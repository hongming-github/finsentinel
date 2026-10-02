package com.finsentinel.platform.investigation;

import com.finsentinel.common.Alert;
import com.finsentinel.common.AlertRule;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * What one analysis run works on: a case and every alert filed under it.
 *
 * @param run         the case's analysis run this context was built for
 * @param analystNote set when an analyst sent the case back for another analysis
 */
public record CaseContext(String caseId, String tenantId, String accountId, String customerId, List<Alert> alerts,
                          int run, String analystNote) {

    public CaseContext {
        if (alerts.isEmpty()) {
            throw new IllegalArgumentException("a case has at least one alert");
        }
        alerts = List.copyOf(alerts);
    }

    /** Every transaction behind any of the case's alerts. */
    public List<String> txnIds() {
        return alerts.stream().flatMap(a -> a.txnIds().stream()).distinct().toList();
    }

    /** Start of the suspicious activity: baselines are computed strictly before it. */
    public Instant firstEventTime() {
        return alerts.stream().map(Alert::eventTime).min(Comparator.naturalOrder()).orElseThrow();
    }

    public List<AlertRule> rules() {
        return alerts.stream().map(Alert::rule).distinct().sorted().toList();
    }
}
