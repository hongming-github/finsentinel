package com.finsentinel.platform.cases;

import com.finsentinel.platform.security.Tenancy;
import com.finsentinel.common.Alert;
import com.finsentinel.platform.observability.Traces;
import com.finsentinel.platform.alert.AlertRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

/**
 * Files an incoming alert under the account's open case, opening or re-opening the case as needed, in one
 * transaction. Idempotent: a redelivered alert changes nothing.
 */
@Component
public class CaseIntake {

    private final AlertRepository alerts;
    private final CaseRepository cases;
    private final TransactionTemplate transactions;

    CaseIntake(AlertRepository alerts, CaseRepository cases, TransactionTemplate transactions) {
        this.alerts = alerts;
        this.cases = cases;
        this.transactions = transactions;
    }

    /**
     * Runs in the alert's tenant scope: the alert names its bank, and row-level security holds every write to it.
     *
     * @return the case the alert was filed under, or empty if it was a redelivery
     */
    public Optional<String> accept(Alert alert) {
        return Tenancy.call(alert.tenantId(), () -> transactions.execute(status -> {
            if (alerts.exists(alert.alertId())) {
                return Optional.<String>empty();
            }
            // The listener's consumer span: the alert's trace, continued here and, after the queue, by the analysis.
            String trace = Traces.current();
            String caseId = cases.attach(alert, trace);
            if (!alerts.insertIfAbsent(alert, caseId, trace)) {
                // A concurrent delivery of the same alert won the insert: undo our case changes.
                status.setRollbackOnly();
                return Optional.<String>empty();
            }
            return Optional.of(caseId);
        }));
    }
}
