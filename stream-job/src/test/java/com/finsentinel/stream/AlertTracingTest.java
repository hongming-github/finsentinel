package com.finsentinel.stream;

import com.finsentinel.common.Alert;
import com.finsentinel.common.AlertRule;
import com.finsentinel.common.Severity;
import com.finsentinel.telemetry.Telemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AlertTracingTest {

    private static final String TXN_TRACE = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
    private static final Instant EVENT = Instant.parse("2026-10-15T08:00:00Z");
    private static final Alert ALERT = new Alert("a-1", "straits", "acc-1", "c-1", AlertRule.LARGE_AMOUNT,
            Severity.MEDIUM, "30x the median", List.of("t-1"), EVENT, EVENT.plusMillis(850));

    @Test
    void anAlertGetsOneSpanInTheTriggeringTransactionsTraceAndCarriesItsContext() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();

        String published = new AlertTracing(Telemetry.withExporter("stream-job", exporter)).detected(ALERT, TXN_TRACE);

        SpanData span = exporter.getFinishedSpanItems().getFirst();
        assertThat(span.getName()).isEqualTo("detect LARGE_AMOUNT");
        assertThat(span.getTraceId()).isEqualTo("0af7651916cd43dd8448eb211c80319c");
        assertThat(span.getParentSpanId()).isEqualTo("b7ad6b7169203331");
        assertThat(span.getAttributes().get(AttributeKey.longKey("finsentinel.detection_lag_ms"))).isEqualTo(850L);
        assertThat(published).as("the platform continues from the detection span")
                .contains(span.getTraceId()).contains(span.getSpanId());
    }

    @Test
    void withTracingOffTheTransactionsContextIsForwardedAndAnUntracedTransactionStaysUntraced() {
        AlertTracing off = new AlertTracing(Telemetry.start("stream-job", ""));

        assertThat(off.detected(ALERT, TXN_TRACE)).isEqualTo(TXN_TRACE);
        assertThat(off.detected(ALERT, null)).isNull();
    }
}
