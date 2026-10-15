package com.finsentinel.platform.cases;

import com.finsentinel.platform.systems.SystemsProperties;
import com.finsentinel.platform.systems.BankSystem;
import com.finsentinel.common.Alert;
import com.finsentinel.common.Severity;
import com.finsentinel.platform.PlatformTest;
import com.finsentinel.platform.StubCaseInvestigator;
import com.finsentinel.platform.StubCaseReviewer;
import com.finsentinel.platform.alert.AlertRepository;
import com.finsentinel.platform.alert.StoredAlert;
import com.finsentinel.platform.analysis.CaseAnalysisGraph;
import com.finsentinel.platform.investigation.CaseContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.finsentinel.platform.TestData.uniqueTenant;
import static com.finsentinel.platform.cases.CaseTestSupport.alertOn;
import static com.finsentinel.platform.cases.CaseTestSupport.eventTypes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The analysis graph's checkpoints (ADR 0008): a new attempt resumes after the last finished node, and a worker that
 * lost its claim cannot write any more checkpoints.
 */
@PlatformTest
class CaseAnalysisResumeTest {

    @Autowired
    CaseRepository cases;
    @Autowired
    SystemsProperties systems;
    @Autowired
    CaseIntake intake;
    @Autowired
    AlertRepository alerts;
    @Autowired
    CaseAnalysisGraph analysis;
    @Autowired
    StubCaseInvestigator investigator;
    @Autowired
    StubCaseReviewer reviewer;
    @Autowired
    JdbcClient jdbc;

    private final String tenant = uniqueTenant();
    private CaseWorker worker;

    @BeforeEach
    void setUp() {
        CaseTestSupport.parkOtherCases(jdbc);
        investigator.reset();
        reviewer.reset();
        // Unlimited budget: these tests are about resuming, not about the hourly cap other tests use up.
        worker = new CaseWorker(cases, alerts, analysis, CaseWorkerTest.props(0, Duration.ofSeconds(2)), systems, io.opentelemetry.api.OpenTelemetry.noop().getTracer("test"));
    }

    @AfterEach
    void resetStubs() {
        investigator.reset();
        reviewer.reset();
    }

    @Test
    void failedAttemptKeepsItsCheckpointsAndTheNextAttemptSkipsFinishedNodes() {
        String caseId = newCase();
        reviewer.respondWith((c, i) -> {
            throw new IllegalStateException("reviewer down");
        });

        worker.runOnce();

        assertThat(cases.find(tenant, caseId).orElseThrow().stage()).isEqualTo(CaseStage.ANALYSIS);
        assertThat(checkpointNodes(caseId)).containsExactly("__START__", "investigate");
        assertThat(jdbc.sql("""
                        select state -> 'investigation' -> 'report' ->> 'disposition' from graph_checkpoint
                        where case_id = cast(:id as uuid) and node_id = 'investigate'
                        """)
                .param("id", caseId).query(String.class).single())
                .as("checkpoint state is plain, queryable JSON")
                .isEqualTo("NEEDS_REVIEW");

        reviewer.reset();
        worker.runOnce();

        assertThat(cases.find(tenant, caseId).orElseThrow().stage()).isEqualTo(CaseStage.AWAITING_DECISION);
        assertThat(investigator.calls()).as("investigator ran once across both attempts").isEqualTo(1);
        assertThat(reviewer.calls()).isEqualTo(1);
        assertThat(checkpointNodes(caseId)).as("the lifecycle deletes the run's checkpoints").isEmpty();
        assertThat(eventTypes(cases, tenant, caseId)).containsExactly("OPENED", "ANALYSIS_ATTEMPT_FAILED", "ANALYSED");
        assertThat(cases.events(tenant, caseId).get(1).detail().get("error").asString())
                .as("the node's own error, not the graph runtime's wrappers")
                .isEqualTo("java.lang.IllegalStateException: reviewer down");
    }

    @Test
    void crashedWorkersCaseIsResumedByTheNextClaimAfterTheTimeout() {
        String caseId = newCase();
        CaseClaim claim = claimOnly(caseId);
        reviewer.respondWith((c, i) -> {
            throw new IllegalStateException("process killed");
        });
        // The worker dies in the review node: no fail(), the claim stays until it times out.
        assertThatThrownBy(() -> analysis.analyse(contextOf(claim), claim.claimedAt())).hasMessageContaining("process killed");
        reviewer.reset();
        expireClaim(caseId);

        worker.runOnce();

        assertThat(cases.find(tenant, caseId).orElseThrow().stage()).isEqualTo(CaseStage.AWAITING_DECISION);
        assertThat(investigator.calls()).isEqualTo(1);
        assertThat(reviewer.calls()).as("the crashed review ran again, nothing before it").isEqualTo(1);
        assertThat(cases.analyses(tenant, caseId)).singleElement();
    }

    @Test
    void crashAfterTheLastNodeFinishesTheRunWithoutCallingAnyAgent() {
        String caseId = newCase();
        CaseClaim claim = claimOnly(caseId);
        // The graph finished, then the process died before the case was moved on.
        analysis.analyse(contextOf(claim), claim.claimedAt());
        expireClaim(caseId);

        worker.runOnce();

        assertThat(cases.find(tenant, caseId).orElseThrow().stage()).isEqualTo(CaseStage.AWAITING_DECISION);
        assertThat(investigator.calls()).isEqualTo(1);
        assertThat(reviewer.calls()).isEqualTo(1);
    }

    /**
     * Fails without the fence: the stale worker's checkpoint would land after the successor finished and cleaned up,
     * leaving a row for a run that is over (and, mid-run, mixing two workers' histories in one thread).
     */
    @Test
    void workerWhoseClaimTimedOutCannotWriteCheckpointsAfterAnotherWorkerTookOver() throws Exception {
        String caseId = newCase();
        CountDownLatch staleWorkerThinking = new CountDownLatch(1);
        CountDownLatch wakeStaleWorker = new CountDownLatch(1);
        investigator.respondWith(c -> {
            if (investigator.calls() == 1) {
                staleWorkerThinking.countDown();
                await(wakeStaleWorker);
            }
            return StubCaseInvestigator.defaultResult(c);
        });

        CompletableFuture<Integer> stale = CompletableFuture.supplyAsync(worker::runOnce);
        assertThat(staleWorkerThinking.await(10, TimeUnit.SECONDS)).isTrue();
        expireClaim(caseId);
        worker.runOnce();
        assertThat(cases.find(tenant, caseId).orElseThrow().stage()).isEqualTo(CaseStage.AWAITING_DECISION);
        wakeStaleWorker.countDown();
        stale.get(10, TimeUnit.SECONDS);

        assertThat(investigator.calls()).isEqualTo(2);
        assertThat(reviewer.calls()).as("the stale worker stopped at its first write: it never ran the review")
                .isEqualTo(1);
        assertThat(checkpointNodes(caseId)).isEmpty();
        assertThat(cases.analyses(tenant, caseId)).singleElement();
        assertThat(eventTypes(cases, tenant, caseId)).containsExactly("OPENED", "ANALYSED");
    }

    @Test
    void newRunStartsAFreshGraphThreadAndClearsTheOldRunsCheckpoints() {
        String caseId = newCase();
        reviewer.respondWith((c, i) -> {
            throw new IllegalStateException("reviewer down");
        });
        worker.runOnce();
        assertThat(checkpointNodes(caseId)).hasSize(2);
        reviewer.reset();
        String account = cases.find(tenant, caseId).orElseThrow().accountId();
        intake.accept(alertOn(tenant, account, Severity.HIGH));

        worker.runOnce();

        assertThat(investigator.calls()).as("run 2 has new evidence, so it starts from the beginning").isEqualTo(2);
        assertThat(investigator.lastContext().alerts()).hasSize(2);
        assertThat(cases.find(tenant, caseId).orElseThrow().stage()).isEqualTo(CaseStage.AWAITING_DECISION);
        assertThat(checkpointNodes(caseId)).isEmpty();
    }

    private String newCase() {
        return intake.accept(alertOn(tenant, "acc-" + UUID.randomUUID(), Severity.HIGH)).orElseThrow();
    }

    private CaseClaim claimOnly(String caseId) {
        CaseClaim claim = cases.claim(1, Duration.ofMinutes(5), 3).getFirst();
        assertThat(claim.fraudCase().caseId()).isEqualTo(caseId);
        return claim;
    }

    private CaseContext contextOf(CaseClaim claim) {
        FraudCase c = claim.fraudCase();
        List<Alert> caseAlerts = alerts.findByCase(c.tenantId(), c.caseId()).stream().map(StoredAlert::alert).toList();
        return new CaseContext(c.caseId(), c.tenantId(), c.accountId(), c.customerId(), caseAlerts, c.analysisRun(),
                c.analystNote(), BankSystem.ALL);
    }

    private void expireClaim(String caseId) {
        jdbc.sql("update fraud_case set claimed_at = claimed_at - interval '10 minutes' where case_id = cast(:id as uuid)")
                .param("id", caseId).update();
    }

    private List<String> checkpointNodes(String caseId) {
        return jdbc.sql("select node_id from graph_checkpoint where case_id = cast(:id as uuid) order by seq")
                .param("id", caseId).query(String.class).list();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
