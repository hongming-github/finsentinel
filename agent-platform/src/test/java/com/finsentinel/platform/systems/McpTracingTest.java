package com.finsentinel.platform.systems;

import com.finsentinel.platform.PlatformTest;
import com.finsentinel.platform.TestTokens;
import com.finsentinel.platform.TestTracing;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.finsentinel.platform.TestData.uniqueTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * An MCP call carries the caller's trace to the server (ADR 0020). The server here is the platform's own MCP endpoint,
 * called over HTTP by the same client code that calls mcp-banking and mcp-compliance.
 */
@PlatformTest
class McpTracingTest {

    @LocalServerPort
    int port;
    @Autowired
    JsonMapper json;
    @Autowired
    Tracer tracer;
    @Autowired
    InMemorySpanExporter spans;
    @Autowired
    SdkTracerProvider tracerProvider;

    @Test
    void theServersSpanIsAChildOfTheClientsToolCallSpan() {
        String token = TestTokens.assistant(uniqueTenant());
        Span caller = tracer.spanBuilder("caller").startSpan();
        try (McpServerConnection connection = new McpServerConnection("platform",
                URI.create("http://localhost:" + port + "/mcp"), Duration.ofSeconds(5), Duration.ofSeconds(10), json,
                () -> token, tracer);
             Scope ignored = caller.makeCurrent()) {
            connection.call("listCases", Map.of(), JsonNode.class);
        } finally {
            caller.end();
        }
        String traceId = caller.getSpanContext().getTraceId();

        SpanData client = await().atMost(Duration.ofSeconds(10)).until(() -> inTrace(traceId).stream()
                .filter(s -> s.getName().equals("mcp tools/call listCases")).findFirst(), o -> o.isPresent()).get();
        assertThat(client.getKind()).isEqualTo(SpanKind.CLIENT);
        assertThat(client.getParentSpanId()).isEqualTo(caller.getSpanContext().getSpanId());
        assertThat(await().atMost(Duration.ofSeconds(10)).until(() -> inTrace(traceId).stream()
                        .filter(s -> s.getKind() == SpanKind.SERVER && s.getParentSpanId().equals(client.getSpanId()))
                        .toList(), list -> !list.isEmpty()))
                .as("the server continued the client's trace").isNotEmpty();
    }

    private List<SpanData> inTrace(String traceId) {
        return TestTracing.finished(tracerProvider, spans).stream().filter(s -> s.getTraceId().equals(traceId)).toList();
    }
}
