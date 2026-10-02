package com.finsentinel.platform.cases;

public enum CaseStage {
    /** Waiting for or undergoing agent analysis; the only stage workers claim. */
    ANALYSIS,
    /** Analysis done; an analyst must decide. Never claimed by workers: the human-in-the-loop gate. */
    AWAITING_DECISION,
    /** Analysis gave up after the maximum attempts; an analyst handles it like AWAITING_DECISION. */
    FAILED,
    /** Decided. A new alert on the account opens a new case. */
    CLOSED
}
