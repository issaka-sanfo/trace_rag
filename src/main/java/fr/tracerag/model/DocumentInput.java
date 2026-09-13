package fr.tracerag.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record DocumentInput(
        @NotBlank @Pattern(regexp = "^[a-zA-Z0-9_-]{2,64}$") String id,
        @NotBlank @Size(min = 3, max = 160) String title,
        @NotNull DocumentKind kind,
        @NotNull Classification classification,
        @NotBlank @Size(min = 20, max = 50_000) String content,
        String updatedAt) {
}

