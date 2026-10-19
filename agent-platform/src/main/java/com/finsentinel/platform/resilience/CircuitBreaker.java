package com.finsentinel.platform.resilience;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Per dependency (a model provider, an outside system) and per instance: after {@code threshold} consecutive failures,
 * calls skip the dependency for {@code openFor}, then one trial call decides. In memory on purpose: it saves each
 * instance the wait on a dependency it has seen fail; it guards no shared state, so instances need not agree (unlike
 * claims and budgets, which live in Postgres).
 */
public final class CircuitBreaker {

    private final int threshold;
    private final Duration openFor;
    private final Clock clock;
    private int consecutiveFailures;
    private Instant openUntil;

    public CircuitBreaker(int threshold, Duration openFor, Clock clock) {
        this.threshold = threshold;
        this.openFor = openFor;
        this.clock = clock;
    }

    public synchronized boolean allowsCall() {
        return openUntil == null || !clock.instant().isBefore(openUntil);
    }

    public synchronized void success() {
        consecutiveFailures = 0;
        openUntil = null;
    }

    public synchronized void failure() {
        consecutiveFailures++;
        if (consecutiveFailures >= threshold) {
            openUntil = clock.instant().plus(openFor);
        }
    }
}
