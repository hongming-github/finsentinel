package com.finsentinel.platform.analysis;

import com.alibaba.cloud.ai.graph.GraphLifecycleListener;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.finsentinel.platform.investigation.AnalysisPlan;
import com.finsentinel.platform.investigation.CaseContext;
import com.finsentinel.platform.investigation.InvestigationResult;
import com.finsentinel.platform.investigation.Review;
import com.finsentinel.platform.investigation.Specialist;
import com.finsentinel.platform.investigation.SpecialistReport;
import com.finsentinel.platform.llm.LlmUsage;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Keys of the analysis graph's state and their types. Every key has a declared type: checkpoints are stored as plain
 * JSON, and the types are how values come back as records rather than maps. Values are immutable records.
 *
 * <p>Each specialist writes its own key, so parallel branches never write the same key and need no reducer.
 */
final class CaseGraphState {

    static final String CASE = "case";
    static final String MODE = "mode";
    static final String PLAN = "plan";
    static final String INVESTIGATION = "investigation";
    static final String REVIEW = "review";
    /** Reviews done in this run; bounds the send-back loop. */
    static final String REVIEWS = "reviews";
    /** Cost of the sequential steps so far (investigator or writer, reviewer), every pass included. */
    static final String SPENT = "spent";

    static final Map<String, Class<?>> TYPES;

    static {
        Map<String, Class<?>> types = new HashMap<>(Map.of(
                CASE, CaseContext.class,
                MODE, AnalysisMode.class,
                PLAN, AnalysisPlan.class,
                INVESTIGATION, InvestigationResult.class,
                REVIEW, Review.class,
                REVIEWS, Integer.class,
                SPENT, LlmUsage.class,
                // Added by the framework to every run's input.
                GraphLifecycleListener.EXECUTION_ID_KEY, String.class));
        for (Specialist specialist : Specialist.values()) {
            types.put(key(specialist), SpecialistReport.class);
        }
        TYPES = Map.copyOf(types);
    }

    private CaseGraphState() {
    }

    static String key(Specialist specialist) {
        return specialist.name().toLowerCase(Locale.ROOT);
    }

    /** No reducers: every key has one writer at a time, and the last write wins. */
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

    static AnalysisPlan plan(OverAllState state) {
        return state.value(PLAN, AnalysisPlan.class).orElseThrow(() -> new IllegalStateException("no plan yet"));
    }

    static Optional<SpecialistReport> specialist(OverAllState state, Specialist specialist) {
        return state.value(key(specialist), SpecialistReport.class);
    }

    static Optional<InvestigationResult> investigation(OverAllState state) {
        return state.value(INVESTIGATION, InvestigationResult.class);
    }

    static Optional<Review> review(OverAllState state) {
        return state.value(REVIEW, Review.class);
    }

    static int reviews(OverAllState state) {
        return state.value(REVIEWS, Integer.class).orElse(0);
    }

    static LlmUsage spent(OverAllState state) {
        return state.value(SPENT, LlmUsage.class).orElse(LlmUsage.NONE);
    }
}
