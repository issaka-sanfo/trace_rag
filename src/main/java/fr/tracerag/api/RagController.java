package fr.tracerag.api;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import fr.tracerag.config.RagProperties;
import fr.tracerag.model.ApiModels.AskRequest;
import fr.tracerag.model.ApiModels.AskResponse;
import fr.tracerag.model.ApiModels.DocumentMetadata;
import fr.tracerag.model.ApiModels.FeedbackRequest;
import fr.tracerag.model.ApiModels.FeedbackResponse;
import fr.tracerag.model.ApiModels.IngestRequest;
import fr.tracerag.model.ApiModels.IngestResponse;
import fr.tracerag.model.Role;
import fr.tracerag.rag.GenerationProvider;
import fr.tracerag.rag.RagService;
import fr.tracerag.store.KnowledgeStore;
import fr.tracerag.store.KnowledgeStore.IngestOutcome;
import fr.tracerag.trace.TraceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api")
public class RagController {
    private final RagProperties properties;
    private final KnowledgeStore store;
    private final RagService rag;
    private final TraceService traces;
    private final GenerationProvider generator;

    public RagController(
            RagProperties properties,
            KnowledgeStore store,
            RagService rag,
            TraceService traces,
            GenerationProvider generator) {
        this.properties = properties;
        this.store = store;
        this.rag = rag;
        this.traces = traces;
        this.generator = generator;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "version", properties.version(),
                "documents", store.documentCount(),
                "chunks", store.chunks().size(),
                "providers", Map.of(
                        "embedding", store.embedder().name(),
                        "generation", generator.name()));
    }

    @GetMapping("/documents")
    public List<DocumentMetadata> documents(
            @RequestHeader(name = "X-Role", required = false) String roleHeader) {
        return store.metadata(Role.fromHeader(roleHeader));
    }

    @PostMapping("/ask")
    public AskResponse ask(
            @Valid @RequestBody AskRequest request,
            @RequestHeader(name = "X-Role", required = false) String roleHeader) {
        if (request.question().length() > properties.maxQueryChars()) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "La question dépasse la taille autorisée.");
        }
        int topK = request.topK() == null ? properties.defaultTopK() : request.topK();
        return rag.ask(request.question(), Role.fromHeader(roleHeader), topK);
    }

    @PostMapping("/ingest")
    public IngestResponse ingest(
            @Valid @RequestBody IngestRequest request,
            @RequestHeader(name = "X-Role", required = false) String roleHeader,
            @RequestHeader(name = "X-API-Key", required = false) String apiKey) throws IOException {
        requireAdmin(roleHeader, apiKey);
        if (request.documents().stream().anyMatch(document -> document.content().length() > properties.maxDocumentChars())) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "Un document dépasse la taille autorisée.");
        }
        IngestOutcome outcome = store.ingest(request.documents(), request.replace());
        return new IngestResponse(
                outcome.accepted().size(),
                request.documents().size() - outcome.accepted().size(),
                outcome.chunkCount(),
                outcome.accepted(),
                outcome.warnings());
    }

    @PostMapping("/feedback")
    public FeedbackResponse feedback(@Valid @RequestBody FeedbackRequest request) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("timestamp", Instant.now().toString());
        event.put("event", "user_feedback");
        event.put("traceId", request.traceId());
        event.put("rating", request.rating());
        event.put("comment", request.comment() == null ? "" : request.comment());
        traces.append(event);
        return new FeedbackResponse(true, request.traceId());
    }

    @GetMapping("/traces")
    public List<JsonNode> traces(
            @RequestHeader(name = "X-Role", required = false) String roleHeader,
            @RequestHeader(name = "X-API-Key", required = false) String apiKey,
            @RequestParam(defaultValue = "20") int limit) {
        requireAdmin(roleHeader, apiKey);
        if (limit < 1 || limit > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit doit être compris entre 1 et 100.");
        }
        return traces.recent(limit);
    }

    private void requireAdmin(String roleHeader, String apiKey) {
        if (Role.fromHeader(roleHeader) != Role.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Rôle admin requis.");
        }
        if (properties.adminApiKey() != null
                && !properties.adminApiKey().isBlank()
                && !properties.adminApiKey().equals(apiKey)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Clé d'administration invalide.");
        }
    }
}

