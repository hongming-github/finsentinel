package com.finsentinel.contracts;

import com.finsentinel.common.Alert;
import com.finsentinel.common.AlertRule;
import com.finsentinel.common.Channel;
import com.finsentinel.common.Severity;
import com.finsentinel.common.Topics;
import com.finsentinel.common.Transaction;
import com.finsentinel.contracts.avro.AlertEvent;
import com.finsentinel.contracts.avro.TransactionEvent;

import java.math.RoundingMode;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * The Avro contracts of the data path and their mapping to the common records the code works with. Subjects follow
 * the topic-name strategy ({@code <topic>-value}).
 */
public final class Contracts {

    public static final String TRANSACTIONS_SUBJECT = Topics.TRANSACTIONS + "-value";
    public static final String ALERTS_SUBJECT = Topics.ALERTS + "-value";

    /**
     * Every subject and its schema, for registration. Compatibility is BACKWARD_TRANSITIVE: a new version must read
     * all earlier data, so consumers upgrade before producers.
     */
    public static final List<Subject> SUBJECTS = List.of(
            new Subject(TRANSACTIONS_SUBJECT, TransactionEvent.getClassSchema()),
            new Subject(ALERTS_SUBJECT, AlertEvent.getClassSchema()));

    public static final String COMPATIBILITY = "BACKWARD_TRANSITIVE";

    public record Subject(String name, org.apache.avro.Schema schema) {
    }

    private Contracts() {
    }

    public static TransactionEvent toAvro(Transaction t) {
        return TransactionEvent.newBuilder()
                .setTxnId(t.txnId()).setTenantId(t.tenantId()).setAccountId(t.accountId()).setCustomerId(t.customerId())
                // The schema fixes two decimals; more would lose money silently, so it fails instead.
                .setAmount(t.amount().setScale(2, RoundingMode.UNNECESSARY))
                .setCurrency(t.currency()).setMerchantId(t.merchantId()).setMerchantCategory(t.merchantCategory())
                .setChannel(com.finsentinel.contracts.avro.Channel.valueOf(t.channel().name()))
                .setCountry(t.country())
                .setEventTime(t.eventTime().truncatedTo(ChronoUnit.MICROS))
                .build();
    }

    public static Transaction fromAvro(TransactionEvent e) {
        return new Transaction(e.getTxnId(), e.getTenantId(), e.getAccountId(), e.getCustomerId(), e.getAmount(),
                e.getCurrency(), e.getMerchantId(), e.getMerchantCategory(), Channel.valueOf(e.getChannel().name()),
                e.getCountry(), e.getEventTime());
    }

    public static AlertEvent toAvro(Alert a) {
        return AlertEvent.newBuilder()
                .setAlertId(a.alertId()).setTenantId(a.tenantId()).setAccountId(a.accountId())
                .setCustomerId(a.customerId())
                .setRule(com.finsentinel.contracts.avro.AlertRule.valueOf(a.rule().name()))
                .setSeverity(com.finsentinel.contracts.avro.Severity.valueOf(a.severity().name()))
                .setReason(a.reason()).setTxnIds(List.copyOf(a.txnIds()))
                .setEventTime(a.eventTime().truncatedTo(ChronoUnit.MICROS))
                .setDetectedAt(a.detectedAt().truncatedTo(ChronoUnit.MICROS))
                .build();
    }

    public static Alert fromAvro(AlertEvent e) {
        return new Alert(e.getAlertId(), e.getTenantId(), e.getAccountId(), e.getCustomerId(),
                AlertRule.valueOf(e.getRule().name()), Severity.valueOf(e.getSeverity().name()), e.getReason(),
                List.copyOf(e.getTxnIds()), e.getEventTime(), e.getDetectedAt());
    }
}
