package com.finsentinel.platform.analysis;

import com.finsentinel.platform.investigation.CaseContext;
import com.finsentinel.platform.investigation.InvestigationResult;
import org.springframework.stereotype.Component;

/**
 * Placeholder until the LLM reviewer (phase 4 step C): the report has already passed {@code CaseReportValidator},
 * so it is approved as is. Exists so the graph has its final shape and its checkpoints span more than one node.
 */
@Component
class ApprovingReviewer implements CaseReviewer {

    @Override
    public Review review(CaseContext caseContext, InvestigationResult investigation) {
        return new Review(true, "validated report, no reviewer configured");
    }
}
