package com.finsentinel.platform.investigation;

import com.finsentinel.platform.alert.StoredAlert;

/** Turns one alert into a case report. The seam between the work queue and the LLM. */
public interface CaseInvestigator {

    InvestigationResult investigate(StoredAlert alert);
}
