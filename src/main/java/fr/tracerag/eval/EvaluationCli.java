package fr.tracerag.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import fr.tracerag.TraceRagApplication;
import fr.tracerag.model.ApiModels.AskResponse;
import fr.tracerag.model.Role;
import fr.tracerag.rag.RagService;
import fr.tracerag.rag.TextSupport;
import fr.tracerag.store.Chunk;
import fr.tracerag.store.KnowledgeStore;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

public final class EvaluationCli {
    private EvaluationCli() {
    }

    public static void main(String[] args) throws Exception {
        Path evalLog = Path.of("logs/eval-traces.jsonl");
        Files.deleteIfExists(evalLog);
        String[] applicationArgs = Arrays.copyOf(args, args.length + 1);
        applicationArgs[args.length] = "--tracerag.trace-path=" + evalLog;
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(TraceRagApplication.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "spring.main.banner-mode=off",
                        "logging.level.root=ERROR",
                        "tracerag.provider=local")
                .run(applicationArgs)) {
            int exit = evaluate(
                    context.getBean(RagService.class),
                    context.getBean(KnowledgeStore.class),
                    context.getBean(ObjectMapper.class),
                    evalLog);
            if (exit != 0) {
                throw new IllegalStateException("Le quality gate RAG a échoué.");
            }
        }
    }

    static int evaluate(RagService rag, KnowledgeStore store, ObjectMapper mapper, Path evalLog) throws IOException {
        EvalCase[] cases = mapper.readValue(
                new ClassPathResource("eval/golden-set.json").getInputStream(), EvalCase[].class);
        Map<String, Chunk> chunks = store.chunks().stream().collect(Collectors.toMap(Chunk::id, chunk -> chunk));
        List<Result> results = new ArrayList<>();
        List<Boolean> retrievalChecks = new ArrayList<>();
        List<Double> keywordChecks = new ArrayList<>();
        List<Boolean> statusChecks = new ArrayList<>();
        List<Boolean> citationChecks = new ArrayList<>();
        List<Boolean> accessChecks = new ArrayList<>();
        List<Double> latencies = new ArrayList<>();

        for (EvalCase evalCase : cases) {
            AskResponse response = rag.ask(evalCase.question(), evalCase.role(), 4);
            Set<String> citedDocuments = response.citations().stream()
                    .map(citation -> citation.documentId())
                    .collect(Collectors.toSet());
            boolean statusOk = response.guardrail().status().equals(evalCase.expectedStatus());
            boolean retrievalOk = evalCase.expectedDocuments().isEmpty()
                    || evalCase.expectedDocuments().stream().anyMatch(citedDocuments::contains);
            String normalizedAnswer = TextSupport.fold(response.answer());
            double keywordScore = evalCase.expectedKeywords().isEmpty()
                    ? 1
                    : evalCase.expectedKeywords().stream()
                            .filter(keyword -> normalizedAnswer.contains(TextSupport.fold(keyword)))
                            .count() / (double) evalCase.expectedKeywords().size();
            boolean citationsOk = response.citations().stream().allMatch(citation -> {
                Chunk source = chunks.get(citation.chunkId());
                return source != null && source.documentId().equals(citation.documentId());
            });
            boolean accessOk = evalCase.forbiddenDocuments().stream().noneMatch(citedDocuments::contains);

            statusChecks.add(statusOk);
            citationChecks.add(citationsOk);
            accessChecks.add(accessOk);
            if (!evalCase.expectedDocuments().isEmpty()) retrievalChecks.add(retrievalOk);
            if (!evalCase.expectedKeywords().isEmpty()) keywordChecks.add(keywordScore);
            latencies.add(response.timing().totalMs());
            results.add(new Result(
                    evalCase.id(), response.guardrail().status(), statusOk, retrievalOk, keywordScore,
                    citationsOk, accessOk, citedDocuments.stream().sorted().toList(),
                    response.timing().totalMs(), response.answer(), response.traceId()));
        }

        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("retrievalHitRate", ratio(retrievalChecks));
        metrics.put("answerKeywordCoverage", keywordChecks.stream().mapToDouble(Double::doubleValue).average().orElse(1));
        metrics.put("guardrailAccuracy", ratio(statusChecks));
        metrics.put("citationIntegrity", ratio(citationChecks));
        metrics.put("accessControlPassRate", ratio(accessChecks));
        metrics.put("p95LatencyMs", percentile(latencies, .95));
        double score = metrics.get("retrievalHitRate") * .30
                + metrics.get("answerKeywordCoverage") * .25
                + metrics.get("guardrailAccuracy") * .20
                + metrics.get("citationIntegrity") * .15
                + metrics.get("accessControlPassRate") * .10;
        metrics.put("qualityScore", score);

        String generatedAt = Instant.now().toString();
        Files.createDirectories(Path.of("reports"));
        Files.writeString(Path.of("reports/eval.md"), report(generatedAt, metrics, results), StandardCharsets.UTF_8);
        mapper.writerWithDefaultPrettyPrinter().writeValue(
                Path.of("reports/eval.json").toFile(),
                Map.of("generatedAt", generatedAt, "metrics", metrics, "cases", results));

        Files.createDirectories(Path.of("logs"));
        List<String> lines = Files.readAllLines(evalLog, StandardCharsets.UTF_8);
        Files.write(
                Path.of("logs/example-traces.jsonl"),
                lines.stream().limit(3).toList(),
                StandardCharsets.UTF_8);

        System.out.printf("Quality score: %.0f%% · p95: %.2f ms · %d cas%n", score * 100, metrics.get("p95LatencyMs"), cases.length);
        return score >= .90 && metrics.get("guardrailAccuracy") == 1 ? 0 : 1;
    }

    private static String report(String generatedAt, Map<String, Double> metrics, List<Result> results) {
        StringBuilder output = new StringBuilder("""
                # Rapport d’évaluation automatique — Spring

                > Généré le `%s` par `EvaluationCli` (Java 21 / Spring Boot).

                ## Résumé

                | Indicateur | Résultat | Seuil | Statut |
                |---|---:|---:|:---:|
                """.formatted(generatedAt));
        row(output, "Hit rate retrieval", metrics.get("retrievalHitRate"), .90, true);
        row(output, "Couverture des mots-clés", metrics.get("answerKeywordCoverage"), .85, true);
        row(output, "Exactitude des garde-fous", metrics.get("guardrailAccuracy"), 1, true);
        row(output, "Intégrité des citations", metrics.get("citationIntegrity"), 1, true);
        row(output, "Contrôle d’accès", metrics.get("accessControlPassRate"), 1, true);
        output.append("| Latence p95 locale | %.2f ms | < 200 ms | %s |%n".formatted(
                metrics.get("p95LatencyMs"), mark(metrics.get("p95LatencyMs") < 200)));
        output.append("| **Score qualité pondéré** | **%.0f%%** | **≥ 90 %%** | **%s** |%n%n".formatted(
                metrics.get("qualityScore") * 100, mark(metrics.get("qualityScore") >= .90)));
        output.append("Le score combine retrieval (30 %), couverture factuelle (25 %), garde-fous (20 %), citations (15 %) et contrôle d’accès (10 %).\n\n");
        output.append("## Cas testés\n\n| Cas | Décision | Sources | Mots-clés | Latence |\n|---|---|---|---:|---:|\n");
        for (Result result : results) {
            boolean passed = result.statusOk() && result.retrievalOk() && result.citationsOk() && result.accessOk();
            String sources = result.citedDocuments().isEmpty() ? "—" : String.join(", ", result.citedDocuments());
            output.append("| %s `%s` | `%s` | %s | %.0f%% | %.2f ms |%n".formatted(
                    mark(passed), result.id(), result.status(), sources, result.keywordScore() * 100, result.latencyMs()));
        }
        output.append("""

                ## Limites

                - Le corpus et les dix cas sont synthétiques : ils valident le pipeline, pas une qualité de production.
                - La couverture par mots-clés est déterministe mais moins nuancée qu’un juge LLM calibré par des humains.
                - Le provider local favorise l’ancrage factuel ; le provider Ollama doit avoir sa propre baseline avant promotion.
                - Étape suivante : 100+ questions réelles, double annotation humaine et tests adversariaux continus.
                """);
        return output.toString();
    }

    private static void row(StringBuilder output, String name, double value, double threshold, boolean higherIsBetter) {
        boolean passed = higherIsBetter ? value >= threshold : value <= threshold;
        output.append("| %s | %.0f%% | %s %.0f %% | %s |%n".formatted(
                name, value * 100, higherIsBetter ? "≥" : "≤", threshold * 100, mark(passed)));
    }

    private static String mark(boolean passed) {
        return passed ? "✅" : "❌";
    }

    private static double ratio(List<Boolean> values) {
        return values.isEmpty() ? 1 : values.stream().filter(Boolean::booleanValue).count() / (double) values.size();
    }

    private static double percentile(List<Double> values, double percentile) {
        double[] ordered = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        if (ordered.length == 0) return 0;
        int index = Math.max(0, (int) Math.ceil(percentile * ordered.length) - 1);
        return ordered[index];
    }

    public record EvalCase(
            String id,
            String question,
            Role role,
            String expectedStatus,
            List<String> expectedDocuments,
            List<String> expectedKeywords,
            List<String> forbiddenDocuments) {
        public EvalCase {
            expectedDocuments = expectedDocuments == null ? List.of() : List.copyOf(expectedDocuments);
            expectedKeywords = expectedKeywords == null ? List.of() : List.copyOf(expectedKeywords);
            forbiddenDocuments = forbiddenDocuments == null ? List.of() : List.copyOf(forbiddenDocuments);
        }
    }

    public record Result(
            String id,
            String status,
            boolean statusOk,
            boolean retrievalOk,
            double keywordScore,
            boolean citationsOk,
            boolean accessOk,
            List<String> citedDocuments,
            double latencyMs,
            String answer,
            String traceId) {
    }
}
