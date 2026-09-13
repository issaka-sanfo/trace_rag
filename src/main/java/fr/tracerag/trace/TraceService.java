package fr.tracerag.trace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import fr.tracerag.config.RagProperties;
import fr.tracerag.security.SecurityPolicy;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class TraceService {
    private final RagProperties properties;
    private final SecurityPolicy security;
    private final ObjectMapper mapper;

    public TraceService(RagProperties properties, SecurityPolicy security, ObjectMapper mapper) {
        this.properties = properties;
        this.security = security;
        this.mapper = mapper;
    }

    public synchronized void append(Map<String, Object> event) {
        try {
            if (properties.tracePath().getParent() != null) {
                Files.createDirectories(properties.tracePath().getParent());
            }
            String line = mapper.writeValueAsString(sanitize(event)) + System.lineSeparator();
            Files.writeString(
                    properties.tracePath(),
                    line,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException exception) {
            throw new IllegalStateException("Impossible d'écrire la trace.", exception);
        }
    }

    public synchronized List<JsonNode> recent(int limit) {
        if (!Files.exists(properties.tracePath())) {
            return List.of();
        }
        try {
            List<String> lines = Files.readAllLines(properties.tracePath(), StandardCharsets.UTF_8);
            List<JsonNode> result = new ArrayList<>();
            for (int index = lines.size() - 1; index >= 0 && result.size() < limit; index--) {
                if (!lines.get(index).isBlank()) {
                    result.add(mapper.readTree(lines.get(index)));
                }
            }
            return result;
        } catch (IOException exception) {
            throw new IllegalStateException("Impossible de lire les traces.", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Object sanitize(Object value) {
        if (value instanceof String text) {
            return security.redact(text);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), sanitize(item)));
            return result;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> result = new ArrayList<>();
            iterable.forEach(item -> result.add(sanitize(item)));
            return result;
        }
        return value;
    }
}

