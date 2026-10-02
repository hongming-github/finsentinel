package com.finsentinel.platform.alert;

import com.finsentinel.common.Alert;

/**
 * An earlier alert on the same account together with what became of its case. "Closed as FALSE_POSITIVE by an
 * analyst last week" is evidence an investigator should weigh.
 */
public record PriorAlert(Alert alert, String caseStage, String caseDecision) {
}
