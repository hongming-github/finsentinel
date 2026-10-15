package com.finsentinel.platform.analysis;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.serializer.StateSerializer;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.finsentinel.platform.investigation.AnalysisPlan;
import com.finsentinel.platform.investigation.CaseContext;
import com.finsentinel.platform.investigation.CaseInvestigator;
import com.finsentinel.platform.investigation.CasePlanner;
import com.finsentinel.platform.investigation.CaseReviewer;
import com.finsentinel.platform.investigation.Disposition;
import com.finsentinel.platform.investigation.InvestigationResult;
import com.finsentinel.platform.investigation.ReportWriter;
import com.finsentinel.platform.investigation.Review;
import com.finsentinel.platform.investigation.Revision;
import com.finsentinel.platform.investigation.Specialist;
import com.finsentinel.platform.investigation.SpecialistAgents;
import com.finsentinel.platform.investigation.SpecialistReport;
import com.finsentinel.platform.llm.LlmUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.Exceptions;

import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Function;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;

/**
 * The inner layer of ADR 0008: what happens inside one claimed ANALYSIS stage, as a Spring AI Alibaba graph
 * checkpointed after every node. Two shapes, chosen by {@code finsentinel.analysis.mode}:
 *
 * <pre>
 * SINGLE: START → investigate → review ─┬→ END
 *                      ↑─── revise ─────┘
 * MULTI:  START → plan → transactions ┐
 *                      → customer     ├→ write → review ─┬→ END
 *                      → regulations  ┘    ↑─── revise ──┘
 * </pre>
 *
 * MULTI follows agentUniverse's PEER pattern: Plan (deterministic routing), Execute (specialists in parallel, each
 * with its own tools), Express (a writer without tools), Review (a second model that can send the report back, at
 * most {@code max-revisions} times). The specialists run as one parallel node: if one fails, the attempt fails and a
 * retry runs all of them again.
 *
 * <p>A run that finds checkpoints for its thread (an earlier attempt crashed or failed) resumes after the last
 * finished node instead of repeating it, which is what saves the LLM calls of finished agents.
 */
@Component
public class CaseAnalysisGraph {

    static final String INVESTIGATE = "investigate";
    static final String PLAN = "plan";
    static final String WRITE = "write";
    static final String REVIEW = "review";
    private static final String REVISE = "revise";
    private static final String DONE = "done";

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

    private final CaseInvestigator investigator;
    private final SpecialistAgents specialists;
    private final ReportWriter writer;
    private final CaseReviewer reviewer;
    private final CasePlanner planner;
    private final FencedPostgresCheckpointSaver saver;
    private final AnalysisProperties props;
    private final Map<AnalysisMode, CompiledGraph> graphs = new EnumMap<>(AnalysisMode.class);
    private final Tracer tracer;
    /**
     * The trace context of each run in progress on this instance (the worker's analysis span), so nodes, which run on
     * threads the graph library starts, continue it (ADR 0020). Only ever read for runs this instance is executing.
     */
    private final Map<String, Context> runs = new ConcurrentHashMap<>();

    CaseAnalysisGraph(CaseInvestigator investigator, SpecialistAgents specialists, ReportWriter writer,
                      CaseReviewer reviewer, CasePlanner planner, FencedPostgresCheckpointSaver saver,
                      CaseGraphStateSerializer serializer, AnalysisProperties props, Tracer tracer)
            throws GraphStateException {
        this.tracer = tracer;
        this.investigator = investigator;
        this.specialists = specialists;
        this.writer = writer;
        this.reviewer = reviewer;
        this.planner = planner;
        this.saver = saver;
        this.props = props;
        CompileConfig compile = CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(saver).build())
                .releaseThread(false)
                .build();
        graphs.put(AnalysisMode.SINGLE, single(serializer).compile(compile));
        graphs.put(AnalysisMode.MULTI, multi(serializer).compile(compile));
    }

    private StateGraph single(CaseGraphStateSerializer serializer) throws GraphStateException {
        return new StateGraph(CaseGraphState.keyStrategies(), (StateSerializer) serializer)
                .addNode(INVESTIGATE, node(INVESTIGATE, state -> {
                    InvestigationResult result = investigator.investigate(CaseGraphState.caseContext(state),
                            revision(state));
                    return Map.of(CaseGraphState.INVESTIGATION, result,
                            CaseGraphState.SPENT, CaseGraphState.spent(state).plus(result.usage()));
                }))
                .addNode(REVIEW, node(REVIEW, state -> review(state, List.of())))
                .addEdge(START, INVESTIGATE)
                .addEdge(INVESTIGATE, REVIEW)
                .addConditionalEdges(REVIEW, edge_async(this::afterReview), Map.of(REVISE, INVESTIGATE, DONE, END));
    }

    private StateGraph multi(CaseGraphStateSerializer serializer) throws GraphStateException {
        StateGraph graph = new StateGraph(CaseGraphState.keyStrategies(), (StateSerializer) serializer)
                .addNode(PLAN, node(PLAN, state -> Map.of(CaseGraphState.PLAN,
                        planner.plan(CaseGraphState.caseContext(state)))))
                .addNode(WRITE, node(WRITE, state -> {
                    InvestigationResult result = writer.write(CaseGraphState.caseContext(state),
                            CaseGraphState.plan(state), reports(state), revision(state));
                    return Map.of(CaseGraphState.INVESTIGATION, result,
                            CaseGraphState.SPENT, CaseGraphState.spent(state).plus(result.usage()));
                }))
                .addNode(REVIEW, node(REVIEW, state -> review(state, reports(state))))
                .addEdge(START, PLAN);
        for (Specialist specialist : Specialist.values()) {
            graph.addNode(CaseGraphState.key(specialist), node(CaseGraphState.key(specialist),
                            state -> Map.of(CaseGraphState.key(specialist),
                            runSpecialist(specialist, state))))
                    .addEdge(PLAN, CaseGraphState.key(specialist))
                    .addEdge(CaseGraphState.key(specialist), WRITE);
        }
        return graph.addEdge(WRITE, REVIEW)
                .addConditionalEdges(REVIEW, edge_async(this::afterReview), Map.of(REVISE, WRITE, DONE, END));
    }

    private SpecialistReport runSpecialist(Specialist specialist, OverAllState state) {
        AnalysisPlan plan = CaseGraphState.plan(state);
        if (!plan.specialists().contains(specialist)) {
            return SpecialistReport.skipped(specialist, "not needed for this case: " + String.join("; ", plan.reasons()));
        }
        return specialists.run(specialist, CaseGraphState.caseContext(state));
    }

    private static List<SpecialistReport> reports(OverAllState state) {
        return Arrays.stream(Specialist.values())
                .map(s -> CaseGraphState.specialist(state, s).orElseThrow(() ->
                        new IllegalStateException("specialist " + s + " has not reported")))
                .filter(r -> !r.skipped())
                .toList();
    }

    private Map<String, Object> review(OverAllState state, List<SpecialistReport> evidence) {
        CaseContext caseContext = CaseGraphState.caseContext(state);
        InvestigationResult investigation = CaseGraphState.investigation(state).orElseThrow();
        Disposition disposition = investigation.report().disposition();
        Review review = props.reviewDispositions().contains(disposition)
                ? reviewer.review(caseContext, investigation, evidence)
                : Review.skipped(disposition + " reports go to the analyst unreviewed (risk-based review)");
        log.info("Case {} run {} review {}: {}", caseContext.caseId(), caseContext.run(),
                CaseGraphState.reviews(state) + 1, review.verdict());
        return Map.of(CaseGraphState.REVIEW, review,
                CaseGraphState.REVIEWS, CaseGraphState.reviews(state) + 1,
                CaseGraphState.SPENT, CaseGraphState.spent(state).plus(review.usage()));
    }

    /** Back to the writer while the reviewer objects and revisions are left; otherwise the report goes as it is. */
    private String afterReview(OverAllState state) {
        Review review = CaseGraphState.review(state).orElseThrow();
        return review.approved() || CaseGraphState.reviews(state) > props.maxRevisions() ? DONE : REVISE;
    }

    /** The reviewer's objections to the current report, when it has sent it back. */
    private static Revision revision(OverAllState state) {
        return CaseGraphState.review(state)
                .filter(review -> !review.approved())
                .map(review -> new Revision(CaseGraphState.investigation(state).orElseThrow().report(), review.issues()))
                .orElse(null);
    }

    /** Analyses one run of a case in the configured mode, resuming from its last checkpoint if there is one. */
    public Outcome analyse(CaseContext context, Instant claimedAt) {
        return analyse(context, claimedAt, props.mode());
    }

    /**
     * @param claimedAt the worker's claim; every checkpoint write is fenced by it
     * @param mode      used for a fresh start; a resumed run keeps the mode it started in
     * @throws LostClaimException when another worker has taken the case over; nothing more was written
     */
    public Outcome analyse(CaseContext context, Instant claimedAt, AnalysisMode mode) {
        RunnableConfig config = new CaseRun(UUID.fromString(context.caseId()), context.tenantId(), context.run(),
                claimedAt).config();
        Optional<Checkpoint> last = saver.get(config);
        AnalysisMode runMode = last.map(cp -> (AnalysisMode) cp.getState().get(CaseGraphState.MODE)).orElse(mode);
        CompiledGraph graph = graphs.get(runMode);
        long start = System.nanoTime();
        OverAllState end;
        String runKey = runKey(context);
        runs.put(runKey, Context.current());
        try {
            if (last.isPresent()) {
                // The library resumes only when the config names the checkpoint to resume from.
                log.info("Case {} run {}: resuming {} analysis after node '{}'", context.caseId(), context.run(),
                        runMode, last.get().getNodeId());
                end = graph.invoke(Map.of(), RunnableConfig.builder(config).checkPointId(last.get().getId()).build())
                        .orElseThrow();
            } else {
                end = graph.invoke(Map.of(CaseGraphState.CASE, context, CaseGraphState.MODE, runMode), config)
                        .orElseThrow();
            }
        } catch (RuntimeException e) {
            throw unwrap(e);
        } finally {
            runs.remove(runKey);
        }
        LlmUsage total = CaseGraphState.spent(end);
        for (Specialist specialist : Specialist.values()) {
            total = total.plus(CaseGraphState.specialist(end, specialist).map(SpecialistReport::usage)
                    .orElse(LlmUsage.NONE));
        }
        // Latency is this attempt's wall clock, not the sum of node times: specialists run in parallel.
        total = total.withLatency((System.nanoTime() - start) / 1_000_000);
        return new Outcome(runMode, CaseGraphState.investigation(end).orElseThrow().withUsage(total),
                CaseGraphState.review(end).orElseThrow(), last.map(Checkpoint::getNodeId).orElse(null));
    }

    /**
     * @param investigation the final report, with the cost of every model call in the analysis
     * @param review        the reviewer's last verdict; not approved when revisions ran out
     * @param resumedAfter  the node whose checkpoint this run resumed from; null for a fresh start
     */
    public record Outcome(AnalysisMode mode, InvestigationResult investigation, Review review, String resumedAfter) {
    }

    private static String runKey(CaseContext context) {
        return context.caseId() + ":" + context.run();
    }

    /** A node runs in a span of its own, a child of the run's analysis span; failures are recorded on it. */
    private AsyncNodeAction node(String name, Function<OverAllState, Map<String, Object>> action) {
        return state -> {
            CaseContext caseContext = CaseGraphState.caseContext(state);
            Context parent = runs.getOrDefault(runKey(caseContext), Context.root());
            return CompletableFuture.supplyAsync(() -> {
                Span span = tracer.spanBuilder("graph node " + name).setParent(parent)
                        .setAttribute("finsentinel.case_id", caseContext.caseId())
                        .setAttribute("finsentinel.run", caseContext.run())
                        .startSpan();
                try (Scope ignored = span.makeCurrent()) {
                    return action.apply(state);
                } catch (RuntimeException e) {
                    span.recordException(e);
                    span.setStatus(StatusCode.ERROR, e.toString());
                    throw e;
                } finally {
                    span.end();
                }
            }, NODE_EXECUTOR);
        };
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
