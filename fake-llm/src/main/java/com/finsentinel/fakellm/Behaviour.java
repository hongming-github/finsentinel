package com.finsentinel.fakellm;

/**
 * How one simulated provider answers chat requests, changeable at runtime through {@code /admin/behaviour} so a chaos
 * drill can slow it down or make it fail without a restart.
 *
 * @param latencyMs   added to every chat answer
 * @param jitterMs    up to this much more, uniformly at random
 * @param errorStatus HTTP status of an injected failure: 429 (rate limit), 500 or 503
 * @param errorRate   share of chat requests that fail with {@code errorStatus}, 0 to 1
 */
record Behaviour(long latencyMs, long jitterMs, int errorStatus, double errorRate) {

    static final Behaviour NORMAL = new Behaviour(0, 0, 500, 0);

    Behaviour {
        if (latencyMs < 0 || jitterMs < 0) {
            throw new IllegalArgumentException("latencyMs and jitterMs must not be negative");
        }
        if (errorStatus != 429 && errorStatus != 500 && errorStatus != 503) {
            throw new IllegalArgumentException("errorStatus must be 429, 500 or 503");
        }
        if (!(errorRate >= 0 && errorRate <= 1)) {
            throw new IllegalArgumentException("errorRate must be between 0 and 1");
        }
    }
}
