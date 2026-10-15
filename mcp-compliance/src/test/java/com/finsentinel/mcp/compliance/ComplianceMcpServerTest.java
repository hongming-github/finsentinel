package com.finsentinel.mcp.compliance;

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
        classes = {ComplianceMcpApplication.class, ComplianceMcpServerTest.Spans.class})
@Testcontainers
class ComplianceMcpServerTest {

    @Container
    static final GenericContainer<?> systems = FakeSystems.container();

    @DynamicPropertySource
    static void systemUrls(DynamicPropertyRegistry registry) {
        registry.add("finsentinel.compliance.screening-url", () -> FakeSystems.url(systems, "/screening"));
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", TestAuth::issuer);
    }

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

    /** A payee's name can be a person's name: the screening call is traced by URI template only (ADR 0020). */
    @org.junit.jupiter.api.Test
    void screeningCallsAreTracedWithoutTheCounterparty() {
        screen("rapid-cash-trading");
        tracerProvider.forceFlush().join(10, TimeUnit.SECONDS);

        List<SpanData> outbound = spans.getFinishedSpanItems().stream()
                .filter(s -> s.getKind() == SpanKind.CLIENT).toList();
        assertThat(outbound).isNotEmpty();
        assertThat(outbound).allSatisfy(span -> assertThat(span.getAttributes().asMap().values())
                .noneMatch(value -> value.toString().contains("rapid-cash-trading")));
    }

    @LocalServerPort
    int port;

    private final JsonMapper json = JsonMapper.builder().build();
    private McpSyncClient client;

    @BeforeEach
    void connect() {
        String token = TestAuth.token("mcp-compliance", SecurityConfig.ROLE);
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
    void offersCounterpartyScreening() {
        assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name).containsExactly("screenCounterparty");
    }

    @Test
    void reportedMuleAccountIsAHit() {
        JsonNode result = screen("payee-new-12345");

        assertThat(result.get("hits").get(0).get("list").asString()).isEqualTo("MULE_ACCOUNTS");
    }

    @Test
    void unlistedCounterpartyHasNoHits() {
        assertThat(screen("payee-new-12341").get("hits").isEmpty()).isTrue();
    }

    @Test
    void failingVendorIsAToolError() throws Exception {
        FakeSystems.stub(systems, """
                {"priority": 1, "request": {"method": "GET", "urlPath": "/screening/straits/counterparties/vendor-down"},
                 "response": {"status": 500}}""");

        McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder("screenCounterparty").arguments(Map.of("tenantId", "straits", "counterpartyId", "vendor-down")).build());

        assertThat(result.isError()).isTrue();
    }

    @Test
    void onlyTheServiceAccountForThisServerGetsIn() throws Exception {
        assertThat(post(null)).as("no token").isEqualTo(401);
        assertThat(post(TestAuth.token("finsentinel-platform", SecurityConfig.ROLE))).as("another audience").isEqualTo(401);
        assertThat(post(TestAuth.token("mcp-compliance", "analyst"))).as("without the role").isEqualTo(403);
        assertThat(post(TestAuth.token("mcp-compliance", SecurityConfig.ROLE))).as("the platform").isEqualTo(200);
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

    private JsonNode screen(String counterpartyId) {
        McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder("screenCounterparty").arguments(Map.of("tenantId", "straits", "counterpartyId", counterpartyId)).build());
        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        return json.readTree(((McpSchema.TextContent) result.content().getFirst()).text());
    }
}
