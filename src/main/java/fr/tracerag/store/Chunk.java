package fr.tracerag.store;

import fr.tracerag.model.Classification;
import fr.tracerag.model.DocumentKind;

public record Chunk(
        String id,
        String documentId,
        String title,
        DocumentKind kind,
        Classification classification,
        String text,
        double[] embedding) {
}

