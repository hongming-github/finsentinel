package com.finsentinel.platform.analysis;

import com.finsentinel.platform.investigation.CaseContext;
import com.finsentinel.platform.investigation.CaseInvestigator;
import com.finsentinel.platform.investigation.InvestigationResult;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.GraphRunnerException;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.function.Function;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;

/**
 * The inner layer of ADR 0008: what happens inside one claimed ANALYSIS stage, as a LangGraph4j graph checkpointed
 * after every node. Today {@code investigate → review}; phase 4 step C adds triage, parallel specialists and the
 * reviewer's send-back loop.
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
     * LangGraph4j drives the graph from the common fork-join pool. Nodes block on LLM and database calls, so they do
     * that on virtual threads; the driver then waits through {@code CompletableFuture}, which the pool compensates for.
     * Node threads get the application class loader: pool threads carry the system class loader, which inside the
     * packaged jar cannot see the libraries, and anything in a node that uses {@code ServiceLoader} would fail.
     */
    private static final ClassLoader APPLICATION_LOADER = CaseAnalysisGraph.class.getClassLoader();
    private static final Executor NODE_EXECUTOR = task -> {
        Thread node = Thread.ofVirtual().name("case-graph-node").unstarted(task);
        node.setContextClassLoader(APPLICATION_LOADER);
        node.start();
    };

    private final CompiledGraph<CaseGraphState> graph;
    private final FencedPostgresCheckpointSaver saver;

    CaseAnalysisGraph(CaseInvestigator investigator, CaseReviewer reviewer, FencedPostgresCheckpointSaver saver,
                      CaseGraphStateSerializer serializer) throws GraphStateException {
        this.saver = saver;
        this.graph = new StateGraph<>(CaseGraphState.CHANNELS, serializer)
                .addNode(INVESTIGATE, node(state ->
                        Map.of(CaseGraphState.INVESTIGATION, investigator.investigate(state.caseContext()))))
                .addNode(REVIEW, node(state -> Map.of(CaseGraphState.REVIEW,
                        reviewer.review(state.caseContext(), state.investigation().orElseThrow()))))
                .addEdge(START, INVESTIGATE)
                .addEdge(INVESTIGATE, REVIEW)
                .addEdge(REVIEW, END)
                .compile(CompileConfig.builder().checkpointSaver(saver).releaseThread(false).build());
    }

    /**
     * Analyses one run of a case, resuming from its last checkpoint if there is one.
     *
     * @param claimedAt the worker's claim; every checkpoint write is fenced by it
     * @throws LostClaimException when another worker has taken the case over; nothing more was written
     */
    public Outcome analyse(CaseContext context, Instant claimedAt) {
        CaseRun run = new CaseRun(UUID.fromString(context.caseId()), context.run(), claimedAt);
        RunnableConfig config = run.config();
        Optional<Checkpoint> last = saver.get(config);
        last.ifPresent(cp -> log.info("Case {} run {}: resuming after node '{}'", context.caseId(), context.run(),
                cp.nodeId()));
        GraphInput input = last.isPresent() ? GraphInput.resume()
                : GraphInput.args(Map.of(CaseGraphState.CASE, context));
        CaseGraphState end;
        try {
            end = graph.invoke(input, config).orElseThrow(() -> new IllegalStateException("graph produced no state"));
        } catch (RuntimeException e) {
            throw unwrap(e);
        }
        return new Outcome(end.investigation().orElseThrow(), end.review().orElseThrow(),
                last.map(Checkpoint::nodeId).orElse(null));
    }

    /**
     * @param resumedAfter the node whose checkpoint this run resumed from; null for a fresh start
     */
    public record Outcome(InvestigationResult investigation, Review review, String resumedAfter) {
    }

    private static AsyncNodeAction<CaseGraphState> node(Function<CaseGraphState, Map<String, Object>> action) {
        return state -> CompletableFuture.supplyAsync(() -> action.apply(state), NODE_EXECUTOR);
    }

    /**
     * LangGraph4j wraps a node's exception several times ({@code CompletionException}, {@code GraphRunnerException},
     * {@code ExecutionException}, plain {@code RuntimeException}). Callers need the node's own exception:
     * {@link LostClaimException} means stop quietly, anything else is a failed attempt whose message is recorded.
     */
    static RuntimeException unwrap(RuntimeException e) {
        Throwable t = e;
        while (isWrapper(t)) {
            t = t.getCause();
        }
        return t instanceof RuntimeException runtime ? runtime : new IllegalStateException(t);
    }

    private static boolean isWrapper(Throwable t) {
        return t.getCause() != null && (t instanceof CompletionException || t instanceof ExecutionException
                || t instanceof GraphRunnerException || t.getClass() == RuntimeException.class);
    }
}
