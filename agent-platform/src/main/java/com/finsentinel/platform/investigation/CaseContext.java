package com.finsentinel.platform.investigation;

import java.util.Set;
import java.util.Arrays;
import com.finsentinel.platform.systems.BankSystem;
import com.finsentinel.common.Alert;
import com.finsentinel.common.AlertRule;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * What one analysis run works on: a case and every alert filed under it.
 *
 * @param run              the case's analysis run this context was built for
 * @param analystNote      set when an analyst sent the case back for another analysis
 * @param connectedSystems the outside systems the case's bank has connected; the agents get only their tools. Missing
 *                         (a checkpoint from before tenant permissions) means none: permissions fail closed
 * @param missingTxnIds    transactions named by the alerts that the platform had not stored when the run began (the
 *                         evidence wait ran out, ADR 0025); the brief names them and the case cannot be cleared while
 *                         no tool has returned them. Missing (a checkpoint from before the wait) means none
 */
public record CaseContext(String caseId, String tenantId, String accountId, String customerId, List<Alert> alerts,
                          int run, String analystNote, Set<BankSystem> connectedSystems, Set<String> missingTxnIds) {

    public CaseContext {
        if (alerts.isEmpty()) {
            throw new IllegalArgumentException("a case has at least one alert");
        }
        alerts = List.copyOf(alerts);
        connectedSystems = connectedSystems == null ? Set.of() : Set.copyOf(connectedSystems);
        missingTxnIds = missingTxnIds == null ? Set.of() : Set.copyOf(missingTxnIds);
    }

    /** A case whose alerts' transactions are all stored. */
    public CaseContext(String caseId, String tenantId, String accountId, String customerId, List<Alert> alerts,
                       int run, String analystNote, Set<BankSystem> connectedSystems) {
        this(caseId, tenantId, accountId, customerId, alerts, run, analystNote, connectedSystems, Set.of());
    }

    /** Systems the bank has not connected, in a stable order (they are named in the brief). */
    public List<BankSystem> unconnectedSystems() {
        return Arrays.stream(BankSystem.values()).filter(s -> !connectedSystems.contains(s)).toList();
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
