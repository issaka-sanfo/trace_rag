package fr.tracerag.model;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class ApiModels {
    private ApiModels() {
    }

    public record IngestRequest(@NotEmpty @Size(max = 20) List<@Valid DocumentInput> documents, boolean replace) {
    }

    public record IngestResponse(
            int accepted,
            int rejected,
            int chunkCount,
            List<String> documentIds,
            List<String> warnings) {
    }

    public record AskRequest(
            @NotBlank @Size(max = 1_000) String question,
            @Min(1) @Max(8) Integer topK) {
    }

    public record Citation(
            String documentId,
            String chunkId,
            String title,
            String quote,
            double score) {
    }

    public record Guardrail(String status, String reason) {
    }

    public record Timing(double retrievalMs, double generationMs, double totalMs) {
    }

    public record AskResponse(
            String traceId,
            String answer,
            double confidence,
            List<Citation> citations,
            Guardrail guardrail,
            Timing timing,
            String provider) {
    }

    public record DocumentMetadata(
            String id,
            String title,
            DocumentKind kind,
            Classification classification,
            String updatedAt,
            int chunkCount) {
    }

    public record FeedbackRequest(
            @NotBlank @Size(max = 64) String traceId,
            @NotBlank @Pattern(regexp = "up|down") String rating,
            @Size(max = 500) String comment) {
    }

    public record FeedbackResponse(boolean saved, String traceId) {
    }
}
