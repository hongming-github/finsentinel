package com.finsentinel.platform.investigation;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * What the investigator agent must return. The field descriptions become the JSON Schema the model is
 * constrained to (OpenAI structured outputs), so they are part of the prompt.
 */
public record CaseReport(
        @JsonPropertyDescription("Overall judgement for the analyst")
        Disposition disposition,
        @JsonPropertyDescription("0 = certainly legitimate, 100 = certainly fraud")
        int riskScore,
        @JsonPropertyDescription("Two or three sentences an analyst can read in ten seconds")
        String summary,
        @JsonPropertyDescription("Facts that support the disposition, each backed by transaction ids")
        List<Finding> findings,
        @JsonPropertyDescription("Concrete next steps for the analyst, most important first")
        List<String> recommendedActions,
        @JsonPropertyDescription("Regulatory obligations or expectations that bear on this case, each citing the "
                + "passages it relies on. Empty if no regulation was consulted")
        List<RegulatoryNote> regulatoryNotes) {

    public record RegulatoryNote(
            @JsonPropertyDescription("What the regulation requires or expects, as it applies to this case")
            String statement,
            @JsonPropertyDescription("chunkIds of the passages, exactly as returned by searchRegulations")
            List<String> sourceChunkIds) {
    }

    public record Finding(
            @JsonPropertyDescription("One factual statement")
            String statement,
            @JsonPropertyDescription("Ids of transactions, exactly as returned by tools, that support the statement")
            List<String> evidenceTxnIds) {
    }
}
