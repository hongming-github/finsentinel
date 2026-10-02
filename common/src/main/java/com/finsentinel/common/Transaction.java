package com.finsentinel.common;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A payment event as published by a tenant's core banking system.
 *
 * @param tenantId   the bank this event belongs to; every downstream read is scoped by it
 * @param merchantId the counterparty: the merchant for card payments, the payee for transfers
 * @param country    ISO 3166-1 alpha-2 country of the terminal or counterparty
 * @param eventTime  when the payment happened (event time, not ingestion time)
 */
public record Transaction(
        String txnId,
        String tenantId,
        String accountId,
        String customerId,
        BigDecimal amount,
        String currency,
        String merchantId,
        String merchantCategory,
        Channel channel,
        String country,
        Instant eventTime) {

    /** Partition key: keeps one account's events ordered within a tenant. */
    public String accountKey() {
        return tenantId + "|" + accountId;
    }
}
