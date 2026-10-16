package com.finsentinel.platform.cases;

import com.finsentinel.platform.alert.StoredAlert;
import com.finsentinel.platform.security.Tenancy;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Where an alert's time goes between the payment and the report the analyst reads (phase 9, ADR 0024), as one timer
 * with a {@code stage} tag and percentile histograms, so a dashboard can show p95 per stage:
 * <ul>
 *   <li>{@code detection}: payment to alert, in Flink (event time to detection time);</li>
 *   <li>{@code delivery}: alert to the platform, over Kafka;</li>
 *   <li>{@code queue}: waiting for a worker, including the analysis budget and claim backlog;</li>
 *   <li>{@code analysis}: the agents, from claim to stored report;</li>
 *   <li>{@code total}: payment to stored report.</li>
 * </ul>
 * Measured on the alert that started the run (the newest of the case). A run an analyst requested has no alert behind
 * it, so only its analysis is timed. Plus a gauge of cases waiting for analysis, the backlog under load.
 */
@Component
public class PipelineMetrics {

    static final String TIMER = "finsentinel.pipeline";
    static final String WAITING = "finsentinel.cases.waiting";

    private final MeterRegistry registry;
    private final Clock clock;
    private final Map<String, Timer> timers;

    @Autowired
    PipelineMetrics(MeterRegistry registry, CaseRepository cases) {
        this(registry, Clock.systemUTC(), cases);
    }

    PipelineMetrics(MeterRegistry registry, Clock clock, CaseRepository cases) {
        this.registry = registry;
        this.clock = clock;
        this.timers = Map.of(
                "detection", timer("detection"), "delivery", timer("delivery"), "queue", timer("queue"),
                "analysis", timer("analysis"), "total", timer("total"));
        // Read when the registry publishes: one count query per export step, across banks (queue work).
        Gauge.builder(WAITING, () -> Tenancy.callAsSystem(() -> cases.countInStage(CaseStage.ANALYSIS)))
                .description("Cases waiting for or in analysis")
                .register(registry);
    }

    private Timer timer(String stage) {
        return Timer.builder(TIMER).tag("stage", stage).description("Alert to report, by stage")
                .publishPercentileHistogram().register(registry);
    }

    /** After a report was stored for {@code claim}'s run over {@code alerts}. */
    void analysed(List<StoredAlert> alerts, CaseClaim claim) {
        Instant now = clock.instant();
        record("analysis", claim.claimedAt(), now);
        if (claim.fraudCase().analystNote() != null || alerts.isEmpty()) {
            return;
        }
        StoredAlert trigger = alerts.stream().max(Comparator.comparing(StoredAlert::receivedAt)).orElseThrow();
        record("detection", trigger.alert().eventTime(), trigger.alert().detectedAt());
        record("delivery", trigger.alert().detectedAt(), trigger.receivedAt());
        record("queue", trigger.receivedAt(), claim.claimedAt());
        record("total", trigger.alert().eventTime(), now);
    }

    /** Clocks of different processes can disagree by a little: a negative span says nothing, so it is skipped. */
    private void record(String stage, Instant from, Instant to) {
        if (from != null && to != null && !to.isBefore(from)) {
            timers.get(stage).record(Duration.between(from, to));
        }
    }

    MeterRegistry registry() {
        return registry;
    }
}
