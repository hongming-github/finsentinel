package com.finsentinel.platform.investigation;

import com.finsentinel.common.Alert;
import com.finsentinel.common.AlertRule;
import com.finsentinel.common.Severity;
import com.finsentinel.platform.PlatformTest;
import com.finsentinel.platform.StubCaseInvestigator;
import com.finsentinel.platform.alert.AlertRepository;
import com.finsentinel.platform.alert.AlertStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.finsentinel.platform.TestData.alert;
import static com.finsentinel.platform.TestData.uniqueTenant;
import static org.assertj.core.api.Assertions.assertThat;

@PlatformTest
class InvestigationWorkerTest {

    @Autowired
    InvestigationWorker worker;
    @Autowired
    InvestigationRepository queue;
    @Autowired
    AlertRepository alerts;
    @Autowired
    StubCaseInvestigator investigator;
    @Autowired
    JdbcClient jdbc;

    private final String tenant = uniqueTenant();

    @BeforeEach
    void drainQueue() {
        // Other test classes share the database; park their alerts so this class sees only its own.
        jdbc.sql("update alert set status = 'FAILED' where status in ('NEW', 'INVESTIGATING')").update();
    }

    @AfterEach
    void resetStub() {
        investigator.reset();
    }

    @Test
    void investigatedAlertGetsReportAndStatus() {
        Alert alert = newAlert(Severity.HIGH);

        worker.runOnce();

        assertThat(status(alert)).isEqualTo(AlertStatus.REPORTED);
        assertThat(queue.findReport(tenant, alert.alertId())).hasValueSatisfying(view -> {
            assertThat(view.report().summary()).isEqualTo("stub report");
            assertThat(view.model()).isEqualTo("stub-model");
        });
    }

    @Test
    void failedInvestigationIsRetriedThenMarkedFailed() {
        Alert alert = newAlert(Severity.MEDIUM);
        investigator.respondWith(a -> {
            throw new IllegalStateException("LLM down");
        });

        worker.runOnce();
        assertThat(status(alert)).isEqualTo(AlertStatus.NEW);
        worker.runOnce();
        worker.runOnce();

        assertThat(status(alert)).isEqualTo(AlertStatus.FAILED);
        assertThat(worker.runOnce()).isZero();
    }

    @Test
    void highSeverityIsClaimedFirst() {
        Alert medium = newAlert(Severity.MEDIUM);
        Alert high = newAlert(Severity.HIGH);

        List<Claim> claims = queue.claim(1, Duration.ofMinutes(5), 3);

        assertThat(claims).singleElement().extracting(c -> c.alert().alert().alertId()).isEqualTo(high.alertId());
        assertThat(status(medium)).isEqualTo(AlertStatus.NEW);
    }

    @Test
    void workerThatLostItsClaimDoesNotWriteAResult() {
        Alert alert = newAlert(Severity.HIGH);
        investigator.respondWith(a -> {
            // Another worker re-claims while this one is still thinking.
            jdbc.sql("update alert set claimed_at = clock_timestamp() where alert_id = :id")
                    .param("id", UUID.fromString(a.alert().alertId())).update();
            return StubCaseInvestigator.defaultResult(a);
        });

        worker.runOnce();

        assertThat(status(alert)).isEqualTo(AlertStatus.INVESTIGATING);
        assertThat(queue.findReport(tenant, alert.alertId())).isEmpty();
    }

    @Test
    void abandonedClaimIsTakenBackAfterTimeout() {
        Alert alert = newAlert(Severity.HIGH);
        queue.claim(1, Duration.ofMinutes(5), 3);
        jdbc.sql("update alert set claimed_at = now() - interval '10 minutes' where alert_id = :id")
                .param("id", UUID.fromString(alert.alertId())).update();

        worker.runOnce();

        assertThat(status(alert)).isEqualTo(AlertStatus.REPORTED);
    }

    @Test
    void hourlyBudgetStopsClaimingWhenSpent() {
        Alert first = newAlert(Severity.HIGH);
        Alert second = newAlert(Severity.HIGH);
        int alreadyUsed = queue.startedInLastHour();
        InvestigationWorker capped = new InvestigationWorker(queue, investigator,
                new InvestigationProperties(true, 5, Duration.ofMinutes(5), 3, alreadyUsed + 1,
                        new InvestigationProperties.Tools(90, 100), new InvestigationProperties.Report(60, 40, 1)));

        assertThat(capped.runOnce()).isEqualTo(1);
        assertThat(capped.runOnce()).isZero();

        assertThat(List.of(status(first), status(second)))
                .containsExactlyInAnyOrder(AlertStatus.REPORTED, AlertStatus.NEW);
    }

    private Alert newAlert(Severity severity) {
        Alert alert = alert(tenant, "acc-" + UUID.randomUUID(), AlertRule.LARGE_AMOUNT, severity, List.of("t1"), Instant.now());
        alerts.insertIfAbsent(alert);
        return alert;
    }

    private AlertStatus status(Alert alert) {
        return alerts.findById(tenant, alert.alertId()).orElseThrow().status();
    }
}
