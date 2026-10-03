package com.finsentinel.platform.analysis;

/** How a case is analysed. Both modes end with the same reviewer and send-back loop. */
public enum AnalysisMode {
    /** One investigator with every tool (the phase 2 agent). The baseline. */
    SINGLE,
    /** Plan, parallel specialists, a report writer without tools (agentUniverse's PEER shape). */
    MULTI
}
