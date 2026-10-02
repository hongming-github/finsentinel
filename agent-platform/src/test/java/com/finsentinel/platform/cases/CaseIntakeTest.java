package com.finsentinel.platform.cases;

import com.finsentinel.common.Alert;
import com.finsentinel.common.Severity;
import com.finsentinel.platform.PlatformTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.finsentinel.platform.TestData.uniqueTenant;
import static com.finsentinel.platform.cases.CaseTestSupport.alertOn;
import static com.finsentinel.platform.cases.CaseTestSupport.eventTypes;
import static org.assertj.core.api.Assertions.assertThat;

@PlatformTest
class CaseIntakeTest {

    @Autowired
    CaseIntake intake;
    @Autowired
    CaseRepository cases;
    @Autowired
    CaseWorker worker;
    @Autowired
    JdbcClient jdbc;

    private final String tenant = uniqueTenant();

    @Test
    void alertsOnOneAccountShareTheOpenCaseAndRaiseItsPriority() {
        String first = intake.accept(alertOn(tenant, "acc-1", Severity.MEDIUM)).orElseThrow();
        String second = intake.accept(alertOn(tenant, "acc-1", Severity.HIGH)).orElseThrow();
        String otherAccount = intake.accept(alertOn(tenant, "acc-2", Severity.LOW)).orElseThrow();

        assertThat(second).isEqualTo(first);
        assertThat(otherAccount).isNotEqualTo(first);
        FraudCase c = cases.find(tenant, first).orElseThrow();
        assertThat(c.priority()).isEqualTo(Severity.HIGH);
        assertThat(c.analysisRun()).as("second alert asks for a fresh analysis").isEqualTo(2);
        assertThat(eventTypes(cases, tenant, first)).containsExactly("OPENED", "ALERT_ADDED");
    }

    @Test
    void lowerSeverityDoesNotLowerPriority() {
        String caseId = intake.accept(alertOn(tenant, "acc-1", Severity.HIGH)).orElseThrow();
        intake.accept(alertOn(tenant, "acc-1", Severity.LOW));

        assertThat(cases.find(tenant, caseId).orElseThrow().priority()).isEqualTo(Severity.HIGH);
    }

    @Test
    void redeliveredAlertChangesNothing() {
        Alert alert = alertOn(tenant, "acc-1", Severity.MEDIUM);
        String caseId = intake.accept(alert).orElseThrow();
        int version = cases.find(tenant, caseId).orElseThrow().version();

        assertThat(intake.accept(alert)).isEmpty();
        assertThat(cases.find(tenant, caseId).orElseThrow().version()).isEqualTo(version);
    }

    @Test
    void concurrentAlertsOnOneAccountOpenExactlyOneCase() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 8; i++) {
                Alert alert = alertOn(tenant, "acc-race", Severity.MEDIUM);
                results.add(pool.submit(() -> {
                    start.await();
                    return intake.accept(alert).orElseThrow();
                }));
            }
            start.countDown();
            List<String> caseIds = new ArrayList<>();
            for (Future<String> f : results) {
                caseIds.add(f.get());
            }
            assertThat(caseIds).containsOnly(caseIds.getFirst());
        }
        assertThat(jdbc.sql("select count(*) from fraud_case where tenant_id = :t and account_id = 'acc-race'")
                .param("t", tenant).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from alert where tenant_id = :t and account_id = 'acc-race'")
                .param("t", tenant).query(Integer.class).single()).isEqualTo(8);
    }

    @Test
    void newAlertReopensACaseWaitingForTheAnalyst() {
        CaseTestSupport.parkOtherCases(jdbc);
        String caseId = intake.accept(alertOn(tenant, "acc-1", Severity.MEDIUM)).orElseThrow();
        worker.runOnce();
        FraudCase awaiting = cases.find(tenant, caseId).orElseThrow();
        assertThat(awaiting.stage()).isEqualTo(CaseStage.AWAITING_DECISION);

        intake.accept(alertOn(tenant, "acc-1", Severity.MEDIUM));

        FraudCase reopened = cases.find(tenant, caseId).orElseThrow();
        assertThat(reopened.stage()).isEqualTo(CaseStage.ANALYSIS);
        assertThat(reopened.version()).isGreaterThan(awaiting.version());
        assertThat(eventTypes(cases, tenant, caseId)).endsWith("ANALYSED", "REOPENED");
    }

    @Test
    void closedCaseIsNotReused() {
        String closed = intake.accept(alertOn(tenant, "acc-1", Severity.MEDIUM)).orElseThrow();
        jdbc.sql("update fraud_case set stage = 'CLOSED' where case_id = cast(:id as uuid)").param("id", closed).update();

        String next = intake.accept(alertOn(tenant, "acc-1", Severity.MEDIUM)).orElseThrow();

        assertThat(next).isNotEqualTo(closed);
    }
}
