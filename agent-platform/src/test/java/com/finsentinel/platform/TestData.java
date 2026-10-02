package com.finsentinel.platform;

import com.finsentinel.common.Alert;
import com.finsentinel.common.AlertRule;
import com.finsentinel.common.Channel;
import com.finsentinel.common.Severity;
import com.finsentinel.common.Transaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/** Builders for test fixtures. Each test uses its own random tenant so shared tables do not interfere. */
public final class TestData {

    private TestData() {
    }

    public static String uniqueTenant() {
        return "t-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static Transaction txn(String tenant, String account, String amount, Channel channel, String country,
                                  Instant at) {
        return txn(tenant, account, amount, channel, country, "m-1", at);
    }

    public static Transaction txn(String tenant, String account, String amount, Channel channel, String country,
                                  String counterparty, Instant at) {
        return new Transaction(UUID.randomUUID().toString(), tenant, account, "cus-" + account,
                new BigDecimal(amount).setScale(2), "SGD", counterparty, "5411", channel, country,
                at.truncatedTo(ChronoUnit.MICROS));
    }

    public static Alert alert(String tenant, String account, AlertRule rule, Severity severity, List<String> txnIds,
                              Instant eventTime) {
        Instant at = eventTime.truncatedTo(ChronoUnit.MICROS);
        return new Alert(UUID.randomUUID().toString(), tenant, account, "cus-" + account, rule, severity,
                "test " + rule, txnIds, at, at);
    }
}
