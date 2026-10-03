package com.finsentinel.platform.investigation;

/** Turns one case into a case report on its own (the single-agent analysis). The seam between graph and LLM. */
public interface CaseInvestigator {

    /**
     * @param revision the reviewer's objections to this run's previous report; null on the first pass
     */
    InvestigationResult investigate(CaseContext caseContext, Revision revision);

    default InvestigationResult investigate(CaseContext caseContext) {
        return investigate(caseContext, null);
    }
}
