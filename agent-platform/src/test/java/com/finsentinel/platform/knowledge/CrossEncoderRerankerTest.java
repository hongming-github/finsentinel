package com.finsentinel.platform.knowledge;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The client against a fake reranker service: what it sends, how it orders, and that every failure is thrown. */
class CrossEncoderRerankerTest {

    private static final String MODEL = "BAAI/bge-reranker-v2-m3";
    private static final String QUERY = "cooling off period for a payee added on a new device";
    private static final List<Reranker.Candidate> CANDIDATES = List.of(
            new Reranker.Candidate("pay:1.1", "Overview of the guidelines"),
            new Reranker.Candidate("pay:2.1", "A cooling off period of at least twelve hours"),
            new Reranker.Candidate("pay:3.1", "File a suspicious transaction report"));

    private final JsonMapper json = JsonMapper.builder().build();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<Answer> answer = new AtomicReference<>();
    private HttpServer service;

    private record Answer(int status, String body, Duration delay) {
    }

    @BeforeEach
    void startService() throws IOException {
        service = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        service.createContext("/rerank", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Answer a = answer.get();
            try {
                Thread.sleep(a.delay());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = a.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(a.status(), body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            } catch (IOException e) {
                // The client gave up (timeout test).
            }
        });
        service.start();
    }

    @AfterEach
    void stopService() {
        service.stop(0);
    }

    @Test
    void sendsModelQueryAndPassagesAndOrdersByScore() {
        answer(200, "{\"model\":\"" + MODEL + "\",\"scores\":[0.10,0.97,0.42]}");

        assertThat(reranker().rerank(QUERY, CANDIDATES)).containsExactly("pay:2.1", "pay:3.1", "pay:1.1");

        JsonNode sent = json.readTree(requestBody.get());
        assertThat(sent.get("model").asString()).isEqualTo(MODEL);
        assertThat(sent.get("query").asString()).isEqualTo(QUERY);
        assertThat(sent.get("passages").valueStream().map(JsonNode::asString))
                .containsExactly(CANDIDATES.stream().map(Reranker.Candidate::text).toArray(String[]::new));
    }

    @Test
    void equalScoresKeepTheFusedOrderAndNothingIsDropped() {
        answer(200, "{\"model\":\"" + MODEL + "\",\"scores\":[0.0,0.5,0.0]}");

        assertThat(reranker().rerank(QUERY, CANDIDATES)).containsExactly("pay:2.1", "pay:1.1", "pay:3.1");
    }

    @Test
    void callsNothingForNoCandidates() {
        answer(500, "{}");

        assertThat(reranker().rankWithUsage(QUERY, List.of()).ids()).isEmpty();
        assertThat(requestBody.get()).isNull();
    }

    @Test
    void costsNothing() {
        answer(200, "{\"model\":\"" + MODEL + "\",\"scores\":[0.1,0.2,0.3]}");

        assertThat(reranker().rankWithUsage(QUERY, CANDIDATES).usage().costUsd()).isZero();
    }

    @Test
    void serverErrorsAreThrown() {
        answer(503, "{\"detail\":\"model loading\"}");

        assertThatThrownBy(() -> reranker().rerank(QUERY, CANDIDATES)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void aSlowServiceTimesOut() {
        answer(200, "{\"model\":\"" + MODEL + "\",\"scores\":[0.1,0.2,0.3]}", Duration.ofSeconds(3));

        long started = System.nanoTime();
        assertThatThrownBy(() -> reranker().rerank(QUERY, CANDIDATES)).isInstanceOf(RuntimeException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void anUnreachableServiceIsThrown() {
        URI closed = URI.create("http://localhost:" + service.getAddress().getPort());
        service.stop(0);

        assertThatThrownBy(() -> reranker(closed, ObservationRegistry.NOOP).rerank(QUERY, CANDIDATES))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void anAnswerThatDoesNotFitIsThrown() {
        answer(200, "{\"model\":\"" + MODEL + "\",\"scores\":[0.1,0.2]}");
        assertThatThrownBy(() -> reranker().rerank(QUERY, CANDIDATES)).as("too few scores")
                .isInstanceOf(IllegalStateException.class);

        answer(200, "{\"model\":\"other/model\",\"scores\":[0.1,0.2,0.3]}");
        assertThatThrownBy(() -> reranker().rerank(QUERY, CANDIDATES)).as("another model")
                .isInstanceOf(IllegalStateException.class);

        answer(200, "{\"model\":\"" + MODEL + "\",\"scores\":[0.1,null,0.3]}");
        assertThatThrownBy(() -> reranker().rerank(QUERY, CANDIDATES)).as("a missing score")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void spansCarryNoQueryOrPassageText() {
        answer(200, "{\"model\":\"" + MODEL + "\",\"scores\":[0.1,0.2,0.3]}");
        List<String> recorded = new ArrayList<>();
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override
            public void onStop(Observation.Context context) {
                recorded.add(context.getName() + " " + context.getAllKeyValues());
            }

            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }
        });

        reranker(URI.create("http://localhost:" + service.getAddress().getPort()), registry).rerank(QUERY, CANDIDATES);

        assertThat(recorded).anySatisfy(r -> assertThat(r).startsWith("finsentinel.rerank")
                .contains("cross-encoder", MODEL, "finsentinel.candidates"));
        assertThat(recorded).anySatisfy(r -> assertThat(r).startsWith("http.client.requests"));
        assertThat(String.join("\n", recorded)).doesNotContain("cooling", "twelve hours", "suspicious");
    }

    private void answer(int status, String body) {
        answer(status, body, Duration.ZERO);
    }

    private void answer(int status, String body, Duration delay) {
        answer.set(new Answer(status, body, delay));
    }

    private CrossEncoderReranker reranker() {
        return reranker(URI.create("http://localhost:" + service.getAddress().getPort()), ObservationRegistry.NOOP);
    }

    private static CrossEncoderReranker reranker(URI url, ObservationRegistry observations) {
        KnowledgeProperties.CrossEncoder crossEncoder =
                new KnowledgeProperties.CrossEncoder(url, MODEL, Duration.ofSeconds(1), Duration.ofSeconds(1));
        KnowledgeProperties props = new KnowledgeProperties(Path.of("knowledge"), false, 5000, 2400, 300, 10, 100,
                new KnowledgeProperties.Search(20, 3, 60, 1.0, 3, 900,
                        new KnowledgeProperties.Rerank(true, KnowledgeProperties.Using.CROSS_ENCODER, "gpt-6-luna",
                                700, crossEncoder),
                        new KnowledgeProperties.Cache(false, Duration.ofDays(1))));
        return new CrossEncoderReranker(RestClient.builder().observationRegistry(observations), props, observations);
    }
}
