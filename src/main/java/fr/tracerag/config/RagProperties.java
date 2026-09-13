package fr.tracerag.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("tracerag")
public record RagProperties(
        String version,
        String provider,
        double minRetrievalScore,
        int defaultTopK,
        int maxQueryChars,
        int maxDocumentChars,
        Path runtimeDir,
        Path tracePath,
        String adminApiKey,
        Ollama ollama) {

    public record Ollama(String baseUrl, String embeddingModel, String chatModel) {
    }
}

