package com.finsentinel.common;

/** Kafka topic names. Versioned so a breaking schema change gets a new topic. */
public final class Topics {

    public static final String TRANSACTIONS = "txn.transactions.v1";
    public static final String TRANSACTIONS_DLQ = "txn.transactions.dlq.v1";
    public static final String ALERTS = "risk.alerts.v1";
    public static final String ALERTS_DLT = "risk.alerts.v1.DLT";
    /** Case lifecycle events from the platform's outbox (ready for decision, decided, failed). */
    public static final String CASE_EVENTS = "case.events.v1";

    private Topics() {
    }
}
