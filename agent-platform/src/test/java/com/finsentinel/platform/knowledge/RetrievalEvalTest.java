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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
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
 */
@PlatformTest
@TestPropertySource(properties = "finsentinel.test.fake-ai=false")
@EnabledIfSystemProperty(named = "live", matches = "true")
class RetrievalEvalTest {

    private static final int K = 5;

    @Autowired
    KnowledgeSync sync;
    @Autowired
    KnowledgeSearch search;
    @Autowired
    JsonMapper json;

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
        List<String> misses = new ArrayList<>();
        for (KnowledgeSearch.Mode mode : KnowledgeSearch.Mode.values()) {
            Map<String, Score> byStyle = new TreeMap<>();
            long started = System.nanoTime();
            for (Question q : questions) {
                List<String> ids = search.search(q.text(), mode, K).stream().map(RegulationHit::chunkId).toList();
                int rank = firstRelevantRank(ids, q.relevant());
                byStyle.computeIfAbsent(q.style(), s -> new Score()).add(rank);
                byStyle.computeIfAbsent("all", s -> new Score()).add(rank);
                if (rank == 0 && mode == KnowledgeSearch.Mode.HYBRID_RERANK) {
                    misses.add(q.text() + "\n      expected " + q.relevant() + "\n      got      " + ids);
                }
            }
            scores.put(mode, byStyle);
            latencyMs.put(mode, (System.nanoTime() - started) / 1_000_000 / questions.size());
        }

        System.out.printf("%nRetrieval evaluation: %d questions, top %d (hit@%d / MRR)%n", questions.size(), K, K);
        System.out.printf("%-14s %14s %14s %14s %12s%n", "mode", "paraphrase", "terms", "all", "ms/query");
        scores.forEach((mode, s) -> System.out.printf("%-14s %14s %14s %14s %12d%n", mode,
                s.get("paraphrase"), s.get("terms"), s.get("all"), latencyMs.get(mode)));
        misses.forEach(m -> System.out.println("  MISS " + m));

        // A floor, not a target: catches a broken index or chunker, not small ranking shifts.
        assertThat(scores.get(KnowledgeSearch.Mode.HYBRID).get("all").hitRate()).isGreaterThanOrEqualTo(0.6);
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
        private double reciprocalRanks;

        void add(int rank) {
            questions++;
            if (rank > 0) {
                hits++;
                reciprocalRanks += 1.0 / rank;
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
            return "%.2f / %.2f".formatted(hitRate(), mrr());
        }
    }
}
