package fr.tracerag.rag;

import java.util.List;

import fr.tracerag.store.SearchHit;

public interface GenerationProvider {
    String name();

    GenerationResult generate(String question, List<SearchHit> hits);

    record GenerationResult(String text, List<String> usedChunkIds) {
    }
}

