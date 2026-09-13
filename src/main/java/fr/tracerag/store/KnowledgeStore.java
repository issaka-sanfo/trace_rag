package fr.tracerag.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import fr.tracerag.config.RagProperties;
import fr.tracerag.model.ApiModels.DocumentMetadata;
import fr.tracerag.model.Classification;
import fr.tracerag.model.DocumentInput;
import fr.tracerag.model.DocumentKind;
import fr.tracerag.model.Role;
import fr.tracerag.rag.EmbeddingProvider;
import fr.tracerag.security.SecurityPolicy;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class KnowledgeStore {
    private static final int MAX_WORDS = 95;
    private static final int OVERLAP = 18;

    private final ResourcePatternResolver resources;
    private final ObjectMapper mapper;
    private final EmbeddingProvider embedder;
    private final SecurityPolicy security;
    private final RagProperties properties;
    private final Map<String, DocumentInput> documents = new LinkedHashMap<>();
    private final List<Chunk> chunks = new ArrayList<>();
    private Set<String> corpusIds = Set.of();

    public KnowledgeStore(
            ResourcePatternResolver resources,
            ObjectMapper mapper,
            EmbeddingProvider embedder,
            SecurityPolicy security,
            RagProperties properties) {
        this.resources = resources;
        this.mapper = mapper;
        this.embedder = embedder;
        this.security = security;
        this.properties = properties;
    }

    @PostConstruct
    public synchronized void load() throws IOException {
        documents.clear();
        Resource[] corpus = resources.getResources("classpath*:corpus/*.json");
        Arrays.sort(corpus, Comparator.comparing(Resource::getFilename, Comparator.nullsLast(String::compareTo)));
        for (Resource resource : corpus) {
            DocumentInput document = normalize(mapper.readValue(resource.getInputStream(), DocumentInput.class));
            documents.put(document.id(), document);
        }
        corpusIds = Set.copyOf(documents.keySet());

        Path runtimeFile = runtimeFile();
        if (Files.exists(runtimeFile)) {
            DocumentInput[] runtimeDocuments = mapper.readValue(runtimeFile.toFile(), DocumentInput[].class);
            Arrays.stream(runtimeDocuments).map(this::normalize).forEach(document -> documents.put(document.id(), document));
        }
        rebuildIndex();
    }

    public synchronized IngestOutcome ingest(List<DocumentInput> inputs, boolean replace) throws IOException {
        List<String> accepted = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (DocumentInput input : inputs) {
            DocumentInput document = normalize(input);
            SecurityPolicy.Decision decision = security.validateDocument(document.content());
            if (!decision.allowed()) {
                warnings.add(document.id() + ": " + decision.reason());
            } else if (documents.containsKey(document.id()) && !replace) {
                warnings.add(document.id() + ": identifiant déjà présent (replace=false). ");
            } else {
                documents.put(document.id(), document);
                accepted.add(document.id());
            }
        }

        if (!accepted.isEmpty()) {
            Files.createDirectories(properties.runtimeDir());
            List<DocumentInput> runtimeDocuments = documents.values().stream()
                    .filter(document -> !corpusIds.contains(document.id()) || accepted.contains(document.id()))
                    .toList();
            mapper.writerWithDefaultPrettyPrinter().writeValue(runtimeFile().toFile(), runtimeDocuments);
            rebuildIndex();
        }
        int chunkCount = (int) chunks.stream().filter(chunk -> accepted.contains(chunk.documentId())).count();
        return new IngestOutcome(List.copyOf(accepted), List.copyOf(warnings), chunkCount);
    }

    public synchronized List<SearchHit> search(String query, Role role, int topK) {
        double[] queryEmbedding = embedder.embed(query);
        return chunks.stream()
                .filter(chunk -> role.canRead(chunk.classification()))
                .map(chunk -> new SearchHit(chunk, embedder.similarity(queryEmbedding, chunk.embedding())))
                .sorted(Comparator.comparingDouble(SearchHit::score).reversed())
                .limit(topK)
                .toList();
    }

    public synchronized List<DocumentMetadata> metadata(Role role) {
        return documents.values().stream()
                .filter(document -> role.canRead(document.classification()))
                .map(document -> new DocumentMetadata(
                        document.id(),
                        document.title(),
                        document.kind(),
                        document.classification(),
                        document.updatedAt(),
                        (int) chunks.stream().filter(chunk -> chunk.documentId().equals(document.id())).count()))
                .sorted(Comparator.comparing(DocumentMetadata::id))
                .toList();
    }

    public synchronized List<Chunk> chunks() {
        return List.copyOf(chunks);
    }

    public synchronized int documentCount() {
        return documents.size();
    }

    public EmbeddingProvider embedder() {
        return embedder;
    }

    private void rebuildIndex() {
        chunks.clear();
        for (DocumentInput document : documents.values()) {
            List<String> parts = chunkText(document.content());
            IntStream.range(0, parts.size()).forEach(index -> {
                String text = parts.get(index);
                chunks.add(new Chunk(
                        document.id() + "#c" + (index + 1),
                        document.id(),
                        document.title(),
                        document.kind(),
                        document.classification(),
                        text,
                        embedder.embed(document.title() + ". " + text)));
            });
        }
    }

    private List<String> chunkText(String content) {
        List<String> result = new ArrayList<>();
        for (String paragraph : content.split("\\R\\s*\\R")) {
            String trimmed = paragraph.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            List<String> words = Arrays.asList(trimmed.split("\\s+"));
            if (words.size() <= MAX_WORDS) {
                result.add(trimmed);
                continue;
            }
            int step = MAX_WORDS - OVERLAP;
            for (int start = 0; start < words.size(); start += step) {
                int end = Math.min(words.size(), start + MAX_WORDS);
                if (end - start >= 12) {
                    result.add(String.join(" ", words.subList(start, end)));
                }
                if (end == words.size()) {
                    break;
                }
            }
        }
        return result;
    }

    private DocumentInput normalize(DocumentInput input) {
        return new DocumentInput(
                input.id().trim(),
                input.title().trim(),
                input.kind() == null ? DocumentKind.OTHER : input.kind(),
                input.classification() == null ? Classification.INTERNAL : input.classification(),
                input.content().trim(),
                input.updatedAt() == null || input.updatedAt().isBlank() ? Instant.now().toString() : input.updatedAt());
    }

    private Path runtimeFile() {
        return properties.runtimeDir().resolve("documents.json");
    }

    public record IngestOutcome(List<String> accepted, List<String> warnings, int chunkCount) {
    }
}

