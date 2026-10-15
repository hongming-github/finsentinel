package com.finsentinel.platform.observability;

import io.micrometer.observation.ObservationPredicate;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Tracing set-up beyond what Spring Boot configures (ADR 0020). */
@Configuration(proxyBeanMethods = false)
class ObservabilityConfig {

    /** For spans the instrumentation cannot make itself: the analysis of a case, graph nodes, MCP calls. */
    @Bean
    Tracer platformTracer(ObjectProvider<OpenTelemetry> openTelemetry) {
        return openTelemetry.getIfAvailable(OpenTelemetry::noop).getTracer("finsentinel-platform");
    }

    /**
     * Scheduled polls (the case worker every few seconds, the outbox relay, the knowledge sync) would each start a
     * trace of their own, empty almost every time. The work they find is traced where it starts instead: an analysis
     * continues the trace of the alert that queued it.
     */
    @Bean
    ObservationPredicate noScheduledPollTraces() {
        return (name, context) -> !name.equals("tasks.scheduled.execution");
    }
}
