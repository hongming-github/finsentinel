package com.finsentinel.platform.cases;

import com.finsentinel.common.Alert;
import com.finsentinel.platform.alert.StoredAlert;
import com.finsentinel.platform.investigation.CaseContext;
import com.finsentinel.platform.investigation.InvestigationResult;
import com.finsentinel.platform.security.Tenancy;
import com.finsentinel.platform.systems.SystemsProperties;
import com.finsentinel.common.Severity;
import com.finsentinel.platform.PlatformTest;
import com.finsentinel.platform.StubCaseInvestigator;
import com.finsentinel.platform.alert.AlertRepository;
import com.finsentinel.platform.analysis.CaseAnalysisGraph;
import com.finsentinel.platform.investigation.InvestigationProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.finsentinel.platform.TestData.uniqueTenant;
import static com.finsentinel.platform.cases.CaseTestSupport.alertOn;
import static com.finsentinel.platform.cases.CaseTestSupport.eventTypes;
import static com.finsentinel.platform.cases.CaseTestSupport.makeDue;
import static com.finsentinel.platform.cases.CaseTestSupport.outboxTypes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@PlatformTest
class CaseWorkerTest {

    @Autowired
    CaseWorker worker;
    @Autowired
    PipelineMetrics metrics;
    @Autowired
    CaseRepository cases;
    @Autowired
    SystemsProperties systems;
    @Autowired
    CaseIntake intake;
    @Autowired
    AlertRepository alerts;
    @Autowired
    StubCaseInvestigator investigator;
    @Autowired
    CaseAnalysisGraph analysis;
    @Autowired
    JdbcClient jdbc;

    private static final Duration CLAIM_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration LEASE = Duration.ofSeconds(30);

    private final String tenant = uniqueTenant();

    @BeforeEach
    void ownQueue() {
        CaseTestSupport.parkOtherCases(jdbc);
    }

    @AfterEach
    void resetStub() {
        investigator.reset();
    }

    /** Phase 9 measures alert-to-report per stage and the backlog from these meters (ADR 0024). */
    @Test
    void anAnalysedAlertIsTimedPerStageAndTheBacklogIsCounted() {
        double before = metrics.registry().get("finsentinel.pipeline").tag("stage", "total").timer().count();
        newCase(Severity.HIGH);
        assertThat(metrics.registry().get("finsentinel.cases.waiting").gauge().value()).isGreaterThanOrEqualTo(1);

        worker.runOnce();

        assertThat(metrics.registry().get("finsentinel.pipeline").tag("stage", "total").timer().count())
                .isGreaterThan((long) before);
    }

    /** Cached and written prompt tokens are how we see whether prompts reuse the provider's cache (ADR 0023). */
    @Test
    void promptCacheTokensAreStoredWithTheAnalysis() {
        String caseId = newCase(Severity.HIGH);
        investigator.respondWith(c -> {
            InvestigationResult r = StubCaseInvestigator.defaultResult(c);
            return new InvestigationResult(r.report(), r.toolCalls(), r.repairedIssues(), r.model(), 1_000, 50, 600,
                    300, r.llmCalls(), r.latencyMs(), List.of(), null, null, List.of());
        });

        worker.runOnce();

        assertThat(cases.analyses(tenant, caseId)).singleElement().satisfies(a -> {
            assertThat(a.cachedInputTokens()).isEqualTo(600);
            assertThat(a.cacheWriteTokens()).isEqualTo(300);
        });
    }

    @Test
    void plantedInstructionsTheToolsFlaggedAreRecordedOnTheCase() {
        String caseId = newCase(Severity.HIGH);
        List<String> flagged = List.of("getCustomerProfile: addresses automated systems");
        investigator.respondWith(c -> {
            InvestigationResult r = StubCaseInvestigator.defaultResult(c);
            return new InvestigationResult(r.report(), r.toolCalls(), r.repairedIssues(), r.model(), r.inputTokens(),
                    r.outputTokens(), r.cachedInputTokens(), r.llmCalls(), r.latencyMs(), List.of(), null, null, flagged);
        });

        worker.runOnce();

        assertThat(eventTypes(cases, tenant, caseId)).containsExactly("OPENED", "UNTRUSTED_INSTRUCTIONS", "ANALYSED");
        assertThat(cases.events(tenant, caseId).get(1).detail().toString()).contains("addresses automated systems");
    }

    @Test
    void analysedCaseWaitsForTheAnalystWithItsAnalysis() {
        String caseId = newCase(Severity.HIGH);

        worker.runOnce();

        FraudCase c = cases.find(tenant, caseId).orElseThrow();
        assertThat(c.stage()).isEqualTo(CaseStage.AWAITING_DECISION);
        assertThat(cases.analyses(tenant, caseId)).singleElement().satisfies(a -> {
            assertThat(a.run()).isEqualTo(1);
            assertThat(a.model()).isEqualTo("stub-model");
        });
        assertThat(eventTypes(cases, tenant, caseId)).containsExactly("OPENED", "ANALYSED");
        assertThat(outboxTypes(jdbc, caseId)).containsExactly("case.ready_for_decision");
    }

    @Test
    void failedAnalysisIsRetriedThenTheCaseFails() {
        String caseId = newCase(Severity.MEDIUM);
        investigator.respondWith(c -> {
            throw new IllegalStateException("LLM down");
        });

        worker.runOnce();
        assertThat(cases.find(tenant, caseId).orElseThrow().stage()).isEqualTo(CaseStage.ANALYSIS);
        makeDue(jdbc, caseId);
        worker.runOnce();
        makeDue(jdbc, caseId);
        worker.runOnce();

        assertThat(cases.find(tenant, caseId).orElseThrow().stage()).isEqualTo(CaseStage.FAILED);
        assertThat(eventTypes(cases, tenant, caseId)).endsWith("ANALYSIS_ATTEMPT_FAILED", "ANALYSIS_ATTEMPT_FAILED", "FAILED");
        assertThat(outboxTypes(jdbc, caseId)).containsExactly("case.failed");
        assertThat(worker.runOnce()).isZero();
    }

    @Test
    void highPriorityIsClaimedFirst() {
        String medium = newCase(Severity.MEDIUM);
        String high = newCase(Severity.HIGH);

        List<CaseClaim> claims = cases.claim(1, Duration.ofMinutes(5), 3);

        assertThat(claims).singleElement().extracting(c -> c.fraudCase().caseId()).isEqualTo(high);
        assertThat(cases.find(tenant, medium).orElseThrow().attempts()).isZero();
    }

    /**
     * The graph stops at its next checkpoint write, which is fenced by the claim, and the worker drops the run without
     * recording a failure: the case is the other worker's now. Fails if the worker treats a lost claim as a failed
     * attempt (an ANALYSIS_ATTEMPT_FAILED event, the other worker's claim released).
     */
    @Test
    void workerThatLostItsClaimDoesNotWriteAResult() {
        String caseId = newCase(Severity.HIGH);
        AtomicReference<Instant> otherWorkersClaim = new AtomicReference<>();
        investigator.respondWith(c -> {
            // Another worker takes the case over while this one is still thinking. Claims are queue work, in the
            // system scope; this graph node's thread has none (no tenant set: the update would fail).
            otherWorkersClaim.set(Tenancy.callAsSystem(() -> takeOver(c.caseId())));
            return StubCaseInvestigator.defaultResult(c);
        });

        worker.runOnce();

        assertThat(eventTypes(cases, tenant, caseId)).as("a lost claim is not a failed attempt").containsExactly("OPENED");
        assertThat(claimedAt(caseId)).as("the other worker's claim stands").contains(otherWorkersClaim.get());
        assertThat(cases.find(tenant, caseId).orElseThrow().stage()).isEqualTo(CaseStage.ANALYSIS);
        assertThat(cases.analyses(tenant, caseId)).isEmpty();
    }

    /**
     * The window the checkpoint fence cannot cover: the graph has finished, the result is not stored yet. Fails without
     * the {@code claimed_at} check in {@link CaseRepository#complete}: the stale worker would store its analysis and
     * hand the case to the analyst under the other worker's claim. Its heartbeat cannot win the claim back either.
     */
    @Test
    void claimTakenOverAfterTheGraphFinishedKeepsTheResultOut() {
        String caseId = newCase(Severity.HIGH);
        CaseClaim claim = cases.claim(1, CLAIM_TIMEOUT, LEASE, 3).getFirst();
        assertThat(claim.fraudCase().caseId()).isEqualTo(caseId);
        CaseAnalysisGraph.Outcome outcome = analysis.analyse(contextOf(claim), claim.claimedAt());

        Instant otherWorkersClaim = takeOver(caseId);
        assertThat(cases.renew(List.of(claim))).as("the stale worker's heartbeat renews nothing").isZero();
        CaseRepository.Completion completion = Tenancy.call(tenant,
                () -> cases.complete(claim, outcome.mode(), outcome.investigation(), outcome.review()));

        assertThat(completion).isEqualTo(CaseRepository.Completion.LOST_CLAIM);
        assertThat(cases.find(tenant, caseId).orElseThrow().stage()).isEqualTo(CaseStage.ANALYSIS);
        assertThat(cases.analyses(tenant, caseId)).isEmpty();
        assertThat(eventTypes(cases, tenant, caseId)).containsExactly("OPENED");
        assertThat(outboxTypes(jdbc, caseId)).isEmpty();
        assertThat(claimedAt(caseId)).contains(otherWorkersClaim);
    }

    @Test
    void abandonedClaimIsTakenBackAfterTimeout() {
        String caseId = newCase(Severity.HIGH);
        cases.claim(1, Duration.ofMinutes(5), 3);
        jdbc.sql("update fraud_case set claimed_at = now() - interval '10 minutes' where case_id = cast(:id as uuid)")
                .param("id", caseId).update();

        worker.runOnce();

        assertThat(cases.find(tenant, caseId).orElseThrow().stage()).isEqualTo(CaseStage.AWAITING_DECISION);
    }

    @Test
    void newAlertDuringAnalysisSupersedesTheRunAndTheNextRunSeesBothAlerts() {
        String caseId = newCase(Severity.MEDIUM);
        investigator.respondWith(c -> {
            if (c.run() == 1) {
                // Evidence arrives while the agent is still working on run 1.
                intake.accept(alertOn(tenant, c.accountId(), Severity.HIGH));
            }
            return StubCaseInvestigator.defaultResult(c);
        });

        worker.runOnce();
        assertThat(cases.find(tenant, caseId).orElseThrow().stage()).as("superseded").isEqualTo(CaseStage.ANALYSIS);
        worker.runOnce();

        assertThat(investigator.lastContext().alerts()).hasSize(2);
        assertThat(cases.find(tenant, caseId).orElseThrow().stage()).isEqualTo(CaseStage.AWAITING_DECISION);
        assertThat(cases.analyses(tenant, caseId)).extracting(CaseRepository.CaseAnalysis::run).containsExactly(2, 1);
        assertThat(eventTypes(cases, tenant, caseId)).contains("ANALYSIS_SUPERSEDED");
    }

    /**
     * Alert-to-report is timed for the report the analyst gets: a run superseded by new evidence (or one whose claim
     * was lost) is not a report anyone waits for, and timing it would count one alert's wait twice (ADR 0024).
     */
    @Test
    void onlyTheRunThatReachesTheAnalystIsTimed() {
        newCase(Severity.MEDIUM);
        investigator.respondWith(c -> {
            if (c.run() == 1) {
                intake.accept(alertOn(tenant, c.accountId(), Severity.HIGH));
            }
            return StubCaseInvestigator.defaultResult(c);
        });
        long analysis = pipelineCount("analysis");
        long total = pipelineCount("total");

        worker.runOnce();
        assertThat(pipelineCount("analysis")).as("superseded run").isEqualTo(analysis);
        assertThat(pipelineCount("total")).as("superseded run").isEqualTo(total);

        worker.runOnce();
        assertThat(pipelineCount("analysis")).as("the run the analyst gets").isEqualTo(analysis + 1);
        assertThat(pipelineCount("total")).as("the run the analyst gets").isEqualTo(total + 1);
    }

    private long pipelineCount(String stage) {
        return metrics.registry().get(PipelineMetrics.TIMER).tag("stage", stage).timer().count();
    }

    /**
     * Drill 02 (ADR 0026): attempts on consecutive polls, seconds apart, used up all three within a model outage of
     * half a minute and the case failed for good. Each failed attempt now waits a growing pause.
     */
    @Test
    void aFailedAttemptWaitsAGrowingPauseSoAnOutageOfMinutesDoesNotFailTheCase() {
        String caseId = newCase(Severity.HIGH);
        investigator.respondWith(c -> {
            throw new IllegalStateException("no provider answered");
        });

        worker.runOnce();
        assertThat(worker.runOnce()).as("the next poll leaves the case alone").isZero();
        assertThat(secondsUntilNextAttempt(caseId)).isBetween(55.0, 60.0);

        makeDue(jdbc, caseId);
        worker.runOnce();
        assertThat(secondsUntilNextAttempt(caseId)).as("doubled").isBetween(115.0, 120.0);

        investigator.reset();
        makeDue(jdbc, caseId);
        worker.runOnce();

        assertThat(cases.find(tenant, caseId).orElseThrow().stage()).isEqualTo(CaseStage.AWAITING_DECISION);
        assertThat(eventTypes(cases, tenant, caseId))
                .containsExactly("OPENED", "ANALYSIS_ATTEMPT_FAILED", "ANALYSIS_ATTEMPT_FAILED", "ANALYSED");
        assertThat(jdbc.sql("select next_attempt_at is null from fraud_case where case_id = cast(:id as uuid)")
                .param("id", caseId).query(Boolean.class).single()).as("no pause left once analysed").isTrue();
    }

    private double secondsUntilNextAttempt(String caseId) {
        return jdbc.sql("""
                        select extract(epoch from next_attempt_at - clock_timestamp()) from fraud_case
                        where case_id = cast(:id as uuid)
                        """)
                .param("id", caseId).query(Double.class).single();
    }

    /**
     * Drill 10 (ADR 0026): the claims of an instance killed mid-analysis waited the full 5-minute claim timeout,
     * although the instance was back in 15 s. A claim nobody renews now lapses after the lease.
     */
    @Test
    void aClaimWhoseOwnerDiedIsTakenOverAfterTheLeaseNotTheClaimTimeout() {
        String caseId = newCase(Severity.HIGH);
        // Claimed 40 s ago by an instance that died: no completion, no renewal since.
        jdbc.sql("""
                        update fraud_case set claimed_at = clock_timestamp() - interval '40 seconds', attempts = 1
                        where case_id = cast(:id as uuid)
                        """)
                .param("id", caseId).update();

        assertThat(worker.runOnce()).isEqualTo(1);

        assertThat(cases.find(tenant, caseId).orElseThrow().stage()).isEqualTo(CaseStage.AWAITING_DECISION);
    }

    @Test
    void aClaimRenewedWithinTheLeaseIsLeftToItsOwner() {
        String caseId = newCase(Severity.HIGH);
        jdbc.sql("""
                        update fraud_case set claimed_at = clock_timestamp() - interval '40 seconds',
                            claim_renewed_at = clock_timestamp(), attempts = 1
                        where case_id = cast(:id as uuid)
                        """)
                .param("id", caseId).update();

        assertThat(worker.runOnce()).isZero();
    }

    /**
     * A run longer than the lease keeps its claim because its worker renews it; another instance polling meanwhile
     * finds nothing to take. Without the renewal the second worker takes the case over after the lease.
     */
    @Test
    void aRunLongerThanTheLeaseKeepsItsClaimWhileItsWorkerRenewsIt() throws Exception {
        String caseId = newCase(Severity.HIGH);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        investigator.respondWith(c -> {
            running.countDown();
            try {
                finish.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return StubCaseInvestigator.defaultResult(c);
        });
        InvestigationProperties shortLease = props(0, Duration.ofSeconds(2), Duration.ofSeconds(1),
                Duration.ofMillis(200));
        CaseWorker owner = worker(shortLease);
        CaseWorker other = worker(shortLease);

        CompletableFuture<Integer> run = CompletableFuture.supplyAsync(owner::runOnce);
        assertThat(running.await(10, TimeUnit.SECONDS)).isTrue();
        long until = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < until) {
            assertThat(other.runOnce()).as("three leases long, nothing to take over").isZero();
        }
        finish.countDown();

        assertThat(run.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(investigator.calls()).isEqualTo(1);
        assertThat(cases.find(tenant, caseId).orElseThrow().stage()).isEqualTo(CaseStage.AWAITING_DECISION);
    }

    /**
     * ADR 0025 Next, drills 2, 3 and 7b (ADR 0026): the worker claimed a batch and waited for all of it, so one slow
     * analysis held every slot of the batch, and nothing more was claimed until the next poll. Now a freed slot claims
     * the next case at once, while the slow one goes on.
     */
    @Test
    void aSlowAnalysisDoesNotHoldUpTheCasesBehindIt() throws Exception {
        String slow = newCase(Severity.HIGH);
        List<String> quick = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            quick.add(newCase(Severity.MEDIUM));
        }
        CountDownLatch finishSlow = new CountDownLatch(1);
        investigator.respondWith(c -> {
            if (c.caseId().equals(slow)) {
                try {
                    finishSlow.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return StubCaseInvestigator.defaultResult(c);
        });
        CaseWorker pool = worker(props(0, Duration.ofSeconds(2)));

        CompletableFuture<Void> poll = CompletableFuture.runAsync(pool::poll);
        try {
            await().pollInSameThread().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(quick).as("all five analysed while the slow one runs (five slots)")
                            .allSatisfy(id -> assertThat(cases.find(tenant, id).orElseThrow().stage())
                                    .isEqualTo(CaseStage.AWAITING_DECISION)));
            assertThat(cases.find(tenant, slow).orElseThrow().stage()).isEqualTo(CaseStage.ANALYSIS);
        } finally {
            finishSlow.countDown();
        }
        poll.get(10, TimeUnit.SECONDS);
        await().pollInSameThread().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(cases.find(tenant, slow).orElseThrow().stage()).isEqualTo(CaseStage.AWAITING_DECISION));
    }

    private CaseWorker worker(InvestigationProperties props) {
        return new CaseWorker(cases, alerts, analysis, props, systems,
                io.opentelemetry.api.OpenTelemetry.noop().getTracer("test"), metrics);
    }

    @Test
    void hourlyBudgetStopsClaimingWhenSpent() {
        newCase(Severity.HIGH);
        newCase(Severity.HIGH);
        int alreadyUsed = cases.startedInLastHour();
        CaseWorker capped = new CaseWorker(cases, alerts, analysis, props(alreadyUsed + 1, Duration.ofSeconds(2)), systems, io.opentelemetry.api.OpenTelemetry.noop().getTracer("test"),
                metrics);

        assertThat(capped.runOnce()).isEqualTo(1);
        assertThat(capped.runOnce()).isZero();
    }

    /**
     * Another worker takes the case over the way it happens in production: this worker's renewals stopped for longer
     * than a lease (a long pause, a lost connection), so its claim lapsed and the next claim took it with a new
     * {@code claimed_at}, the fencing token. Returns the new owner's token.
     */
    private Instant takeOver(String caseId) {
        jdbc.sql("""
                        update fraud_case set claim_renewed_at = clock_timestamp() - interval '1 hour'
                        where case_id = cast(:id as uuid)
                        """)
                .param("id", caseId).update();
        CaseClaim other = cases.claim(1, CLAIM_TIMEOUT, LEASE, 3).getFirst();
        assertThat(other.fraudCase().caseId()).isEqualTo(caseId);
        return other.claimedAt();
    }

    private Optional<Instant> claimedAt(String caseId) {
        return jdbc.sql("select claimed_at from fraud_case where case_id = cast(:id as uuid)")
                .param("id", caseId)
                .query((rs, n) -> Optional.ofNullable(rs.getTimestamp("claimed_at")).map(Timestamp::toInstant))
                .single();
    }

    private CaseContext contextOf(CaseClaim claim) {
        FraudCase c = claim.fraudCase();
        List<Alert> caseAlerts = alerts.findByCase(c.tenantId(), c.caseId()).stream().map(StoredAlert::alert).toList();
        return new CaseContext(c.caseId(), c.tenantId(), c.accountId(), c.customerId(), caseAlerts, c.analysisRun(),
                c.analystNote(), systems.connectedFor(c.tenantId()));
    }

    private String newCase(Severity severity) {
        return intake.accept(alertOn(tenant, "acc-" + UUID.randomUUID(), severity)).orElseThrow();
    }

    static InvestigationProperties props(int maxPerHour, Duration lockTimeout) {
        return props(maxPerHour, lockTimeout, Duration.ofSeconds(30), Duration.ofSeconds(10));
    }

    static InvestigationProperties props(int maxPerHour, Duration lockTimeout, Duration lease, Duration heartbeat) {
        return props(maxPerHour, lockTimeout, lease, heartbeat, Duration.ZERO);
    }

    static InvestigationProperties props(int maxPerHour, Duration lockTimeout, Duration lease, Duration heartbeat,
                                         Duration evidenceWait) {
        return new InvestigationProperties(true, 5, Duration.ofMinutes(5), lease, heartbeat, 3,
                new InvestigationProperties.Retry(Duration.ofMinutes(1), 2, Duration.ofMinutes(10)), maxPerHour, lockTimeout,
                evidenceWait, new InvestigationProperties.Tools(90, 100, 1000), new InvestigationProperties.Report(60, 40, 1, true));
    }
}
