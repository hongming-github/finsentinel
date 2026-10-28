package com.finsentinel.platform.cases;

import com.finsentinel.common.AlertRule;
import com.finsentinel.common.Channel;
import com.finsentinel.common.Severity;
import com.finsentinel.common.Transaction;
import com.finsentinel.platform.PlatformTest;
import com.finsentinel.platform.StubCaseInvestigator;
import com.finsentinel.platform.alert.AlertRepository;
import com.finsentinel.platform.analysis.CaseAnalysisGraph;
import com.finsentinel.platform.systems.SystemsProperties;
import io.opentelemetry.api.OpenTelemetry;
import com.finsentinel.platform.investigation.CaseContext;
import com.finsentinel.platform.transaction.TransactionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.finsentinel.platform.TestData.alert;
import static com.finsentinel.platform.TestData.txn;
import static com.finsentinel.platform.TestData.uniqueTenant;
import static com.finsentinel.platform.cases.CaseTestSupport.eventTypes;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 9B found analyses that started before the platform had stored their alert's transactions: the copy lagged,
 * {@code getAlertTransactions} returned nothing, and 44 analyses failed (with a real model they would have been
 * reports without transaction evidence). A case waits for that evidence, for a bounded time (ADR 0025).
 */
@PlatformTest
class EvidenceGateTest {

    private static final Duration CLAIM_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Duration EVIDENCE_WAIT = Duration.ofSeconds(60);

    @Autowired
    CaseRepository cases;
    @Autowired
    CaseIntake intake;
    @Autowired
    TransactionRepository transactions;
    @Autowired
    AlertRepository alerts;
    @Autowired
    CaseAnalysisGraph analysis;
    @Autowired
    SystemsProperties systems;
    @Autowired
    PipelineMetrics metrics;
    @Autowired
    StubCaseInvestigator investigator;
    @Autowired
    JdbcClient jdbc;

    private final String tenant = uniqueTenant();
    private final String account = "acc-" + UUID.randomUUID();

    @BeforeEach
    void ownQueue() {
        CaseTestSupport.parkOtherCases(jdbc);
    }

    @AfterEach
    void resetStub() {
        investigator.reset();
    }

    @Test
    void aCaseIsNotClaimedUntilItsAlertsTransactionsAreStored() {
        Transaction t = txn(tenant, account, "900.00", Channel.CARD_PRESENT, "SG", Instant.now());
        String caseId = fileAlert(List.of(t.txnId()));

        assertThat(cases.claim(5, CLAIM_TIMEOUT, LEASE, EVIDENCE_WAIT, 3)).isEmpty();

        transactions.insertAll(List.of(t));
        assertThat(cases.claim(5, CLAIM_TIMEOUT, LEASE, EVIDENCE_WAIT, 3))
                .extracting(claim -> claim.fraudCase().caseId()).containsExactly(caseId);
    }

    @Test
    void everyAlertOfTheCaseCounts() {
        Transaction first = txn(tenant, account, "900.00", Channel.CARD_PRESENT, "SG", Instant.now());
        transactions.insertAll(List.of(first));
        fileAlert(List.of(first.txnId()));
        // A second alert on the same account joins the case; its transaction has not been stored yet.
        fileAlert(List.of("t-" + UUID.randomUUID()));

        assertThat(cases.claim(5, CLAIM_TIMEOUT, LEASE, EVIDENCE_WAIT, 3)).isEmpty();
    }

    @Test
    void afterTheWaitTheCaseIsClaimedWithoutItsTransactions() {
        String caseId = fileAlert(List.of("t-" + UUID.randomUUID()));
        jdbc.sql("update alert set received_at = now() - interval '2 minutes' where case_id = cast(:id as uuid)")
                .param("id", caseId).update();

        assertThat(cases.claim(5, CLAIM_TIMEOUT, LEASE, EVIDENCE_WAIT, 3))
                .extracting(claim -> claim.fraudCase().caseId()).containsExactly(caseId);
    }

    @Test
    void theAnalysisIsToldWhichTransactionsAreMissingAndTheCaseRecordsIt() {
        Transaction stored = txn(tenant, account, "900.00", Channel.CARD_PRESENT, "SG", Instant.now());
        transactions.insertAll(List.of(stored));
        String missing = "t-" + UUID.randomUUID();
        String caseId = fileAlert(List.of(stored.txnId(), missing));
        // The wait ran out.
        jdbc.sql("update alert set received_at = now() - interval '2 minutes' where case_id = cast(:id as uuid)")
                .param("id", caseId).update();
        AtomicReference<CaseContext> seen = new AtomicReference<>();
        investigator.respondWith(c -> {
            seen.set(c);
            return StubCaseInvestigator.defaultResult(c);
        });

        waitingWorker().runOnce();

        assertThat(seen.get().missingTxnIds()).containsExactly(missing);
        assertThat(eventTypes(cases, tenant, caseId)).containsExactly("OPENED", "EVIDENCE_INCOMPLETE", "ANALYSED");
    }

    @Test
    void aCaseWithAllItsTransactionsStartsWithNothingMissing() {
        Transaction stored = txn(tenant, account, "900.00", Channel.CARD_PRESENT, "SG", Instant.now());
        transactions.insertAll(List.of(stored));
        String caseId = fileAlert(List.of(stored.txnId()));
        AtomicReference<CaseContext> seen = new AtomicReference<>();
        investigator.respondWith(c -> {
            seen.set(c);
            return StubCaseInvestigator.defaultResult(c);
        });

        waitingWorker().runOnce();

        assertThat(seen.get().missingTxnIds()).isEmpty();
        assertThat(eventTypes(cases, tenant, caseId)).containsExactly("OPENED", "ANALYSED");
    }

    /** A worker with the production behaviour: the test configuration turns the wait off (evidence-wait 0). */
    private CaseWorker waitingWorker() {
        return new CaseWorker(cases, alerts, analysis, CaseWorkerTest.props(0, Duration.ofSeconds(2),
                Duration.ofSeconds(30), Duration.ofSeconds(10), EVIDENCE_WAIT), systems,
                OpenTelemetry.noop().getTracer("test"), metrics);
    }

    private String fileAlert(List<String> txnIds) {
        return intake.accept(alert(tenant, account, AlertRule.LARGE_AMOUNT, Severity.HIGH, txnIds, Instant.now()))
                .orElseThrow();
    }
}
