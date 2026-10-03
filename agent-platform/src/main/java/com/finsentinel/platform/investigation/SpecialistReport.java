package com.finsentinel.platform.investigation;

import com.finsentinel.platform.llm.LlmUsage;

import java.util.List;
import java.util.Set;

/**
 * A specialist's findings plus everything the later steps need without re-running it: the evidence ids its tools
 * returned (to ground the final report) and what it cost. Lives in the graph state, so it survives a restart.
 *
 * @param skipped true when the plan did not call for this specialist; no model call was made
 */
public record SpecialistReport(Specialist specialist, boolean skipped, String summary,
                               List<CaseReport.Finding> findings, List<CaseReport.RegulatoryNote> regulatoryNotes,
                               List<ToolCall> toolCalls, Set<String> seenTxnIds, Set<String> seenChunkIds,
                               LlmUsage usage) {

    public SpecialistReport {
        findings = List.copyOf(findings);
        regulatoryNotes = List.copyOf(regulatoryNotes);
        toolCalls = List.copyOf(toolCalls);
        seenTxnIds = Set.copyOf(seenTxnIds);
        seenChunkIds = Set.copyOf(seenChunkIds);
    }

    public static SpecialistReport skipped(Specialist specialist, String reason) {
        return new SpecialistReport(specialist, true, reason, List.of(), List.of(), List.of(), Set.of(), Set.of(),
                LlmUsage.NONE);
    }
}
