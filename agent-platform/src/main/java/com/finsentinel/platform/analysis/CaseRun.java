package com.finsentinel.platform.analysis;

import com.alibaba.cloud.ai.graph.RunnableConfig;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One analysis run of a case as seen by the worker running it: the graph thread it owns and the claim that fences
 * its writes. Travels to the checkpoint saver in the {@link RunnableConfig} metadata.
 *
 * @param tenantId  the case's bank: the saver runs on the graph's pool threads and sets this scope itself
 * @param claimedAt the worker's claim on the case; checkpoints are written only while the case still carries it
 */
record CaseRun(UUID caseId, String tenantId, int run, Instant claimedAt) {

    static final String METADATA_KEY = "finsentinel.caseRun";

    CaseRun {
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(claimedAt, "claimedAt");
    }

    /** Graph thread id: a new run of the same case starts a new graph thread. */
    String threadId() {
        return caseId + ":" + run;
    }

    RunnableConfig config() {
        return RunnableConfig.builder()
                .threadId(threadId())
                .addMetadata(METADATA_KEY, this)
                .defaultParallelExecutor(CaseAnalysisGraph.NODE_EXECUTOR)
                .build();
    }

    static CaseRun of(RunnableConfig config) {
        return config.metadata(METADATA_KEY)
                .map(CaseRun.class::cast)
                .orElseThrow(() -> new IllegalArgumentException("the case analysis graph runs with a CaseRun in its config"));
    }
}
