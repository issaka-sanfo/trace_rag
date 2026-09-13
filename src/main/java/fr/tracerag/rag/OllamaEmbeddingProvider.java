package fr.tracerag.rag;

import java.util.Map;
import java.util.stream.StreamSupport;

import fr.tracerag.config.RagProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
@ConditionalOnProperty(name = "tracerag.provider", havingValue = "ollama")
public class OllamaEmbeddingProvider implements EmbeddingProvider {
    private final OllamaClient client;
    private final String model;

    public OllamaEmbeddingProvider(OllamaClient client, RagProperties properties) {
        this.client = client;
        this.model = properties.ollama().embeddingModel();
    }

    @Override
    public String name() {
        return "ollama/" + model;
    }

    @Override
    public double[] embed(String text) {
        JsonNode embeddings = client.post("/api/embed", Map.of(
                "model", model,
                "input", text,
                "truncate", true,
                "keep_alive", 0)).path("embeddings");
        if (!embeddings.isArray() || embeddings.isEmpty()) {
            throw new OllamaClient.OllamaException("Ollama n'a retourné aucun embedding.");
        }
        return StreamSupport.stream(embeddings.get(0).spliterator(), false)
                .mapToDouble(JsonNode::asDouble)
                .toArray();
    }
}
