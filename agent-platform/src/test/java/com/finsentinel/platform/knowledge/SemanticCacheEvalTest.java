package com.finsentinel.platform.knowledge;

import com.finsentinel.platform.PlatformTest;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Would a semantic cache (reuse a search result when a new question embeds close to a cached one) be safe for
 * regulation searches? Replays questions through a simulated cache at several cosine thresholds and counts hits whose
 * cached passages do not answer the new question. This measurement is why the cache matches exact questions only
 * (ADR 0013).
 *
 * <p>Embeddings only (about 1,100 tokens of text-embedding-3-small, well under $0.001; cached on disk after the first
 * run). Run: ./mvnw -pl agent-platform test -Dtest=SemanticCacheEvalTest -Dlive=true -Dsurefire.failIfNoSpecifiedTests=false
 */
@PlatformTest
@TestPropertySource(properties = "finsentinel.test.fake-ai=false")
@EnabledIfSystemProperty(named = "live", matches = "true")
class SemanticCacheEvalTest {

    private static final double[] THRESHOLDS = {0.70, 0.75, 0.80, 0.85, 0.90, 0.95};
    private static final int SHUFFLES = 50;

    @Autowired
    Embedder embedder;
    @Autowired
    JsonMapper json;

    @Test
    void embeddingSimilarityCannotTellNearMissesFromParaphrases() throws IOException {
        JsonNode data;
        try (InputStream in = new ClassPathResource("semantic-cache-eval.json").getInputStream()) {
            data = json.readTree(in);
        }
        List<Question> questions = new ArrayList<>();
        for (JsonNode group : data.get("groups")) {
            Set<String> relevant = new HashSet<>();
            group.get("relevant").forEach(r -> relevant.add(r.asString()));
            group.get("queries").forEach(q -> questions.add(new Question(group.get("id").asString(), relevant,
                    q.asString(), null)));
        }
        List<float[]> vectors = embedder.embed(questions.stream().map(Question::text).toList());
        for (int i = 0; i < questions.size(); i++) {
            questions.set(i, questions.get(i).withVector(vectors.get(i)));
        }

        List<Double> sameAnswer = new ArrayList<>();
        List<Pair> differentAnswer = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            for (int j = i + 1; j < questions.size(); j++) {
                Question a = questions.get(i);
                Question b = questions.get(j);
                double s = cosine(a.vector(), b.vector());
                if (a.answeredLike(b)) {
                    sameAnswer.add(s);
                } else {
                    differentAnswer.add(new Pair(s, a, b));
                }
            }
        }
        Collections.sort(sameAnswer);
        differentAnswer.sort(Comparator.comparingDouble(Pair::similarity).reversed());
        double median = sameAnswer.get(sameAnswer.size() / 2);

        System.out.printf("%nSemantic cache evaluation: %d questions in %d groups, text-embedding-3-small%n",
                questions.size(), data.get("groups").size());
        System.out.printf("same-answer pairs: %d, cosine min %.3f / median %.3f / max %.3f%n", sameAnswer.size(),
                sameAnswer.getFirst(), median, sameAnswer.getLast());
        System.out.println("closest different-answer pairs:");
        differentAnswer.stream().limit(5).forEach(p -> System.out.printf("  %.3f  %s%n         %s%n",
                p.similarity(), p.a().text(), p.b().text()));
        System.out.println("agent-written pairs:");
        for (JsonNode pair : data.get("agentPairs").get("pairs")) {
            List<float[]> v = embedder.embed(List.of(pair.get("a").asString(), pair.get("b").asString()));
            System.out.printf("  %.3f  %-9s %s%n                   %s%n", cosine(v.get(0), v.get(1)),
                    pair.get("sameAnswer").asBoolean() ? "same" : "DIFFERENT", pair.get("a").asString(),
                    pair.get("b").asString());
        }

        System.out.printf("%nsimulated cache, %d random orders: threshold / hits per lookup / wrong hits%n", SHUFFLES);
        for (double t : THRESHOLDS) {
            Replay r = replay(questions, t);
            System.out.printf("  %.2f   %5.1f%%   %5.1f%% of hits%n", t, 100.0 * r.hits() / r.lookups(),
                    r.hits() == 0 ? 0 : 100.0 * r.wrong() / r.hits());
        }

        // The finding the design rests on: a question needing different passages scores above the typical paraphrase.
        assertThat(differentAnswer.getFirst().similarity()).isGreaterThan(median);
    }

    /** Questions arrive in random order; each is answered by the nearest cached question above the threshold. */
    private static Replay replay(List<Question> questions, double threshold) {
        int lookups = 0;
        int hits = 0;
        int wrong = 0;
        for (int seed = 0; seed < SHUFFLES; seed++) {
            List<Question> order = new ArrayList<>(questions);
            Collections.shuffle(order, new Random(seed));
            List<Question> cached = new ArrayList<>();
            for (Question q : order) {
                lookups++;
                Question nearest = cached.stream()
                        .max(Comparator.comparingDouble(c -> cosine(c.vector(), q.vector()))).orElse(null);
                if (nearest != null && cosine(nearest.vector(), q.vector()) >= threshold) {
                    hits++;
                    if (!nearest.answeredLike(q)) {
                        wrong++;
                    }
                } else {
                    cached.add(q);
                }
            }
        }
        return new Replay(lookups, hits, wrong);
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return dot / Math.sqrt(na * nb);
    }

    @SuppressWarnings("ArrayRecordComponent") // compared by group and text, never by the vector
    private record Question(String group, Set<String> relevant, String text, float[] vector) {

        Question withVector(float[] v) {
            return new Question(group, relevant, text, v);
        }

        /** The same passages answer both: a result cached for one would be right for the other. */
        boolean answeredLike(Question other) {
            return relevant.stream().anyMatch(other.relevant()::contains);
        }
    }

    private record Pair(double similarity, Question a, Question b) {
    }

    private record Replay(int lookups, int hits, int wrong) {
    }
}
