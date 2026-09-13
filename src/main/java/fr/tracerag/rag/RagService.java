package fr.tracerag.rag;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import fr.tracerag.config.RagProperties;
import fr.tracerag.model.ApiModels.AskResponse;
import fr.tracerag.model.ApiModels.Citation;
import fr.tracerag.model.ApiModels.Guardrail;
import fr.tracerag.model.ApiModels.Timing;
import fr.tracerag.model.Role;
import fr.tracerag.security.SecurityPolicy;
import fr.tracerag.store.KnowledgeStore;
import fr.tracerag.store.SearchHit;
import fr.tracerag.trace.TraceService;
import org.springframework.stereotype.Service;

@Service
public class RagService {
    private final RagProperties properties;
    private final KnowledgeStore store;
    private final GenerationProvider generator;
    private final SecurityPolicy security;
    private final TraceService traces;

    public RagService(
            RagProperties properties,
            KnowledgeStore store,
            GenerationProvider generator,
            SecurityPolicy security,
            TraceService traces) {
        this.properties = properties;
        this.store = store;
        this.generator = generator;
        this.security = security;
        this.traces = traces;
    }

    public AskResponse ask(String rawQuestion, Role role, int topK) {
        String question = rawQuestion.trim();
        long started = System.nanoTime();
        String traceId = UUID.randomUUID().toString().replace("-", "");
        SecurityPolicy.Decision safety = security.assessQuestion(question);
        if (!safety.allowed()) {
            AskResponse response = new AskResponse(
                    traceId,
                    "Je ne peux pas traiter cette demande. Reformulez-la comme une question sur le corpus autorisé.",
                    0,
                    List.of(),
                    new Guardrail("blocked", safety.reason()),
                    new Timing(0, 0, elapsed(started)),
                    generator.name());
            log(question, role, response, List.of());
            return response;
        }

        long retrievalStarted = System.nanoTime();
        List<SearchHit> hits = store.search(question, role, topK);
        double retrievalMs = elapsed(retrievalStarted);
        double relativeThreshold = hits.isEmpty() ? 1 : hits.getFirst().score() * 0.82;
        double effectiveThreshold = Math.max(properties.minRetrievalScore(), relativeThreshold);
        List<SearchHit> usefulHits = hits.stream().filter(hit -> hit.score() >= effectiveThreshold).toList();

        if (usefulHits.isEmpty()) {
            AskResponse response = new AskResponse(
                    traceId,
                    "Je ne trouve pas de source suffisamment fiable dans le corpus autorisé pour répondre.",
                    0,
                    List.of(),
                    new Guardrail(
                            "insufficient_context",
                            "Score de recherche inférieur au seuil %.2f.".formatted(properties.minRetrievalScore())),
                    new Timing(retrievalMs, 0, elapsed(started)),
                    generator.name());
            log(question, role, response, hits);
            return response;
        }

        long generationStarted = System.nanoTime();
        GenerationProvider.GenerationResult generated = generator.generate(question, usefulHits);
        double generationMs = elapsed(generationStarted);
        Set<String> usedIds = new HashSet<>(generated.usedChunkIds());
        List<Citation> citations = usefulHits.stream()
                .filter(hit -> usedIds.contains(hit.chunk().id()))
                .map(hit -> new Citation(
                        hit.chunk().documentId(),
                        hit.chunk().id(),
                        hit.chunk().title(),
                        TextSupport.truncate(hit.chunk().text(), 240),
                        round(hit.score(), 4)))
                .toList();
        AskResponse response = new AskResponse(
                traceId,
                generated.text(),
                confidence(usefulHits.getFirst().score(), citations.size()),
                citations,
                new Guardrail("passed", null),
                new Timing(retrievalMs, generationMs, elapsed(started)),
                generator.name());
        log(question, role, response, hits);
        return response;
    }

    private double confidence(double topScore, int citationCount) {
        double normalized = (topScore - properties.minRetrievalScore())
                / Math.max(0.01, 0.55 - properties.minRetrievalScore());
        double citationBonus = Math.min(citationCount, 2) * 0.06;
        return round(Math.max(0.08, Math.min(0.98, 0.45 + normalized * 0.42 + citationBonus)), 2);
    }

    private void log(String question, Role role, AskResponse response, List<SearchHit> hits) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("timestamp", Instant.now().toString());
        event.put("event", "rag_answer");
        event.put("traceId", response.traceId());
        event.put("role", role.name().toLowerCase());
        event.put("question", question);
        event.put("answer", response.answer());
        Map<String, Object> guardrail = new LinkedHashMap<>();
        guardrail.put("status", response.guardrail().status());
        guardrail.put("reason", response.guardrail().reason());
        event.put("guardrail", guardrail);
        event.put("confidence", response.confidence());

        List<Map<String, Object>> retrieval = new ArrayList<>();
        hits.forEach(hit -> retrieval.add(Map.of(
                "chunkId", hit.chunk().id(),
                "documentId", hit.chunk().documentId(),
                "score", round(hit.score(), 4))));
        event.put("retrieval", retrieval);
        event.put("citations", response.citations());
        event.put("timingMs", response.timing());
        event.put("versions", Map.of(
                "app", properties.version(),
                "embedding", store.embedder().name(),
                "generator", generator.name(),
                "policy", "2026-09-13"));
        traces.append(event);
    }

    private static double elapsed(long started) {
        return round((System.nanoTime() - started) / 1_000_000.0, 2);
    }

    private static double round(double value, int digits) {
        double factor = Math.pow(10, digits);
        return Math.round(value * factor) / factor;
    }
}
