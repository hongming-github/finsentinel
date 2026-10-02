package com.finsentinel.platform;

import com.finsentinel.platform.investigation.CaseContext;
import com.finsentinel.platform.investigation.CaseInvestigator;
import com.finsentinel.platform.investigation.CaseReport;
import com.finsentinel.platform.investigation.Disposition;
import com.finsentinel.platform.investigation.InvestigationResult;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.function.Function;

/**
 * Replaces the LLM investigator in tests. Behaviour is swappable per test; reset() restores the default.
 * Remembers the last context it was given, so tests can check what reached the agent.
 */
@TestComponent
@Primary
public class StubCaseInvestigator implements CaseInvestigator {

    private volatile Function<CaseContext, InvestigationResult> behaviour = StubCaseInvestigator::defaultResult;
    private volatile CaseContext lastContext;

    @Override
    public InvestigationResult investigate(CaseContext caseContext) {
        lastContext = caseContext;
        return behaviour.apply(caseContext);
    }

    public void respondWith(Function<CaseContext, InvestigationResult> behaviour) {
        this.behaviour = behaviour;
    }

    public CaseContext lastContext() {
        return lastContext;
    }

    public void reset() {
        this.behaviour = StubCaseInvestigator::defaultResult;
        this.lastContext = null;
    }

    public static InvestigationResult defaultResult(CaseContext caseContext) {
        CaseReport report = new CaseReport(Disposition.NEEDS_REVIEW, 50, "stub report",
                List.of(new CaseReport.Finding("stub finding", caseContext.txnIds())), List.of("stub action"), List.of());
        return new InvestigationResult(report, List.of(), List.of(), "stub-model", 10, 5, null, 1, 1);
    }
}
