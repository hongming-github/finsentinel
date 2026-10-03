package com.finsentinel.platform.analysis;

import com.alibaba.cloud.ai.graph.GraphLifecycleListener;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.finsentinel.platform.investigation.CaseContext;
import com.finsentinel.platform.investigation.InvestigationResult;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Keys of the analysis graph's state and their types. Every key has a declared type: checkpoints are stored as plain
 * JSON, and the types are how values come back as records rather than maps. Values are immutable records.
 */
final class CaseGraphState {

    static final String CASE = "case";
    static final String INVESTIGATION = "investigation";
    static final String REVIEW = "review";

    static final Map<String, Class<?>> TYPES = Map.of(
            CASE, CaseContext.class,
            INVESTIGATION, InvestigationResult.class,
            REVIEW, Review.class,
            // Added by the framework to every run's input.
            GraphLifecycleListener.EXECUTION_ID_KEY, String.class);

    private CaseGraphState() {
    }

    /** No reducers yet: for every key the last write wins. */
    static KeyStrategyFactory keyStrategies() {
        return () -> {
            Map<String, KeyStrategy> strategies = new HashMap<>();
            TYPES.keySet().forEach(key -> strategies.put(key, new ReplaceStrategy()));
            return strategies;
        };
    }

    static CaseContext caseContext(OverAllState state) {
        return state.value(CASE, CaseContext.class)
                .orElseThrow(() -> new IllegalStateException("graph started without a case"));
    }

    static Optional<InvestigationResult> investigation(OverAllState state) {
        return state.value(INVESTIGATION, InvestigationResult.class);
    }

    static Optional<Review> review(OverAllState state) {
        return state.value(REVIEW, Review.class);
    }
}
