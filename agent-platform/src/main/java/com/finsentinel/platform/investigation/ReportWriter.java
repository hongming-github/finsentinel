package com.finsentinel.platform.investigation;

import java.util.List;

/** Writes the case report from the specialists' findings (the Express step). Has no tools of its own. */
public interface ReportWriter {

    /**
     * @param revision the reviewer's objections to this run's previous report; null on the first pass
     */
    InvestigationResult write(CaseContext caseContext, AnalysisPlan plan, List<SpecialistReport> reports,
                              Revision revision);
}
