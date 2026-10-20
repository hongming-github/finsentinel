package com.finsentinel.platform.eval;

import com.finsentinel.fakellm.FakeLlm;
import com.finsentinel.common.AlertRule;
import com.finsentinel.common.Channel;
import com.finsentinel.common.Severity;
import com.finsentinel.common.Transaction;
import com.finsentinel.platform.FakeExternalSystems;
import com.finsentinel.platform.StubAssistantModel;
import com.finsentinel.platform.TestData;
import com.finsentinel.platform.TestTokens;
import com.finsentinel.platform.assistant.AssistantModel;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;

import static com.finsentinel.platform.TestTokens.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
    @Autowired
    MockMvc mvc;
    @Autowired
    StubAssistantModel assistantModel;

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

    /**
     * The case assistant (ADR 0028) on the fake: its real prompts and the conversation call (several messages, a schema)
     * through the real gateway and client, two turns, the second a regulatory follow-up that searches.
     */
    @Test
    void aConversationGoesThroughTheRealAssistantOnTheFake() throws Exception {
        AssistantModel real = context.getBeansOfType(AssistantModel.class).values().stream()
                .filter(m -> !(m instanceof StubAssistantModel)).findFirst().orElseThrow();
        assistantModel.delegateTo(real);
        try {
            String tenant = TestData.uniqueTenant();
            Instant now = Instant.now();
            Transaction transfer = TestData.txn(tenant, "acc-1", "4800.00", Channel.TRANSFER, "SG", "payee-1", now);
            transactions.insertAll(List.of(transfer));
            String caseId = intake.accept(TestData.alert(tenant, "acc-1", AlertRule.LARGE_AMOUNT, Severity.HIGH,
                    List.of(transfer.txnId()), now)).orElseThrow();
            String analyst = TestTokens.analyst(tenant);
            String conversation = json.readTree(mvc.perform(post("/api/tenants/{t}/cases/{c}/conversations", tenant,
                            caseId).with(bearer(analyst))).andExpect(status().isCreated()).andReturn().getResponse()
                    .getContentAsString()).path("conversationId").asString();

            ask(tenant, caseId, conversation, analyst, 0, "Why is this case open?");
            JsonNode view = ask(tenant, caseId, conversation, analyst, 1, "And what does the EUPG say about it?");

            JsonNode second = view.path("turns").get(1);
            assertThat(second.path("answer").asString()).startsWith("Synthetic answer by fake-llm");
            assertThat(second.path("standaloneQuestion").asString()).isEqualTo("And what does the EUPG say about it?");
            assertThat(second.path("searchQuery").isString()).as("a regulatory question searches").isTrue();
            assertThat(second.path("model").asString()).isEqualTo("fake-llm");
            assertThat(second.path("costUsd").decimalValue()).isZero();
            assertThat(view.path("turns").get(0).path("searchQuery").isNull()).isTrue();
        } finally {
            assistantModel.reset();
        }
    }

    private JsonNode ask(String tenant, String caseId, String conversation, String token, int seen, String question)
            throws Exception {
        return json.readTree(mvc.perform(post("/api/tenants/{t}/cases/{c}/conversations/{id}/turns", tenant, caseId,
                        conversation).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("question", question)))
                        .header("If-Match", "\"" + seen + "\"").with(bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
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
