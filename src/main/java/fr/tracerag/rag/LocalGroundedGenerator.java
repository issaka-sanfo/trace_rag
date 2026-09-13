package fr.tracerag.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import fr.tracerag.store.SearchHit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "tracerag.provider", havingValue = "local", matchIfMissing = true)
public class LocalGroundedGenerator implements GenerationProvider {
    private final EmbeddingProvider embedder;

    public LocalGroundedGenerator(EmbeddingProvider embedder) {
        this.embedder = embedder;
    }

    @Override
    public String name() {
        return "grounded-extractive-java-v1";
    }

    @Override
    public GenerationResult generate(String question, List<SearchHit> hits) {
        double[] questionEmbedding = embedder.embed(question);
        List<Candidate> candidates = new ArrayList<>();
        for (SearchHit hit : hits) {
            for (String sentence : TextSupport.sentences(hit.chunk().text())) {
                if (sentence.length() < 25) {
                    continue;
                }
                double relevance = embedder.similarity(questionEmbedding, embedder.embed(sentence));
                candidates.add(new Candidate(
                        0.75 * relevance + 0.25 * hit.score(), sentence, hit.chunk().id()));
            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::score).reversed());

        List<String> selected = new ArrayList<>();
        List<String> usedChunks = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Candidate candidate : candidates) {
            String signature = candidate.sentence().toLowerCase().replaceAll("[ .]+$", "");
            if (seen.add(signature)) {
                selected.add(candidate.sentence());
                usedChunks.add(candidate.chunkId());
            }
            if (selected.size() == 3) {
                break;
            }
        }

        if (selected.isEmpty()) {
            return new GenerationResult("Je ne dispose pas d’assez d’éléments fiables pour répondre.", List.of());
        }
        String answer = selected.size() == 1
                ? selected.getFirst()
                : "Voici ce que les sources internes indiquent :\n\n• " + String.join("\n• ", selected);
        return new GenerationResult(answer, List.copyOf(usedChunks));
    }

    private record Candidate(double score, String sentence, String chunkId) {
    }
}

