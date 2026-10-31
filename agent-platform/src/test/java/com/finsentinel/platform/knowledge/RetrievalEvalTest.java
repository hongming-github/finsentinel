package com.finsentinel.platform.knowledge;

import com.finsentinel.platform.PlatformTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compares the retrieval stages on the real MAS corpus with real embeddings and the real reranker.
 * Needs scripts/fetch-knowledge.sh and OPENAI_API_KEY. First run embeds the corpus (then cached on disk);
 * every run makes one small rerank call per question.
 * Run: ./mvnw -pl agent-platform test -Dtest=RetrievalEvalTest -Dlive=true -Dsurefire.failIfNoSpecifiedTests=false
 *
 * <p>HYBRID_CROSS_ENCODER runs when the reranker service (finsentinel.knowledge.search.rerank.cross-encoder.url,
 * compose profile reranker) answers /health; add -Dcross-encoder=true to fail instead of skipping it when it does not
 * (ADR 0030). A fallen-back ranking is counted per mode, and any in that mode fails the run: its scores would be the
 * fused order's. -Dretrieval.modes=VECTOR,HYBRID runs only those modes (the ones without a reranker cost nothing once
 * the corpus embeddings are cached); -Dretrieval.ranks=true prints every question's rank in every mode.
 * -Dfinsentinel.knowledge.context-chars=300 measures the reranker with the cross-reference lines (ADR 0030).
 *
 * <p>Questions carry a category and a split. Choose and tune on dev; run test (-Dretrieval.split=test) once to confirm
 * a choice, never to make it. Results come per split and per category, and every mode is compared with HYBRID_RERANK
 * on the same questions (McNemar). recall@20 is the share of questions whose answer is among the twenty candidates a
 * reranker is handed (for the modes without one, among their top twenty). -Dretrieval.pool=FILE writes every mode's
 * top twenty per question, for labelling.
 */
@PlatformTest
@TestPropertySource(properties = "finsentinel.test.fake-ai=false")
@EnabledIfSystemProperty(named = "live", matches = "true")
class RetrievalEvalTest {

    private static final int K = 5;
    /** What a search hands the agent (finsentinel.knowledge.search.top-k): a passage ranked 4th or 5th never reaches it. */
    private static final int AGENT_K = 3;

    @Autowired
    KnowledgeSync sync;
    @Autowired
    KnowledgeSearch search;
    @Autowired
    JsonMapper json;
    @Autowired
    KnowledgeProperties props;

    @BeforeEach
    void index() {
        KnowledgeSync.Result result = sync.sync();
        assertThat(result.missing()).as("run scripts/fetch-knowledge.sh first").isZero();
    }

    @Test
    void compareRetrievalStages() throws IOException {
        List<Question> questions = questions();
        String pool = System.getProperty("retrieval.pool");
        // As deep as the candidates a reranker is handed, so recall@20 says whether fusion let the answer through.
        int depth = props.search().candidates();
        Map<KnowledgeSearch.Mode, Map<String, Score>> scores = new EnumMap<>(KnowledgeSearch.Mode.class);
        Map<KnowledgeSearch.Mode, Long> latencyMs = new EnumMap<>(KnowledgeSearch.Mode.class);
        Map<KnowledgeSearch.Mode, BigDecimal> costUsd = new EnumMap<>(KnowledgeSearch.Mode.class);
        Map<KnowledgeSearch.Mode, Integer> degraded = new EnumMap<>(KnowledgeSearch.Mode.class);
        Map<KnowledgeSearch.Mode, Map<String, Integer>> ranks = new EnumMap<>(KnowledgeSearch.Mode.class);
        Map<String, Map<String, List<String>>> pooled = new LinkedHashMap<>();
        List<String> misses = new ArrayList<>();
        for (KnowledgeSearch.Mode mode : modes()) {
            Map<String, Score> byGroup = new TreeMap<>();
            long started = System.nanoTime();
            for (Question q : questions) {
                KnowledgeSearch.Ranked ranked = search.evaluate(q.text(), mode, depth);
                List<String> all = ranked.hits().stream().map(RegulationHit::chunkId).toList();
                List<String> ids = all.subList(0, Math.min(K, all.size()));
                pooled.computeIfAbsent(q.text(), t -> new LinkedHashMap<>()).put(mode.name(), all);
                costUsd.merge(mode, ranked.usage().costUsd(), BigDecimal::add);
                degraded.merge(mode, ranked.degraded() ? 1 : 0, Integer::sum);
                int rank = firstRelevantRank(ids, q.relevant());
                ranks.computeIfAbsent(mode, m -> new LinkedHashMap<>()).put(q.text(), rank);
                boolean inCandidates = all.stream().anyMatch(q.relevant()::contains);
                for (String group : List.of("all", q.split(), "category:" + q.category())) {
                    byGroup.computeIfAbsent(group, g -> new Score()).add(rank, inCandidates);
                }
                if ((rank == 0 || rank > AGENT_K) && (mode == KnowledgeSearch.Mode.HYBRID_RERANK
                        || mode == KnowledgeSearch.Mode.HYBRID_CROSS_ENCODER)) {
                    misses.add(mode + " " + (rank == 0 ? "" : "rank " + rank + ", not handed to the agent: ") + q.text()
                            + "\n      expected " + q.relevant() + "\n      got      " + ids);
                }
            }
            scores.put(mode, byGroup);
            latencyMs.put(mode, (System.nanoTime() - started) / 1_000_000 / questions.size());
        }

        System.out.printf("%nRetrieval evaluation: %d questions, top %d (hit@%d / hit@%d / MRR)%n", questions.size(), K,
                AGENT_K, K);
        System.out.printf("Rerank in HYBRID_RERANK / VECTOR_RERANK: %s; keyword weight in the fusion: %s%n",
                props.search().rerank().using(), props.search().keywordWeight());
        System.out.printf("%-20s %20s %20s %20s %9s %10s %12s %9s%n", "mode", "dev", "test", "all",
                "recall@" + depth, "ms/query", "USD/query", "degraded");
        scores.forEach((mode, s) -> System.out.printf("%-20s %20s %20s %20s %9.2f %10d %12.6f %9d%n", mode,
                s.getOrDefault("dev", new Score()), s.getOrDefault("test", new Score()), s.get("all"),
                s.get("all").candidateRecall(), latencyMs.get(mode),
                costUsd.get(mode).divide(BigDecimal.valueOf(questions.size()), 8, RoundingMode.HALF_UP),
                degraded.get(mode)));
        List<String> categories = questions.stream().map(Question::category).distinct().toList();
        System.out.printf("%nhit@%d / MRR by category (questions: %s)%n%-20s", AGENT_K, String.join(", ",
                categories.stream().map(c -> c + " " + questions.stream().filter(q -> q.category().equals(c)).count())
                        .toList()), "mode");
        categories.forEach(c -> System.out.printf(" %15s", c));
        System.out.println();
        scores.forEach((mode, s) -> {
            System.out.printf("%-20s", mode);
            categories.forEach(c -> System.out.printf(" %15s", s.get("category:" + c).agentHitsAndMrr()));
            System.out.println();
        });
        if (ranks.containsKey(KnowledgeSearch.Mode.HYBRID_RERANK)) {
            // Paired on the same questions: only the questions one mode gets right and the other wrong carry information.
            System.out.printf("%nhit@%d against HYBRID_RERANK on the same questions (b: only HYBRID_RERANK, c: only the "
                    + "mode; exact McNemar p, two-sided)%n", AGENT_K);
            for (KnowledgeSearch.Mode mode : ranks.keySet()) {
                if (mode != KnowledgeSearch.Mode.HYBRID_RERANK) {
                    for (String split : List.of("all", "test")) {
                        System.out.printf("  %-20s %-4s %s%n", mode, split,
                                mcNemar(questions, split, ranks.get(KnowledgeSearch.Mode.HYBRID_RERANK), ranks.get(mode)));
                    }
                }
            }
        }
        misses.forEach(m -> System.out.println("  MISS " + m));
        if (Boolean.getBoolean("retrieval.ranks")) {
            System.out.println("Rank of the first relevant passage per question (0 = not in the top five):");
            System.out.println("  " + String.join(" ", ranks.keySet().stream().map(KnowledgeSearch.Mode::name).toList()));
            for (Question q : questions) {
                System.out.println("  " + String.join(" ", ranks.values().stream()
                        .map(r -> String.valueOf(r.get(q.text()))).toList()) + "  " + q.text());
            }
        }
        if (ranks.containsKey(KnowledgeSearch.Mode.HYBRID_CROSS_ENCODER)
                && ranks.containsKey(KnowledgeSearch.Mode.HYBRID_RERANK)) {
            // Where the two rerankers disagree: rank of the first relevant passage, 0 = not in the top five.
            System.out.printf("Rank of the first relevant passage where the rerankers differ (%s / cross-encoder):%n",
                    props.search().rerank().using());
            ranks.get(KnowledgeSearch.Mode.HYBRID_RERANK).forEach((q, rank) -> {
                int ce = ranks.get(KnowledgeSearch.Mode.HYBRID_CROSS_ENCODER).get(q);
                if (ce != rank) {
                    System.out.printf("  %d / %d  %s%n", rank, ce, q);
                }
            });
        }
        if (pool != null) {
            // Every mode's top ten per question, for judging which retrieved passages also answer it (pooling).
            json.writeValue(Path.of(pool).toFile(), pooled);
            System.out.println("Pool written to " + pool);
        }
        if (scores.containsKey(KnowledgeSearch.Mode.HYBRID_CROSS_ENCODER)) {
            assertThat(degraded.get(KnowledgeSearch.Mode.HYBRID_CROSS_ENCODER))
                    .as("cross-encoder rankings that fell back to the fused order").isZero();
        }

        // A floor, not a target: catches a broken index or chunker, not small ranking shifts.
        if (scores.containsKey(KnowledgeSearch.Mode.HYBRID)) {
            assertThat(scores.get(KnowledgeSearch.Mode.HYBRID).get("all").hitRate()).isGreaterThanOrEqualTo(0.6);
        }
    }

    /** Discordant pairs and the exact McNemar p-value on hit@3 for the questions of a split. */
    static String mcNemar(List<Question> questions, String split, Map<String, Integer> base, Map<String, Integer> other) {
        int b = 0;
        int c = 0;
        for (Question q : questions) {
            if (split.equals("all") || split.equals(q.split())) {
                boolean baseHit = isAgentHit(base.get(q.text()));
                boolean otherHit = isAgentHit(other.get(q.text()));
                b += baseHit && !otherHit ? 1 : 0;
                c += otherHit && !baseHit ? 1 : 0;
            }
        }
        return "b=%d c=%d p=%.3f".formatted(b, c, McNemar.exactP(b, c));
    }

    private static boolean isAgentHit(int rank) {
        return rank > 0 && rank <= AGENT_K;
    }

    private List<KnowledgeSearch.Mode> modes() {
        List<KnowledgeSearch.Mode> modes = new ArrayList<>(List.of(KnowledgeSearch.Mode.values()));
        String only = System.getProperty("retrieval.modes");
        if (only != null) {
            modes.retainAll(Arrays.stream(only.split(",")).map(String::trim).map(KnowledgeSearch.Mode::valueOf).toList());
        }
        if (modes.contains(KnowledgeSearch.Mode.HYBRID_CROSS_ENCODER) && !crossEncoderUp()) {
            assertThat(Boolean.getBoolean("cross-encoder")).as("-Dcross-encoder=true, but the reranker service at %s "
                    + "does not answer", props.search().rerank().crossEncoder().url()).isFalse();
            System.out.println("Reranker service not reachable: HYBRID_CROSS_ENCODER skipped");
            modes.remove(KnowledgeSearch.Mode.HYBRID_CROSS_ENCODER);
        }
        return modes;
    }

    private boolean crossEncoderUp() {
        KnowledgeProperties.CrossEncoder ce = props.search().rerank().crossEncoder();
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(ce.connectTimeout()).build()) {
            HttpResponse<Void> health = client.send(HttpRequest.newBuilder(ce.url().resolve("/health"))
                    .timeout(ce.timeout()).build(), HttpResponse.BodyHandlers.discarding());
            return health.statusCode() == 200;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static int firstRelevantRank(List<String> ids, Set<String> relevant) {
        for (int i = 0; i < ids.size(); i++) {
            if (relevant.contains(ids.get(i))) {
                return i + 1;
            }
        }
        return 0;
    }

    private List<Question> questions() throws IOException {
        try (InputStream in = new ClassPathResource("retrieval-eval.json").getInputStream()) {
            List<Question> out = new ArrayList<>();
            String split = System.getProperty("retrieval.split", "all");
            for (JsonNode node : json.readTree(in).get("questions")) {
                Set<String> relevant = new HashSet<>();
                node.get("relevant").forEach(r -> relevant.add(r.asString()));
                Question q = new Question(node.get("q").asString(), relevant, node.get("category").asString(),
                        node.get("split").asString());
                if (split.equals("all") || split.equals(q.split())) {
                    out.add(q);
                }
            }
            return out;
        }
    }

    /** @param split dev for choosing and tuning, test for confirming a choice once (see retrieval-eval.json) */
    record Question(String text, Set<String> relevant, String category, String split) {
    }

    private static final class Score {
        private int questions;
        private int hits;
        private int agentHits;
        private int inCandidates;
        private double reciprocalRanks;

        void add(int rank, boolean answerInCandidates) {
            questions++;
            inCandidates += answerInCandidates ? 1 : 0;
            if (rank > 0) {
                hits++;
                reciprocalRanks += 1.0 / rank;
            }
            if (rank > 0 && rank <= AGENT_K) {
                agentHits++;
            }
        }

        double candidateRecall() {
            return (double) inCandidates / questions;
        }

        double hitRate() {
            return (double) hits / questions;
        }

        double mrr() {
            return reciprocalRanks / questions;
        }

        String agentHitsAndMrr() {
            return "%.2f / %.2f".formatted((double) agentHits / questions, mrr());
        }

        @Override
        public String toString() {
            if (questions == 0) {
                return "-";
            }
            return "%.2f / %.2f / %.2f".formatted((double) agentHits / questions, hitRate(), mrr());
        }
    }
}
