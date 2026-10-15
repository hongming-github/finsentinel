package com.finsentinel.telemetry;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Tracing for one process. With an OTLP endpoint, spans are batched and exported over OTLP/HTTP; without one,
 * tracing is off: no spans, and callers forward the trace context they received unchanged, so a downstream service
 * still continues the upstream trace.
 */
public final class Telemetry implements AutoCloseable {

    private static final Map<String, Telemetry> SHARED = new ConcurrentHashMap<>();

    private final OpenTelemetry openTelemetry;
    private final SdkTracerProvider provider;
    private final Tracer tracer;

    private Telemetry(OpenTelemetry openTelemetry, SdkTracerProvider provider, String instrumentation) {
        this.openTelemetry = openTelemetry;
        this.provider = provider;
        this.tracer = openTelemetry.getTracer(instrumentation);
    }

    /**
     * @param otlpEndpoint base URL of an OTLP/HTTP receiver (e.g. {@code http://localhost:4318}); blank = off
     */
    public static Telemetry start(String serviceName, String otlpEndpoint) {
        if (otlpEndpoint == null || otlpEndpoint.isBlank()) {
            return new Telemetry(OpenTelemetry.noop(), null, serviceName);
        }
        String base = otlpEndpoint.endsWith("/") ? otlpEndpoint.substring(0, otlpEndpoint.length() - 1) : otlpEndpoint;
        return withProcessor(serviceName, BatchSpanProcessor.builder(OtlpHttpSpanExporter.builder()
                .setEndpoint(base + "/v1/traces").setTimeout(Duration.ofSeconds(10)).build()).build());
    }

    /** One instance per service and endpoint in a JVM, closed when the JVM exits (Flink tasks share a JVM). */
    public static Telemetry shared(String serviceName, String otlpEndpoint) {
        return SHARED.computeIfAbsent(serviceName + "|" + otlpEndpoint, key -> {
            Telemetry telemetry = start(serviceName, otlpEndpoint);
            Runtime.getRuntime().addShutdownHook(new Thread(telemetry::close, "telemetry-flush"));
            return telemetry;
        });
    }

    /** For tests: spans go straight to the given exporter. */
    public static Telemetry withExporter(String serviceName, SpanExporter exporter) {
        return withProcessor(serviceName, io.opentelemetry.sdk.trace.export.SimpleSpanProcessor.create(exporter));
    }

    private static Telemetry withProcessor(String serviceName, SpanProcessor processor) {
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .setResource(Resource.getDefault().merge(Resource.create(Attributes.of(
                        io.opentelemetry.api.common.AttributeKey.stringKey("service.name"), serviceName))))
                .addSpanProcessor(processor)
                .build();
        return new Telemetry(OpenTelemetrySdk.builder().setTracerProvider(provider).build(), provider, serviceName);
    }

    public boolean enabled() {
        return provider != null;
    }

    public Tracer tracer() {
        return tracer;
    }

    public OpenTelemetry openTelemetry() {
        return openTelemetry;
    }

    @Override
    public void close() {
        if (provider != null) {
            provider.forceFlush().join(10, TimeUnit.SECONDS);
            provider.shutdown().join(10, TimeUnit.SECONDS);
        }
    }
}
