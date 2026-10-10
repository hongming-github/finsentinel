package com.finsentinel.common;

/**
 * Kafka topic names. Versioned so a breaking schema change gets a new topic: v2 carries Avro registered in the schema
 * registry (ADR 0018), where compatible changes evolve in place; v1 was JSON.
 */
public final class Topics {

    /** Avro {@code TransactionEvent}. */
    public static final String TRANSACTIONS = "txn.transactions.v2";
    /** The raw bytes of transaction messages that could not be read. */
    public static final String TRANSACTIONS_DLQ = "txn.transactions.dlq.v2";
    /** Avro {@code AlertEvent}. */
    public static final String ALERTS = "risk.alerts.v2";
    public static final String ALERTS_DLT = "risk.alerts.v2.DLT";
    /**
     * Avro {@code AccountBaselineEvent} from the daily batch job, keyed by tenant|account. Compacted: the latest
     * baseline per account is what matters, and a restarted job rebuilds its state from it.
     */
    public static final String ACCOUNT_BASELINES = "account.baselines.v1";
    /** Case lifecycle events from the platform's outbox (ready for decision, decided, failed). Still JSON. */
    public static final String CASE_EVENTS = "case.events.v1";

    private Topics() {
    }
}
