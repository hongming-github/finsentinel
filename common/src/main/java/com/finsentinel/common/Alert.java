package com.finsentinel.common;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A suspicious-activity signal raised by the stream job and investigated by the agent platform.
 *
 * @param alertId    deterministic id, see {@link #idFor}; replays produce the same id so consumers can dedupe
 * @param txnIds     the transactions that triggered the rule, oldest first
 * @param eventTime  event time of the transaction that tipped the rule
 * @param detectedAt wall-clock time the stream job raised the alert
 */
public record Alert(
        String alertId,
        String tenantId,
        String accountId,
        String customerId,
        AlertRule rule,
        Severity severity,
        String reason,
        List<String> txnIds,
        Instant eventTime,
        Instant detectedAt) {

    /**
     * Derives the alert id from what triggered it rather than generating a random one, so the
     * at-least-once Kafka sink can resend an alert without the platform opening a second case.
     */
    public static String idFor(String tenantId, String accountId, AlertRule rule, String triggeringTxnId) {
        String natural = tenantId + "|" + accountId + "|" + rule + "|" + triggeringTxnId;
        return UUID.nameUUIDFromBytes(natural.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
