package com.finsentinel.loadtest;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.FeederBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.listFeeder;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.header;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/**
 * Analysts working the queue through the case API (README "Case API"), as two banks' analysts with their own realm
 * tokens: the queue of cases awaiting a decision, one case's detail (alerts, analyses, audit trail; its ETag is the
 * case version), and for a share of visits a decision with {@code If-Match}. Two visits can pick the same case: the
 * second decision gets 409 (the case changed since its detail was read), which is the API working, not a failure.
 */
public final class AnalystApiSimulation extends Simulation {

    private static final LoadSettings SETTINGS = LoadSettings.load();
    private static final Tokens TOKENS = new Tokens(SETTINGS);

    private final double decideShare = SETTINGS.decimal("analyst.decide-share");

    private final HttpProtocolBuilder protocol = http.baseUrl(SETTINGS.string("platform.url"))
            .acceptHeader("application/json")
            .contentTypeHeader("application/json");

    /** Each user is an analyst of one bank: the path must name the token's bank (else 404). */
    private final FeederBuilder<Object> analysts = listFeeder(List.<Map<String, Object>>of(
            Map.of("user", "alice", "tenant", "straits"),
            Map.of("user", "bob", "tenant", "harbour"))).circular();

    private final ChainBuilder signIn = exec(session -> session.set("token", TOKENS.user(session.getString("user"))));

    private final ChainBuilder queue = exec(http("queue")
            .get("/api/tenants/#{tenant}/cases?stage=AWAITING_DECISION&limit=50")
            .header("Authorization", "Bearer #{token}")
            .check(status().is(200), jsonPath("$[*].caseId").findRandom().optional().saveAs("caseId")));

    private final ChainBuilder detail = exec(http("case detail")
            .get("/api/tenants/#{tenant}/cases/#{caseId}")
            .header("Authorization", "Bearer #{token}")
            .check(status().is(200), header("ETag").saveAs("etag")));

    private final ChainBuilder decide = exec(http("decision")
            .post("/api/tenants/#{tenant}/cases/#{caseId}/decision")
            .header("Authorization", "Bearer #{token}")
            .header("If-Match", "#{etag}")
            .body(StringBody("{\"decision\": \"FALSE_POSITIVE\", \"note\": \"load test\"}"))
            .check(status().in(200, 409)));

    private final ScenarioBuilder visit = scenario("analyst visit")
            .feed(analysts)
            .exec(signIn, queue)
            .doIf(session -> session.contains("caseId")).then(exec(detail)
                    .doIf(session -> ThreadLocalRandom.current().nextDouble() < decideShare).then(decide));

    {
        double rate = SETTINGS.decimal("analyst.users-per-second");
        setUp(visit.injectOpen(
                rampUsersPerSec(1).to(rate).during(SETTINGS.duration("analyst.ramp")),
                constantUsersPerSec(rate).during(SETTINGS.duration("analyst.hold"))))
                .protocols(protocol)
                .assertions(
                        global().responseTime().percentile(95.0)
                                .lt((int) SETTINGS.duration("analyst.max-p95").toMillis()),
                        global().failedRequests().percent().lt(SETTINGS.decimal("analyst.max-failed-percent")));
    }
}
