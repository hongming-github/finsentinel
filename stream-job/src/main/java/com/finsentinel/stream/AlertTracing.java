package com.finsentinel.stream;

import com.finsentinel.common.Alert;
import com.finsentinel.common.Topics;
import com.finsentinel.telemetry.Telemetry;
import com.finsentinel.telemetry.TraceContext;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;

import java.time.Duration;

/**
 * One span per alert, not per transaction (ADR 0020): the detection is what an investigation needs to see, and alerts
 * are about one in a thousand transactions, so tracing costs scale with alerts, not with throughput. The span is a
 * child of the transaction that triggered the alert, and the alert carries the span's context to the platform.
 */
final class AlertTracing {

    private final Telemetry telemetry;

    AlertTracing(Telemetry telemetry) {
        this.telemetry = telemetry;
    }

    /** @return the trace context to publish the alert with */
    String detected(Alert alert, String transactionTraceparent) {
        if (!telemetry.enabled()) {
            return transactionTraceparent;
        }
        Span span = telemetry.tracer().spanBuilder("detect " + alert.rule())
                .setParent(TraceContext.parent(transactionTraceparent))
                .setSpanKind(SpanKind.CONSUMER)
                .setAttribute("messaging.system", "kafka")
                .setAttribute("messaging.destination.name", Topics.TRANSACTIONS)
                .setAttribute("finsentinel.tenant", alert.tenantId())
                .setAttribute("finsentinel.alert_id", alert.alertId())
                .setAttribute("finsentinel.rule", alert.rule().name())
                .setAttribute("finsentinel.severity", alert.severity().name())
                // Event time to detection: Kafka lag plus Flink processing, the stream's share of alert latency.
                .setAttribute("finsentinel.detection_lag_ms",
                        Duration.between(alert.eventTime(), alert.detectedAt()).toMillis())
                .startSpan();
        span.end();
        return TraceContext.traceparent(Context.root().with(span));
    }
}
