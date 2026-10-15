package com.finsentinel.telemetry;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;

import java.util.HashMap;
import java.util.Map;

/**
 * W3C trace context as the single {@value #HEADER} header that our Kafka messages carry (ADR 0020). Plain strings,
 * so callers can put it into Kafka headers, a Flink record or a database column.
 */
public final class TraceContext {

    public static final String HEADER = "traceparent";

    private static final W3CTraceContextPropagator W3C = W3CTraceContextPropagator.getInstance();
    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    };

    private TraceContext() {
    }

    /** The traceparent of the span current in {@code context}, or null when there is no valid span. */
    public static String traceparent(Context context) {
        Map<String, String> carrier = new HashMap<>();
        W3C.inject(context, carrier, Map::put);
        return carrier.get(HEADER);
    }

    /** A context whose parent is the span named by {@code traceparent}; the root context for null or garbage. */
    public static Context parent(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) {
            return Context.root();
        }
        return W3C.extract(Context.root(), Map.of(HEADER, traceparent), GETTER);
    }
}
