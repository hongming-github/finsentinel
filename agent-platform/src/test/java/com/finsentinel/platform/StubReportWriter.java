package com.finsentinel.platform;

import com.finsentinel.platform.investigation.AnalysisPlan;
import com.finsentinel.platform.investigation.CaseContext;
import com.finsentinel.platform.investigation.InvestigationResult;
import com.finsentinel.platform.investigation.ReportWriter;
import com.finsentinel.platform.investigation.Revision;
import com.finsentinel.platform.investigation.SpecialistReport;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** Replaces the LLM report writer in tests. Remembers what it was given; reset() restores the default. */
@TestComponent
@Primary
public class StubReportWriter implements ReportWriter {

    private volatile Function<CaseContext, InvestigationResult> behaviour = StubCaseInvestigator::defaultResult;
    private volatile List<SpecialistReport> lastReports;
    private volatile AnalysisPlan lastPlan;
    private volatile Revision lastRevision;
    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public InvestigationResult write(CaseContext caseContext, AnalysisPlan plan, List<SpecialistReport> reports,
                                     Revision revision) {
        calls.incrementAndGet();
        lastPlan = plan;
        lastReports = reports;
        lastRevision = revision;
        return behaviour.apply(caseContext);
    }

    public void respondWith(Function<CaseContext, InvestigationResult> behaviour) {
        this.behaviour = behaviour;
    }

    public int calls() {
        return calls.get();
    }

    public List<SpecialistReport> lastReports() {
        return lastReports;
    }

    public AnalysisPlan lastPlan() {
        return lastPlan;
    }

    public Revision lastRevision() {
        return lastRevision;
    }

    public void reset() {
        behaviour = StubCaseInvestigator::defaultResult;
        lastReports = null;
        lastPlan = null;
        lastRevision = null;
        calls.set(0);
    }
}
