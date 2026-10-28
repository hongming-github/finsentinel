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
        Map<KnowledgeSearch.Mode, Map<String, Score>> scores = new EnumMap<>(KnowledgeSearch.Mode.class);
        Map<KnowledgeSearch.Mode, Long> latencyMs = new EnumMap<>(KnowledgeSearch.Mode.class);
        Map<KnowledgeSearch.Mode, BigDecimal> costUsd = new EnumMap<>(KnowledgeSearch.Mode.class);
        Map<KnowledgeSearch.Mode, Integer> degraded = new EnumMap<>(KnowledgeSearch.Mode.class);
        Map<KnowledgeSearch.Mode, Map<String, Integer>> ranks = new EnumMap<>(KnowledgeSearch.Mode.class);
        List<String> misses = new ArrayList<>();
        for (KnowledgeSearch.Mode mode : modes()) {
            Map<String, Score> byStyle = new TreeMap<>();
            long started = System.nanoTime();
            for (Question q : questions) {
                KnowledgeSearch.Ranked ranked = search.evaluate(q.text(), mode, K);
                List<String> ids = ranked.hits().stream().map(RegulationHit::chunkId).toList();
                costUsd.merge(mode, ranked.usage().costUsd(), BigDecimal::add);
                degraded.merge(mode, ranked.degraded() ? 1 : 0, Integer::sum);
                int rank = firstRelevantRank(ids, q.relevant());
                ranks.computeIfAbsent(mode, m -> new LinkedHashMap<>()).put(q.text(), rank);
                byStyle.computeIfAbsent(q.style(), s -> new Score()).add(rank);
                byStyle.computeIfAbsent("all", s -> new Score()).add(rank);
                if ((rank == 0 || rank > AGENT_K) && (mode == KnowledgeSearch.Mode.HYBRID_RERANK
                        || mode == KnowledgeSearch.Mode.HYBRID_CROSS_ENCODER)) {
                    misses.add(mode + " " + (rank == 0 ? "" : "rank " + rank + ", not handed to the agent: ") + q.text()
                            + "\n      expected " + q.relevant() + "\n      got      " + ids);
                }
            }
            scores.put(mode, byStyle);
            latencyMs.put(mode, (System.nanoTime() - started) / 1_000_000 / questions.size());
        }

        System.out.printf("%nRetrieval evaluation: %d questions, top %d (hit@%d / hit@%d / MRR)%n", questions.size(), K,
                AGENT_K, K);
        System.out.printf("Rerank in HYBRID_RERANK / VECTOR_RERANK: %s%n", props.search().rerank().using());
        System.out.printf("%-20s %20s %20s %20s %10s %12s %9s%n", "mode", "paraphrase", "terms", "all", "ms/query",
                "USD/query", "degraded");
        scores.forEach((mode, s) -> System.out.printf("%-20s %20s %20s %20s %10d %12.6f %9d%n", mode,
                s.get("paraphrase"), s.get("terms"), s.get("all"), latencyMs.get(mode),
                costUsd.get(mode).divide(BigDecimal.valueOf(questions.size()), 8, RoundingMode.HALF_UP),
                degraded.get(mode)));
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
        if (scores.containsKey(KnowledgeSearch.Mode.HYBRID_CROSS_ENCODER)) {
            assertThat(degraded.get(KnowledgeSearch.Mode.HYBRID_CROSS_ENCODER))
                    .as("cross-encoder rankings that fell back to the fused order").isZero();
        }

        // A floor, not a target: catches a broken index or chunker, not small ranking shifts.
        if (scores.containsKey(KnowledgeSearch.Mode.HYBRID)) {
            assertThat(scores.get(KnowledgeSearch.Mode.HYBRID).get("all").hitRate()).isGreaterThanOrEqualTo(0.6);
        }
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
            for (JsonNode node : json.readTree(in).get("questions")) {
                Set<String> relevant = new HashSet<>();
                node.get("relevant").forEach(r -> relevant.add(r.asString()));
                out.add(new Question(node.get("q").asString(), relevant, node.get("style").asString()));
            }
            return out;
        }
    }

    private record Question(String text, Set<String> relevant, String style) {
    }

    private static final class Score {
        private int questions;
        private int hits;
        private int agentHits;
        private double reciprocalRanks;

        void add(int rank) {
            questions++;
            if (rank > 0) {
                hits++;
                reciprocalRanks += 1.0 / rank;
            }
            if (rank > 0 && rank <= AGENT_K) {
                agentHits++;
            }
        }

        double hitRate() {
            return (double) hits / questions;
        }

        double mrr() {
            return reciprocalRanks / questions;
        }

        @Override
        public String toString() {
            return "%.2f / %.2f / %.2f".formatted((double) agentHits / questions, hitRate(), mrr());
        }
    }
}
