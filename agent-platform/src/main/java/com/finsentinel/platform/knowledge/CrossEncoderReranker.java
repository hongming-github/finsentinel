package com.finsentinel.platform.knowledge;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

/**
 * Scores each candidate against the query with a cross-encoder served by the reranker service (reranker/, ADR 0030).
 * No model API is called, so the ranking is free per call and the passages never leave the premises; the cost is a
 * service to run. Any failure (service down, timeout, 4xx/5xx, a malformed answer) is thrown: {@link KnowledgeSearch}
 * then keeps the fused order and does not cache it.
 */
@Component
class CrossEncoderReranker implements Reranker {

    private final RestClient client;
    private final KnowledgeProperties.CrossEncoder props;
    private final ObservationRegistry observations;

    CrossEncoderReranker(RestClient.Builder builder, KnowledgeProperties knowledge, ObservationRegistry observations) {
        this.props = knowledge.search().rerank().crossEncoder();
        this.observations = observations;
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(props.connectTimeout()).build());
        requests.setReadTimeout(props.timeout());
        // Boot's builder: its HTTP client span carries the method and URI template, never the body.
        this.client = builder.clone().baseUrl(props.url().toString()).requestFactory(requests).build();
    }

    @Override
    public List<String> rerank(String query, List<Candidate> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        // Ids, model and counts only; the query and the passages stay out of the span (ADR 0020).
        Observation observation = Observation.createNotStarted("finsentinel.rerank", observations)
                .lowCardinalityKeyValue("finsentinel.reranker", "cross-encoder")
                .lowCardinalityKeyValue("finsentinel.model", props.model())
                .highCardinalityKeyValue("finsentinel.candidates", String.valueOf(candidates.size()));
        return observation.observe(() -> rank(query, candidates));
    }

    private List<String> rank(String query, List<Candidate> candidates) {
        Scores answer = client.post().uri("/rerank").contentType(MediaType.APPLICATION_JSON)
                .body(new Request(props.model(), query, candidates.stream().map(Candidate::text).toList()))
                .retrieve()
                .body(Scores.class);
        if (answer == null || answer.scores() == null || answer.scores().size() != candidates.size()
                || answer.scores().stream().anyMatch(Objects::isNull) || !props.model().equals(answer.model())) {
            throw new IllegalStateException("reranker service answered with an unexpected body");
        }
        List<Double> scores = answer.scores();
        // Stable sort: equal scores keep the fused (RRF) order. Nothing is dropped; search cuts to top-k.
        return IntStream.range(0, candidates.size()).boxed()
                .sorted(Comparator.comparingDouble((Integer i) -> -scores.get(i)))
                .map(i -> candidates.get(i).id())
                .toList();
    }

    record Request(String model, String query, List<String> passages) {
    }

    record Scores(String model, List<Double> scores) {
    }
}
