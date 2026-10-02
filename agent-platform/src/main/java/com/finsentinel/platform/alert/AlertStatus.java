package com.finsentinel.platform.alert;

public enum AlertStatus {
    /** Waiting for an investigation worker. */
    NEW,
    /** Claimed by a worker; returns to NEW if the claim times out. */
    INVESTIGATING,
    /** Case report written. */
    REPORTED,
    /** Gave up after the maximum number of attempts. */
    FAILED
}
