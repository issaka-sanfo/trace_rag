package fr.tracerag.rag;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "tracerag.provider", havingValue = "local", matchIfMissing = true)
public class LocalEmbeddingProvider implements EmbeddingProvider {
    private static final int DIMENSIONS = 768;
    private static final Map<String, Set<String>> CONCEPTS = Map.of(
            "battery", Set.of("autonomie", "batterie", "charge", "recharge"),
            "bluetooth_issue", Set.of("bluetooth", "deconnexion", "deconnexions", "scanner", "scanners"),
            "refund", Set.of("remboursement", "rembourser", "rembourse", "remboursable", "remboursables"),
            "retention", Set.of("garder", "gardez", "conserver", "conservees", "conservation", "retention", "supprimer", "supprimees"),
            "trace_log", Set.of("log", "logs", "journal", "journaux", "trace", "traces", "journalisation"),
            "support_sla", Set.of("sla", "support", "assistance", "reponse"),
            "subscription", Set.of("abonnement", "abonnements", "offre", "offres", "tarif", "prix"));

    @Override
    public String name() {
        return "semantic-lite-java-v1";
    }

    @Override
    public double[] embed(String text) {
        List<String> words = TextSupport.tokenize(text);
        List<String> features = new ArrayList<>();
        words.forEach(word -> features.add("w:" + word));
        for (int index = 0; index + 1 < words.size(); index++) {
            features.add("b:" + words.get(index) + "_" + words.get(index + 1));
        }

        Set<String> wordSet = new HashSet<>(words);
        CONCEPTS.forEach((concept, aliases) -> {
            long matches = aliases.stream().filter(wordSet::contains).count();
            for (int count = 0; count < matches * 3; count++) {
                features.add("concept:" + concept);
            }
        });

        String normalized = String.join("_", words);
        for (int index = 0; index + 2 < normalized.length(); index++) {
            features.add("c:" + normalized.substring(index, index + 3));
        }

        Map<String, Integer> counts = new HashMap<>();
        features.forEach(feature -> counts.merge(feature, 1, Integer::sum));
        double[] vector = new double[DIMENSIONS];
        counts.forEach((feature, count) -> {
            long hash = hash(feature);
            int position = Math.floorMod(hash, DIMENSIONS);
            double sign = (hash & 1) == 1 ? 1.0 : -1.0;
            vector[position] += sign * (1.0 + Math.log(count));
        });

        double norm = Math.sqrt(java.util.Arrays.stream(vector).map(value -> value * value).sum());
        if (norm > 0) {
            for (int index = 0; index < vector.length; index++) {
                vector[index] /= norm;
            }
        }
        return vector;
    }

    private long hash(String feature) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(feature.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest).getLong();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 indisponible", impossible);
        }
    }
}

