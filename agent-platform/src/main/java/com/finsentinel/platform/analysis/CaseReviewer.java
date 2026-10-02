package com.finsentinel.platform.analysis;

import com.finsentinel.platform.investigation.CaseContext;
import com.finsentinel.platform.investigation.InvestigationResult;

/** Checks the investigator's report before it reaches an analyst. The last node of the analysis graph. */
public interface CaseReviewer {

    Review review(CaseContext caseContext, InvestigationResult investigation);
}
