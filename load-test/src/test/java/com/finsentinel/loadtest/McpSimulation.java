package com.finsentinel.loadtest;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import io.gatling.javaapi.http.HttpRequestActionBuilder;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.regex;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/**
 * The platform's MCP endpoint (streamable HTTP, stateless: every request stands alone, no initialize needed) under two
 * callers with the analyst assistant's client-credentials token:
 * <ul>
 *   <li>an assistant reading cases: {@code listCases}, then {@code getCase} on one of them;</li>
 *   <li>a searcher calling {@code searchRegulations} faster than its hourly budget allows: answered searches never
 *       exceed the budget (counted in Postgres, ADR 0013), the rest come back as tool errors. Each question is new, so
 *       the exact-question cache does not answer it.</li>
 * </ul>
 */
public final class McpSimulation extends Simulation {

    private static final LoadSettings SETTINGS = LoadSettings.load();
    private static final Tokens TOKENS = new Tokens(SETTINGS);

    private final HttpProtocolBuilder protocol = http.baseUrl(SETTINGS.string("platform.url"))
            .contentTypeHeader("application/json")
            .acceptHeader("application/json, text/event-stream");

    private final ChainBuilder signIn = exec(session -> session.set("token",
            TOKENS.client("analyst-assistant", "analyst-assistant-dev-secret")));

    private static HttpRequestActionBuilder tool(String name, String arguments) {
        return http(name).post("/mcp")
                .header("Authorization", "Bearer #{token}")
                .body(StringBody("{\"jsonrpc\": \"2.0\", \"id\": 1, \"method\": \"tools/call\", \"params\": "
                        + "{\"name\": \"" + name + "\", \"arguments\": " + arguments + "}}"))
                .check(status().is(200), jsonPath("$.result.isError").ofBoolean().is(false));
    }

    private final ScenarioBuilder assistant = scenario("assistant")
            .exec(signIn)
            // The tool result is JSON inside the text content, so its quotes arrive escaped.
            .exec(tool("listCases", "{\"stage\": \"AWAITING_DECISION\", \"limit\": 20}")
                    .check(regex("caseId\\\\\":\\\\\"([0-9a-f-]{36})").findRandom().optional().saveAs("caseId")))
            .doIf(session -> session.contains("caseId"))
            .then(exec(tool("getCase", "{\"caseId\": \"#{caseId}\"}")));

    private final ScenarioBuilder searcher = scenario("searcher")
            .exec(signIn)
            .exec(session -> session.set("question", "load test question " + session.userId()))
            .exec(tool("searchRegulations", "{\"question\": \"#{question}\"}"));

    {
        double rate = SETTINGS.decimal("mcp.users-per-second");
        int budget = SETTINGS.integer("mcp.search-budget");
        setUp(
                assistant.injectOpen(
                        rampUsersPerSec(1).to(rate).during(SETTINGS.duration("mcp.ramp")),
                        constantUsersPerSec(rate).during(SETTINGS.duration("mcp.hold"))),
                searcher.injectOpen(
                        constantUsersPerSec(SETTINGS.decimal("mcp.searches-per-second"))
                                .during(SETTINGS.duration("mcp.search-duration"))))
                .protocols(protocol)
                .assertions(
                        details("listCases").responseTime().percentile(95.0)
                                .lt((int) SETTINGS.duration("mcp.max-p95").toMillis()),
                        details("getCase").responseTime().percentile(95.0)
                                .lt((int) SETTINGS.duration("mcp.max-p95").toMillis()),
                        details("listCases").failedRequests().percent()
                                .lt(SETTINGS.decimal("mcp.max-failed-percent")),
                        details("getCase").failedRequests().percent()
                                .lt(SETTINGS.decimal("mcp.max-failed-percent")),
                        // The budget holds: never more answers than it allows within the hour, whatever else used it.
                        details("searchRegulations").successfulRequests().count().lte((long) budget));
    }
}
