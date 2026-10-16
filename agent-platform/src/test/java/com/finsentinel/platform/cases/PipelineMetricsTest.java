package com.finsentinel.platform.cases;

import com.finsentinel.common.Alert;
import com.finsentinel.common.AlertRule;
import com.finsentinel.common.Severity;
import com.finsentinel.platform.alert.StoredAlert;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineMetricsTest {

    private static final Instant PAID = Instant.parse("2026-10-16T08:00:00Z");
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    // The gauge reads the repository only when published; these tests never publish.
    private final PipelineMetrics metrics = new PipelineMetrics(registry,
            Clock.fixed(PAID.plusSeconds(20), ZoneOffset.UTC), null);

    @Test
    void eachStageOfTheTriggeringAlertIsTimed() {
        StoredAlert older = stored(PAID.minusSeconds(3600), PAID.minusSeconds(3599), PAID.minusSeconds(3598));
        StoredAlert trigger = stored(PAID, PAID.plusMillis(300), PAID.plusMillis(500));

        metrics.analysed(List.of(older, trigger), claim(PAID.plusSeconds(2), null));

        assertThat(ms("detection")).isEqualTo(300);
        assertThat(ms("delivery")).isEqualTo(200);
        assertThat(ms("queue")).isEqualTo(1_500);
        assertThat(ms("analysis")).isEqualTo(18_000);
        assertThat(ms("total")).isEqualTo(20_000);
    }

    /** An analyst's re-analysis has no alert behind it: an hour-old alert would read as an hour in the queue. */
    @Test
    void aRunAnAnalystRequestedTimesOnlyTheAnalysis() {
        metrics.analysed(List.of(stored(PAID.minusSeconds(3600), PAID.minusSeconds(3599), PAID.minusSeconds(3598))),
                claim(PAID.plusSeconds(5), "please check the payee again"));

        assertThat(timer("analysis").count()).isEqualTo(1);
        assertThat(timer("queue").count()).isZero();
        assertThat(timer("total").count()).isZero();
    }

    @Test
    void aNegativeSpanFromClockSkewIsSkipped() {
        // Flink's clock a little ahead of the platform's: detected after it was received.
        metrics.analysed(List.of(stored(PAID, PAID.plusMillis(900), PAID.plusMillis(800))),
                claim(PAID.plusSeconds(1), null));

        assertThat(timer("delivery").count()).isZero();
        assertThat(timer("detection").count()).isEqualTo(1);
    }

    private long ms(String stage) {
        return (long) timer(stage).totalTime(TimeUnit.MILLISECONDS);
    }

    private Timer timer(String stage) {
        return registry.get(PipelineMetrics.TIMER).tag("stage", stage).timer();
    }

    private static StoredAlert stored(Instant eventTime, Instant detectedAt, Instant receivedAt) {
        Alert alert = new Alert("a-" + eventTime.toEpochMilli(), "straits", "acc-1", "cus-1", AlertRule.GEO_JUMP,
                Severity.HIGH, "reason", List.of("t-1"), eventTime, detectedAt);
        return new StoredAlert(alert, "case-1", receivedAt);
    }

    private static CaseClaim claim(Instant claimedAt, String analystNote) {
        FraudCase fraudCase = new FraudCase("case-1", "straits", "acc-1", "cus-1", CaseStage.ANALYSIS, Severity.HIGH, 1,
                1, analystNote, 1, PAID, PAID, null, null, null);
        return new CaseClaim(fraudCase, claimedAt);
    }
}
