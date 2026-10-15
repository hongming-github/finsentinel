package com.finsentinel.mcp.banking;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** A real MCP client against this server, backed by the WireMock fake systems. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "finsentinel.banking.read-timeout=1s", classes = {BankingMcpApplication.class,
        BankingMcpServerTest.Spans.class})
@Testcontainers
class BankingMcpServerTest {

    @Container
    static final GenericContainer<?> systems = FakeSystems.container();

    @DynamicPropertySource
    static void systemUrls(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", TestAuth::issuer);
        registry.add("finsentinel.banking.crm-url", () -> FakeSystems.url(systems, "/crm"));
        registry.add("finsentinel.banking.core-url", () -> FakeSystems.url(systems, "/core"));
    }

    @LocalServerPort
    int port;
    @Autowired
    InMemorySpanExporter spans;
    @Autowired
    SdkTracerProvider tracerProvider;

    @TestConfiguration(proxyBeanMethods = false)
    static class Spans {
        @Bean
        InMemorySpanExporter spans() {
            return InMemorySpanExporter.create();
        }
    }

    private final JsonMapper json = JsonMapper.builder().build();
    private McpSyncClient client;

    @BeforeEach
    void connect() {
        String token = TestAuth.token("mcp-banking", SecurityConfig.ROLE);
        client = McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                        .httpRequestCustomizer((request, method, endpoint, body, context) ->
                                request.header("Authorization", "Bearer " + token))
                        .build())
                .requestTimeout(Duration.ofSeconds(10))
                .build();
        client.initialize();
    }

    @AfterEach
    void close() {
        client.closeGracefully();
    }

    @Test
    void callsToTheBanksSystemsAreTracedByUriTemplateWithoutCustomerIdentifiers() {
        call("getAccountDetails", Map.of("tenantId", "straits", "accountId", "straits-acc-0007"));
        tracerProvider.forceFlush().join(10, TimeUnit.SECONDS);

        List<SpanData> outbound = spans.getFinishedSpanItems().stream()
                .filter(s -> s.getKind() == SpanKind.CLIENT).toList();
        assertThat(outbound).isNotEmpty();
        assertThat(outbound).allSatisfy(span -> assertThat(span.getAttributes().asMap().values())
                .noneMatch(value -> value.toString().contains("straits-acc-0007")));
        assertThat(outbound).anySatisfy(span -> assertThat(span.getAttributes().asMap().values())
                .anyMatch(value -> value.toString().equals("/core/{tenant}/accounts/{account}")));
    }

    @Test
    void offersTheTwoLookups() {
        assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name)
                .containsExactlyInAnyOrder("getCustomerProfile", "getAccountDetails");
    }

    @Test
    void customerProfileCarriesTravelNoticesAndStaffNotes() {
        JsonNode profile = call("getCustomerProfile", Map.of("tenantId", "straits", "customerId", "straits-cus-0483"));

        assertThat(profile.get("customerId").asString()).isEqualTo("straits-cus-0483");
        assertThat(profile.get("travelNotices").get(0).get("country").asString()).isEqualTo("JP");
        assertThat(profile.get("notes").get(0).get("author").asString()).isEqualTo("contact-centre");
    }

    @Test
    void accountDetailsCarrySecurityEvents() {
        JsonNode account = call("getAccountDetails", Map.of("tenantId", "straits", "accountId", "straits-acc-0487"));

        assertThat(account.get("recentSecurityEvents").valueStream().map(e -> e.get("type").asString()))
                .containsExactly("PHONE_NUMBER_CHANGED", "NEW_DEVICE_LOGIN", "TRANSFER_LIMIT_RAISED");
    }

    @Test
    void anIdCannotRedirectTheRequestToAnotherSystem() {
        McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder("getCustomerProfile").arguments(Map.of("tenantId", "straits", "customerId", "../../../core/straits/accounts/straits-acc-0487")).build());

        assertThat(text(result)).doesNotContain("recentSecurityEvents");
    }

    @Test
    void failingBackendIsAToolError() throws Exception {
        FakeSystems.stub(systems, """
                {"priority": 1, "request": {"method": "GET", "urlPath": "/crm/straits/customers/crm-down"},
                 "response": {"status": 503}}""");

        McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder("getCustomerProfile").arguments(Map.of("tenantId", "straits", "customerId", "crm-down")).build());

        assertThat(result.isError()).isTrue();
    }

    @Test
    void slowBackendIsCutOffByTheReadTimeout() throws Exception {
        FakeSystems.stub(systems, """
                {"priority": 1, "request": {"method": "GET", "urlPath": "/core/straits/accounts/core-slow"},
                 "response": {"status": 200, "fixedDelayMilliseconds": 5000, "jsonBody": {}}}""");
        long start = System.nanoTime();

        McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder("getAccountDetails").arguments(Map.of("tenantId", "straits", "accountId", "core-slow")).build());

        assertThat(result.isError()).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void onlyTheServiceAccountForThisServerGetsIn() throws Exception {
        assertThat(post(null)).as("no token").isEqualTo(401);
        assertThat(post(TestAuth.token("finsentinel-platform", SecurityConfig.ROLE))).as("another audience").isEqualTo(401);
        assertThat(post(TestAuth.token("mcp-banking", "analyst"))).as("without the role").isEqualTo(403);
        assertThat(post(TestAuth.token("mcp-banking", SecurityConfig.ROLE))).as("the platform").isEqualTo(200);
    }

    private int post(String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"jsonrpc":"2.0","id":1,"method":"tools/list"}"""));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }

    private JsonNode call(String tool, Map<String, Object> arguments) {
        McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder(tool).arguments(arguments).build());
        assertThat(result.isError()).as(text(result)).isNotEqualTo(true);
        return json.readTree(text(result));
    }

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().getFirst()).text();
    }
}
