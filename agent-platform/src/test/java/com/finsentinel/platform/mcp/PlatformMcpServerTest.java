package com.finsentinel.platform.mcp;

import com.finsentinel.common.AlertRule;
import com.finsentinel.common.Severity;
import com.finsentinel.platform.PlatformTest;
import com.finsentinel.platform.cases.CaseIntake;
import com.finsentinel.platform.cases.CaseWorker;
import com.finsentinel.platform.knowledge.TestKnowledgeBase;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.finsentinel.platform.TestData.alert;
import static com.finsentinel.platform.TestData.uniqueTenant;
import static org.assertj.core.api.Assertions.assertThat;

/** The platform's MCP endpoint, called by a real MCP client over HTTP. */
@PlatformTest
class PlatformMcpServerTest {

    @LocalServerPort
    int port;
    @Autowired
    CaseIntake intake;
    @Autowired
    CaseWorker worker;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    JsonMapper json;
    @Autowired
    McpServerProperties props;
    @Autowired
    ApplicationContext context;

    @TempDir
    Path dir;

    private final String tenant = uniqueTenant();
    private McpSyncClient client;

    @BeforeEach
    void connect() {
        client = McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + port).build())
                .requestTimeout(Duration.ofSeconds(10))
                .build();
        client.initialize();
    }

    @AfterEach
    void close() {
        client.closeGracefully();
        jdbc.sql("delete from rate_limit_window where key = :key").param("key", PlatformMcpTools.SEARCH_LIMIT_KEY).update();
    }

    @Test
    void offersOnlyTheReadOnlyPlatformTools() {
        assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name).containsExactlyInAnyOrder(
                "listCases", "getCase", "searchRegulations", "readRegulationPassages");
    }

    @Test
    void caseWithItsReportAndReviewerVerdict() {
        String caseId = analysedCase();

        JsonNode view = call("getCase", Map.of("tenantId", tenant, "caseId", caseId));

        assertThat(view.get("caseSummary").get("stage").asString()).isEqualTo("AWAITING_DECISION");
        assertThat(view.get("alerts").get(0).get("rule").asString()).isEqualTo("GEO_JUMP");
        assertThat(view.get("latestAnalysis").get("summary").asString()).isEqualTo("stub report");
        assertThat(view.get("latestAnalysis").get("review").get("approved").asBoolean()).isTrue();
        assertThat(view.get("latestAnalysis").has("toolCalls")).as("compact view").isFalse();
    }

    @Test
    void anotherTenantsCaseIsNotFound() {
        String caseId = analysedCase();

        McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest("getCase",
                Map.of("tenantId", uniqueTenant(), "caseId", caseId)));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("no case").doesNotContain("GEO_JUMP");
    }

    @Test
    void listsOnlyTheTenantsCasesInTheAskedStage() {
        String caseId = analysedCase();

        JsonNode awaiting = call("listCases", Map.of("tenantId", tenant, "stage", "AWAITING_DECISION"));
        JsonNode inAnalysis = call("listCases", Map.of("tenantId", tenant, "stage", "ANALYSIS"));

        assertThat(awaiting.valueStream().map(c -> c.get("caseId").asString())).containsExactly(caseId);
        assertThat(awaiting.get(0).get("latestDisposition").asString()).isEqualTo("NEEDS_REVIEW");
        assertThat(awaiting.get(0).get("latestRiskScore").asInt()).isEqualTo(50);
        assertThat(inAnalysis.isEmpty()).isTrue();
    }

    @Test
    void regulationSearchAndCitedPassages() throws Exception {
        TestKnowledgeBase.indexPaymentsDoc(context, dir);

        JsonNode hits = call("searchRegulations", Map.of("question", "cooling off period for a new payee"));
        JsonNode passages = call("readRegulationPassages", Map.of("chunkIds", List.of("pay:3.1", "unknown")));

        assertThat(hits.valueStream().map(h -> h.get("chunkId").asString())).contains("pay:2.1");
        assertThat(passages.valueStream().map(p -> p.get("chunkId").asString())).containsExactly("pay:3.1");
        assertThat(passages.get(0).get("text").asString()).contains("suspicious transaction report");
    }

    @Test
    void regulationSearchStopsWhenTheBudgetIsUsedUp() {
        jdbc.sql("""
                        insert into rate_limit_window (key, window_start, used)
                        values (:key, date_bin(interval '3600 seconds', now(), timestamptz '2000-01-01'), :used)
                        on conflict (key, window_start) do update set used = excluded.used
                        """)
                .param("key", PlatformMcpTools.SEARCH_LIMIT_KEY)
                .param("used", props.maxRegulationSearchesPerHour())
                .update();

        McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest("searchRegulations",
                Map.of("question", "cooling off period")));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("budget");
    }

    /** A case the (stubbed) agents have analysed: AWAITING_DECISION with a report. */
    private String analysedCase() {
        jdbc.sql("update fraud_case set stage = 'CLOSED', claimed_at = null where stage = 'ANALYSIS'").update();
        String caseId = intake.accept(alert(tenant, "acc-" + UUID.randomUUID(), AlertRule.GEO_JUMP, Severity.HIGH,
                List.of("t-1"), Instant.now())).orElseThrow();
        worker.runOnce();
        return caseId;
    }

    private JsonNode call(String tool, Map<String, Object> arguments) {
        McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(tool, arguments));
        assertThat(result.isError()).as(text(result)).isNotEqualTo(Boolean.TRUE);
        return json.readTree(text(result));
    }

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().getFirst()).text();
    }
}
