package com.finsentinel.platform.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;

import java.util.HashMap;
import java.util.Map;

/**
 * W3C trace context as a {@code traceparent} string (ADR 0020), for the places where a trace has to survive a gap the
 * instrumentation cannot see: a case waiting in the queue (stored with the case and its alerts) and an MCP request
 * sent from another thread.
 */
public final class Traces {

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

    private Traces() {
    }

    /** The traceparent of the current span, or null when nothing is being traced. */
    public static String current() {
        return of(Context.current());
    }

    public static String of(Context context) {
        Map<String, String> carrier = new HashMap<>();
        W3C.inject(context, carrier, Map::put);
        return carrier.get(HEADER);
    }

    /** A context whose parent is the span named by {@code traceparent}; the root context for null or garbage. */
    public static Context context(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) {
            return Context.root();
        }
        return W3C.extract(Context.root(), Map.of(HEADER, traceparent), GETTER);
    }

    /** The trace id (32 hex digits) a {@code traceparent} names, for links into Tempo; null for null or garbage. */
    public static String traceId(String traceparent) {
        SpanContext span = Span.fromContext(context(traceparent)).getSpanContext();
        return span.isValid() ? span.getTraceId() : null;
    }
}
