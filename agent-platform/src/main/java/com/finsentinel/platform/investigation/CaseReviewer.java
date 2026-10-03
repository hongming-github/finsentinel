package com.finsentinel.platform.investigation;

import java.util.List;

/** Checks a report before it reaches an analyst, and can send it back. The last step of the analysis graph. */
public interface CaseReviewer {

    /**
     * @param evidence the specialists' reports the report was written from; empty for the single-agent analysis
     */
    Review review(CaseContext caseContext, InvestigationResult investigation, List<SpecialistReport> evidence);
}
