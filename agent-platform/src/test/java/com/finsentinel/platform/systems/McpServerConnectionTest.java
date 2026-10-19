package com.finsentinel.platform.systems;

import com.finsentinel.platform.resilience.CircuitBreaker;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpServerConnectionTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void textContentIsReadAsTheExpectedRecord() {
        ScreeningResult result = McpServerConnection.parse("compliance", "screenCounterparty",
                result("{\"counterpartyId\":\"p-1\",\"hits\":[{\"list\":\"MULE_ACCOUNTS\",\"reason\":\"r\","
                        + "\"listedOn\":\"2026-09-12\"}],\"extra\":\"ignored\"}", false), ScreeningResult.class, json);

        assertThat(result.hits()).extracting(ScreeningResult.Hit::list).containsExactly("MULE_ACCOUNTS");
    }

    @Test
    void noAccessTokenMeansTheSystemIsUnavailableNotAFailedAnalysis() {
        McpServerConnection connection = new McpServerConnection("banking", URI.create("http://localhost:1"),
                Duration.ofSeconds(1), Duration.ofSeconds(1), json, () -> {
                    throw new IllegalStateException("identity provider down");
                }, TRACER, new CircuitBreaker(3, Duration.ofSeconds(30), Clock.systemUTC()));

        assertThatThrownBy(() -> connection.call("getCustomerProfile", Map.of(), CustomerProfile.class))
                .isInstanceOf(SystemUnavailableException.class)
                .hasMessageContaining("banking unavailable");
    }

    /**
     * Drill 05 (ADR 0026): with CRM and core banking 10 s slow, every call waited out mcp-banking's 5 s timeout. After
     * three failures in a row the system's calls fail at once, without reaching it, until one trial call after the
     * open period.
     */
    @Test
    void aSystemThatKeepsFailingIsSkippedUntilATrialCallAfterTheOpenPeriod() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer slow = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        slow.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        slow.createContext("/", exchange -> {
            // Calls only: closing a failed connection also sends a request, which is not a call.
            if (exchange.getRequestMethod().equals("POST")) {
                requests.incrementAndGet();
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        slow.start();
        MutableClock clock = new MutableClock();
        McpServerConnection connection = new McpServerConnection("banking",
                URI.create("http://localhost:" + slow.getAddress().getPort() + "/mcp"), Duration.ofSeconds(1),
                Duration.ofSeconds(2), json, () -> "token", TRACER, new CircuitBreaker(3, Duration.ofSeconds(30), clock));
        try {
            for (int i = 0; i < 3; i++) {
                assertThatThrownBy(() -> connection.call("getCustomerProfile", Map.of(), CustomerProfile.class))
                        .isInstanceOf(SystemUnavailableException.class);
            }
            int reached = requests.get();
            assertThat(reached).isPositive();

            long started = System.nanoTime();
            for (int i = 0; i < 10; i++) {
                assertThatThrownBy(() -> connection.call("getCustomerProfile", Map.of(), CustomerProfile.class))
                        .isInstanceOf(SystemUnavailableException.class)
                        .hasMessageContaining("circuit open");
            }
            assertThat(Duration.ofNanos(System.nanoTime() - started)).as("ten calls, none waited on the system")
                    .isLessThan(Duration.ofMillis(300));
            assertThat(requests.get()).as("nothing reached the system").isEqualTo(reached);

            clock.advance(Duration.ofSeconds(31));
            assertThatThrownBy(() -> connection.call("getCustomerProfile", Map.of(), CustomerProfile.class))
                    .isInstanceOf(SystemUnavailableException.class)
                    .hasMessageNotContaining("circuit open");
            assertThat(requests.get()).as("the trial call reached it").isGreaterThan(reached);
        } finally {
            connection.close();
            slow.stop(0);
        }
    }

    @Test
    void toolErrorBecomesSystemUnavailable() {
        assertThatThrownBy(() -> McpServerConnection.parse("banking", "getCustomerProfile",
                result("503 Service Unavailable", true), CustomerProfile.class, json))
                .isInstanceOf(SystemUnavailableException.class)
                .hasMessage("banking unavailable: getCustomerProfile failed: 503 Service Unavailable");
    }

    @Test
    void unreadableAnswerBecomesSystemUnavailable() {
        assertThatThrownBy(() -> McpServerConnection.parse("banking", "getCustomerProfile",
                result("<html>gateway</html>", false), CustomerProfile.class, json))
                .isInstanceOf(SystemUnavailableException.class)
                .hasMessageContaining("unreadable content");
    }

    private static final io.opentelemetry.api.trace.Tracer TRACER =
            io.opentelemetry.api.OpenTelemetry.noop().getTracer("test");

    static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-10-19T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private static McpSchema.CallToolResult result(String text, boolean error) {
        return McpSchema.CallToolResult.builder().content(List.of(McpSchema.TextContent.builder(text).build())).isError(error).build();
    }
}
