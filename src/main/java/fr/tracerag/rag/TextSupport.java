package fr.tracerag.rag;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class TextSupport {
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern TOKENS = Pattern.compile("[a-z0-9]+(?:\\.[a-z0-9]+)?");
    private static final Pattern SENTENCES = Pattern.compile("(?<=[.!?])\\s+|\\s*[•]\\s*");
    private static final Set<String> STOPWORDS = Set.of(
            "au", "aux", "avec", "ce", "ces", "cette", "dans", "de", "des", "du", "elle",
            "en", "est", "et", "il", "je", "la", "le", "les", "leur", "leurs", "mais", "mes",
            "mon", "ne", "nos", "notre", "nous", "on", "ou", "par", "pas", "pour", "que", "quel",
            "quelle", "quelles", "quels", "qui", "sa", "se", "ses", "son", "sur", "tu", "un", "une",
            "vos", "votre", "vous", "comment", "combien", "peut", "puis", "faire");

    private TextSupport() {
    }

    public static String fold(String value) {
        String normalized = Normalizer.normalize(value.toLowerCase(), Normalizer.Form.NFKD);
        return COMBINING_MARKS.matcher(normalized).replaceAll("");
    }

    public static List<String> tokenize(String value) {
        return TOKENS.matcher(fold(value)).results()
                .map(result -> result.group())
                .filter(token -> token.length() > 1 && !STOPWORDS.contains(token))
                .toList();
    }

    public static List<String> sentences(String value) {
        String compact = value.replaceAll("\\s+", " ").trim();
        if (compact.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(SENTENCES.split(compact))
                .map(String::trim)
                .filter(sentence -> !sentence.isEmpty())
                .toList();
    }

    public static String truncate(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit - 1).stripTrailing() + "…";
    }
}

