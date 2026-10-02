package com.finsentinel.platform;

import com.finsentinel.platform.alert.StoredAlert;
import com.finsentinel.platform.investigation.CaseInvestigator;
import com.finsentinel.platform.investigation.CaseReport;
import com.finsentinel.platform.investigation.Disposition;
import com.finsentinel.platform.investigation.InvestigationResult;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.function.Function;

/** Replaces the LLM investigator in tests. Behaviour is swappable per test; reset() restores the default. */
@TestComponent
@Primary
public class StubCaseInvestigator implements CaseInvestigator {

    private volatile Function<StoredAlert, InvestigationResult> behaviour = StubCaseInvestigator::defaultResult;

    @Override
    public InvestigationResult investigate(StoredAlert alert) {
        return behaviour.apply(alert);
    }

    public void respondWith(Function<StoredAlert, InvestigationResult> behaviour) {
        this.behaviour = behaviour;
    }

    public void reset() {
        this.behaviour = StubCaseInvestigator::defaultResult;
    }

    public static InvestigationResult defaultResult(StoredAlert alert) {
        CaseReport report = new CaseReport(Disposition.NEEDS_REVIEW, 50, "stub report",
                List.of(new CaseReport.Finding("stub finding", alert.alert().txnIds())), List.of("stub action"), List.of());
        return new InvestigationResult(report, List.of(), List.of(), "stub-model", 10, 5, null, 1, 1);
    }
}
