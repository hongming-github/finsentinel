package com.finsentinel.platform.eval;

import com.finsentinel.fakellm.FakeLlm;
import com.finsentinel.platform.FakeExternalSystems;
import com.finsentinel.platform.PlatformTest;
import com.finsentinel.platform.analysis.AnalysisMode;
import com.finsentinel.platform.analysis.CaseAnalysisGraph;
import com.finsentinel.platform.analysis.RealAgentGraph;
import com.finsentinel.platform.cases.CaseIntake;
import com.finsentinel.platform.cases.CaseRepository;
import com.finsentinel.platform.llm.LlmGateway;
import com.finsentinel.platform.llm.ModelRef;
import com.finsentinel.platform.transaction.TransactionRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real agents, gateway and Spring AI OpenAI client against fake-llm (ADR 0024): proves the stand-in that load tests
 * and chaos drills run on speaks the protocol the platform uses (tool calls, structured output, review, the fallback's
 * two-step path), and that a whole case gets through validation on it. Free, so it runs in every build.
 */
@PlatformTest
@TestPropertySource(properties = "finsentinel.test.fake-ai=false")
class FakeLlmContractTest {

    private static final FakeLlm FAKE = start();

    @DynamicPropertySource
    static void pointAtTheFake(DynamicPropertyRegistry registry) {
        String base = "http://localhost:" + FAKE.port();
        registry.add("spring.ai.openai.base-url", () -> base + "/v1");
        registry.add("spring.ai.openai.api-key", () -> "fake-llm");
        registry.add("finsentinel.llm.providers.groq.base-url", () -> base + "/groq/v1");
        registry.add("finsentinel.llm.providers.groq.api-key", () -> "fake-llm");
        for (String model : List.of("[fake-llm]", "[groq:fake-llm]")) {
            for (String rate : List.of("input", "cached-input", "output")) {
                registry.add("finsentinel.llm.prices." + model + "." + rate, () -> "0");
            }
        }
    }

    @AfterAll
    static void stop() {
        FAKE.close();
    }

    @Autowired
    ApplicationContext context;
    @Autowired
    CaseRepository cases;
    @Autowired
    CaseIntake intake;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    TransactionRepository transactions;
    @Autowired
    FakeExternalSystems systems;
    @Autowired
    LlmGateway gateway;
    @Autowired
    JsonMapper json;

    @Test
    void aCaseGoesThroughTheRealAgentsAndValidationOnTheFake() {
        GoldenDataset dataset = GoldenDataset.load(json);
        EvalHarness harness = new EvalHarness(new ScenarioBuilder(transactions, systems), new CaseGrader(dataset, json),
                intake, cases, jdbc, systems, null);
        CaseAnalysisGraph graph = RealAgentGraph.of(context);

        RunRecord run = harness.run(dataset.select("geo-impossible-travel").getFirst(), graph, AnalysisMode.SINGLE, 1);

        assertThat(run.analysisFailed()).as(String.valueOf(run.failures())).isFalse();
        assertThat(run.disposition()).isEqualTo("NEEDS_REVIEW");
        assertThat(run.riskScore()).isEqualTo(50);
        assertThat(run.inputTokens()).isPositive();
        assertThat(run.costUsd()).isZero();
    }

    /** What the slow-LLM drill relies on: the main provider failing, the fallback's two-step path answering. */
    @Test
    void withTheMainProviderFailingTheFallbackAnswers() throws Exception {
        behave("openai", "{\"errorStatus\":503,\"errorRate\":1.0}");
        try {
            LlmGateway.Answer<Verdict> answer = gateway.ask(ModelRef.parse("gpt-6-luna"), "You review reports.",
                    "Review this case report.", List.of(), Verdict.class);

            assertThat(answer.answeredBy()).isEqualTo(ModelRef.parse("groq:openai/gpt-oss-120b"));
            assertThat(answer.value().approved()).isTrue();
        } finally {
            behave(null, null);
        }
    }

    record Verdict(boolean approved, List<String> issues) {
    }

    private static void behave(String provider, String behaviour) throws IOException, InterruptedException {
        String uri = "http://localhost:" + FAKE.port() + "/admin/behaviour"
                + (provider == null ? "" : "?provider=" + provider);
        HttpRequest request = HttpRequest.newBuilder(URI.create(uri))
                .method(behaviour == null ? "DELETE" : "POST", behaviour == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(behaviour))
                .build();
        try (HttpClient http = HttpClient.newHttpClient()) {
            assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        }
    }

    private static FakeLlm start() {
        try {
            return new FakeLlm(0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
