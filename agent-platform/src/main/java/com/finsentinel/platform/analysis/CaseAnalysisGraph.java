package com.finsentinel.platform.analysis;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.finsentinel.platform.investigation.CaseContext;
import com.finsentinel.platform.investigation.CaseInvestigator;
import com.finsentinel.platform.investigation.InvestigationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.Exceptions;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.function.Function;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;

/**
 * The inner layer of ADR 0008: what happens inside one claimed ANALYSIS stage, as a Spring AI Alibaba graph
 * checkpointed after every node. Today {@code investigate → review}; phase 4 step C2 adds triage, parallel
 * specialists and the reviewer's send-back loop.
 *
 * <p>A run that finds checkpoints for its thread (an earlier attempt crashed or failed) resumes after the last
 * finished node instead of repeating it, which is what saves the LLM calls of finished agents.
 */
@Component
public class CaseAnalysisGraph {

    static final String INVESTIGATE = "investigate";
    static final String REVIEW = "review";

    private static final Logger log = LoggerFactory.getLogger(CaseAnalysisGraph.class);

    /**
     * Nodes block on LLM and database calls, so they run on virtual threads. They get the application class loader
     * explicitly: threads spawned from a pool carry the system class loader, which inside the packaged jar cannot see
     * the libraries, and anything in a node that uses {@code ServiceLoader} would fail (it did, for the MCP SDK).
     */
    private static final ClassLoader APPLICATION_LOADER = CaseAnalysisGraph.class.getClassLoader();
    static final Executor NODE_EXECUTOR = task -> {
        Thread node = Thread.ofVirtual().name("case-graph-node").unstarted(task);
        node.setContextClassLoader(APPLICATION_LOADER);
        node.start();
    };

    private final CompiledGraph graph;
    private final FencedPostgresCheckpointSaver saver;

    CaseAnalysisGraph(CaseInvestigator investigator, CaseReviewer reviewer, FencedPostgresCheckpointSaver saver,
                      CaseGraphStateSerializer serializer) throws GraphStateException {
        this.saver = saver;
        this.graph = new StateGraph(CaseGraphState.keyStrategies(), serializer)
                .addNode(INVESTIGATE, node(state -> Map.of(CaseGraphState.INVESTIGATION,
                        investigator.investigate(CaseGraphState.caseContext(state)))))
                .addNode(REVIEW, node(state -> Map.of(CaseGraphState.REVIEW, reviewer.review(
                        CaseGraphState.caseContext(state), CaseGraphState.investigation(state).orElseThrow()))))
                .addEdge(START, INVESTIGATE)
                .addEdge(INVESTIGATE, REVIEW)
                .addEdge(REVIEW, END)
                .compile(CompileConfig.builder()
                        .saverConfig(SaverConfig.builder().register(saver).build())
                        .releaseThread(false)
                        .build());
    }

    /**
     * Analyses one run of a case, resuming from its last checkpoint if there is one.
     *
     * @param claimedAt the worker's claim; every checkpoint write is fenced by it
     * @throws LostClaimException when another worker has taken the case over; nothing more was written
     */
    public Outcome analyse(CaseContext context, Instant claimedAt) {
        RunnableConfig config = new CaseRun(UUID.fromString(context.caseId()), context.run(), claimedAt).config();
        Optional<Checkpoint> last = saver.get(config);
        OverAllState end;
        try {
            if (last.isPresent()) {
                // The library resumes only when the config names the checkpoint to resume from.
                log.info("Case {} run {}: resuming after node '{}'", context.caseId(), context.run(),
                        last.get().getNodeId());
                end = graph.invoke(Map.of(), RunnableConfig.builder(config).checkPointId(last.get().getId()).build())
                        .orElseThrow();
            } else {
                end = graph.invoke(Map.of(CaseGraphState.CASE, context), config).orElseThrow();
            }
        } catch (RuntimeException e) {
            throw unwrap(e);
        }
        return new Outcome(CaseGraphState.investigation(end).orElseThrow(),
                CaseGraphState.review(end).orElseThrow(), last.map(Checkpoint::getNodeId).orElse(null));
    }

    /**
     * @param resumedAfter the node whose checkpoint this run resumed from; null for a fresh start
     */
    public record Outcome(InvestigationResult investigation, Review review, String resumedAfter) {
    }

    private static AsyncNodeAction node(Function<OverAllState, Map<String, Object>> action) {
        return state -> CompletableFuture.supplyAsync(() -> action.apply(state), NODE_EXECUTOR);
    }

    /**
     * The graph wraps a node's exception (in Reactor's, {@code CompletionException}, {@code GraphRunnerException},
     * plain {@code RuntimeException}). Callers need the node's own exception: {@link LostClaimException} means stop
     * quietly, anything else is a failed attempt whose message is recorded.
     */
    static RuntimeException unwrap(RuntimeException e) {
        Throwable t = Exceptions.unwrap(e);
        while (isWrapper(t)) {
            t = Exceptions.unwrap(t.getCause());
        }
        return t instanceof RuntimeException runtime ? runtime : new IllegalStateException(t);
    }

    private static boolean isWrapper(Throwable t) {
        return t.getCause() != null && (t instanceof CompletionException || t instanceof ExecutionException
                || t instanceof GraphRunnerException || t.getClass() == RuntimeException.class);
    }
}
