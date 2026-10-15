package com.finsentinel.telemetry;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TraceContextTest {

    @Test
    void aTraceparentRoundTripsAndContinuesTheTrace() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        Telemetry telemetry = Telemetry.withExporter("test", exporter);

        Span upstream = telemetry.tracer().spanBuilder("upstream").startSpan();
        String header = TraceContext.traceparent(Context.root().with(upstream));
        upstream.end();
        telemetry.tracer().spanBuilder("downstream").setParent(TraceContext.parent(header)).startSpan().end();

        // Flags: sampled (0x01), and since W3C Trace Context level 2 also "random trace id" (0x02).
        assertThat(header).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
        assertThat(Integer.parseInt(header.substring(53), 16) & 1).as("sampled").isEqualTo(1);
        SpanData up = exporter.getFinishedSpanItems().get(0);
        SpanData down = exporter.getFinishedSpanItems().get(1);
        assertThat(down.getTraceId()).isEqualTo(up.getTraceId());
        assertThat(down.getParentSpanId()).isEqualTo(up.getSpanId());
    }

    @Test
    void missingOrBrokenContextStartsANewTraceAndNothingCurrentGivesNoHeader() {
        assertThat(Span.fromContext(TraceContext.parent(null)).getSpanContext().isValid()).isFalse();
        assertThat(Span.fromContext(TraceContext.parent("not-a-traceparent")).getSpanContext().isValid()).isFalse();
        assertThat(TraceContext.traceparent(Context.root())).isNull();
    }

    @Test
    void withoutAnEndpointTracingIsOff() {
        Telemetry off = Telemetry.start("test", "");

        assertThat(off.enabled()).isFalse();
        assertThat(off.tracer().spanBuilder("x").startSpan().getSpanContext().isValid()).isFalse();
    }
}
