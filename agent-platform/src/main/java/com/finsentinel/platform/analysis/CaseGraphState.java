package com.finsentinel.platform.analysis;

import com.finsentinel.platform.investigation.CaseContext;
import com.finsentinel.platform.investigation.InvestigationResult;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;

import java.util.Map;
import java.util.Optional;

/**
 * State of the analysis graph. Every key has a declared type: checkpoints are stored as plain JSON, and the types
 * are how values come back as records rather than maps. Values are immutable records.
 */
public final class CaseGraphState extends AgentState {

    static final String CASE = "case";
    static final String INVESTIGATION = "investigation";
    static final String REVIEW = "review";

    static final Map<String, Class<?>> TYPES = Map.of(
            CASE, CaseContext.class,
            INVESTIGATION, InvestigationResult.class,
            REVIEW, Review.class);

    /** No reducers yet: for every key the last write wins. */
    static final Map<String, Channel<?>> CHANNELS = Map.of();

    public CaseGraphState(Map<String, Object> data) {
        super(data);
    }

    CaseContext caseContext() {
        return this.<CaseContext>value(CASE).orElseThrow(() -> new IllegalStateException("graph started without a case"));
    }

    Optional<InvestigationResult> investigation() {
        return value(INVESTIGATION);
    }

    Optional<Review> review() {
        return value(REVIEW);
    }
}
