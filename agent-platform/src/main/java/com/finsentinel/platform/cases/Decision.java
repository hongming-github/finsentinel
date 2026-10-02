package com.finsentinel.platform.cases;

public enum Decision {
    CONFIRMED_FRAUD,
    FALSE_POSITIVE,
    /** Send the case back to the agents with the analyst's note. */
    REQUEST_REANALYSIS;

    CaseStage nextStage() {
        return this == REQUEST_REANALYSIS ? CaseStage.ANALYSIS : CaseStage.CLOSED;
    }
}
