package com.finsentinel.platform.investigation;

/** Turns one case into a case report. The seam between the case lifecycle and the LLM. */
public interface CaseInvestigator {

    InvestigationResult investigate(CaseContext caseContext);
}
