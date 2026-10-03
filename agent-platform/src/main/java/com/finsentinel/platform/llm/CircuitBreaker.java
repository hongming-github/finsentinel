package com.finsentinel.platform.llm;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Per provider and per instance: after {@code threshold} consecutive failures, calls skip the provider for
 * {@code openFor}, then one trial call decides. In memory on purpose: it saves each instance the wait on a provider it
 * has seen fail; it guards no shared state, so instances need not agree (unlike claims and budgets, which live in
 * Postgres).
 */
final class CircuitBreaker {

    private final int threshold;
    private final Duration openFor;
    private final Clock clock;
    private int consecutiveFailures;
    private Instant openUntil;

    CircuitBreaker(int threshold, Duration openFor, Clock clock) {
        this.threshold = threshold;
        this.openFor = openFor;
        this.clock = clock;
    }

    synchronized boolean allowsCall() {
        return openUntil == null || !clock.instant().isBefore(openUntil);
    }

    synchronized void success() {
        consecutiveFailures = 0;
        openUntil = null;
    }

    synchronized void failure() {
        consecutiveFailures++;
        if (consecutiveFailures >= threshold) {
            openUntil = clock.instant().plus(openFor);
        }
    }
}
