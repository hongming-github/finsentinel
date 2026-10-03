package com.finsentinel.platform.investigation;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/** What a specialist agent must return. The descriptions become the JSON Schema the model is constrained to. */
public record SpecialistFindings(
        @JsonPropertyDescription("Two or three sentences for the lead investigator: what you found and what it means "
                + "for each alert")
        String summary,
        @JsonPropertyDescription("Facts you established, each backed by transaction ids exactly as returned by tools; "
                + "an empty id list when a fact rests on other data (a profile, a security event, a screening result)")
        List<CaseReport.Finding> findings,
        @JsonPropertyDescription("Regulatory obligations, each citing chunkIds exactly as returned by "
                + "searchRegulations. Only the regulations specialist fills this; everyone else returns an empty list")
        List<CaseReport.RegulatoryNote> regulatoryNotes) {
}
