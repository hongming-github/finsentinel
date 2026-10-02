package com.finsentinel.platform.analysis;

import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.InterruptionMetadata;
import org.bsc.langgraph4j.checkpoint.AbstractCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.bsc.langgraph4j.state.AgentState;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.LinkedList;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * LangGraph4j checkpoint saver for the analysis graph (ADR 0008), replacing the library's Postgres saver.
 *
 * <ul>
 *   <li>Fenced: every write first takes a share lock on the case row and checks that the case is still in ANALYSIS
 *       under this worker's claim. A worker whose claim timed out cannot write once a successor has claimed the
 *       case; it gets {@link LostClaimException} at its next checkpoint and stops. The share lock makes check and
 *       write atomic against a concurrent claim, which updates the same row.</li>
 *   <li>Plain JSON state on a Flyway-managed table, through {@link CaseGraphStateSerializer}.</li>
 *   <li>{@link JdbcClient}: connections always go back to the pool.</li>
 * </ul>
 *
 * <p>The case lifecycle owns the rows: {@code CaseRepository} deletes a run's checkpoints in the same transaction
 * that ends the run. The graph is compiled without {@code releaseThread}, so a checkpoint after the last node
 * survives until the case has moved on; a worker that crashes in between resumes straight to the end.
 */
@Component
class FencedPostgresCheckpointSaver extends AbstractCheckpointSaver {

    /** The case row, share-locked, if this worker still owns it. Shared by every write. */
    private static final String OWNER = """
            with owner as (
                select case_id from fraud_case
                where case_id = :caseId and stage = 'ANALYSIS' and claimed_at = :claimedAt
                for share
            )
            """;

    private final JdbcClient jdbc;
    private final CaseGraphStateSerializer serializer;

    FencedPostgresCheckpointSaver(JdbcClient jdbc, CaseGraphStateSerializer serializer) {
        this.jdbc = jdbc;
        this.serializer = serializer;
    }

    @Override
    protected LinkedList<Checkpoint> loadCheckpoints(RunnableConfig config) {
        CaseRun run = CaseRun.of(config);
        // Newest first: AbstractCheckpointSaver treats the head of the list as the latest checkpoint.
        return jdbc.sql("""
                        select checkpoint_id, node_id, next_node_id, state from graph_checkpoint
                        where case_id = :caseId and run = :run
                        order by seq desc
                        """)
                .param("caseId", run.caseId())
                .param("run", run.run())
                .query((rs, n) -> new Checkpoint(rs.getString("checkpoint_id"),
                        serializer.readDataFromString(rs.getString("state")).data(),
                        rs.getString("node_id"), rs.getString("next_node_id")))
                .list()
                .stream()
                .collect(LinkedList::new, LinkedList::add, LinkedList::addAll);
    }

    @Override
    protected void insertedCheckpoint(RunnableConfig config, LinkedList<Checkpoint> checkpoints, Checkpoint checkpoint) {
        CaseRun run = CaseRun.of(config);
        int inserted = jdbc.sql(OWNER + """
                        insert into graph_checkpoint (checkpoint_id, case_id, run, node_id, next_node_id, state)
                        select :checkpointId, case_id, :run, :nodeId, :nextNodeId, cast(:state as jsonb) from owner
                        """)
                .param("caseId", run.caseId())
                .param("claimedAt", Timestamp.from(run.claimedAt()))
                .param("checkpointId", UUID.fromString(checkpoint.id()))
                .param("run", run.run())
                .param("nodeId", checkpoint.nodeId())
                .param("nextNodeId", checkpoint.nextNodeId())
                .param("state", serializer.toJson(checkpoint.state()))
                .update();
        requireOwner(inserted, run);
    }

    /** Used when state is edited between runs ({@code CompiledGraph.updateState}); the graph run itself only inserts. */
    @Override
    protected void updatedCheckpoint(RunnableConfig config, LinkedList<Checkpoint> checkpoints, Checkpoint checkpoint) {
        CaseRun run = CaseRun.of(config);
        int updated = jdbc.sql(OWNER + """
                        update graph_checkpoint g
                        set node_id = :nodeId, next_node_id = :nextNodeId, state = cast(:state as jsonb)
                        from owner
                        where g.checkpoint_id = :checkpointId and g.case_id = owner.case_id and g.run = :run
                        """)
                .param("caseId", run.caseId())
                .param("claimedAt", Timestamp.from(run.claimedAt()))
                .param("checkpointId", UUID.fromString(checkpoint.id()))
                .param("run", run.run())
                .param("nodeId", checkpoint.nodeId())
                .param("nextNodeId", checkpoint.nextNodeId())
                .param("state", serializer.toJson(checkpoint.state()))
                .update();
        requireOwner(updated, run);
    }

    /** Not called by our graph (compiled without releaseThread); deletes the thread like the lifecycle would. */
    @Override
    protected Tag releaseCheckpoints(RunnableConfig config, LinkedList<Checkpoint> checkpoints, @Nullable String message) {
        CaseRun run = CaseRun.of(config);
        boolean owner = jdbc.sql(OWNER + """
                        , released as (
                            delete from graph_checkpoint
                            where case_id in (select case_id from owner) and run = :run
                        )
                        select count(*) from owner
                        """)
                .param("caseId", run.caseId())
                .param("claimedAt", Timestamp.from(run.claimedAt()))
                .param("run", run.run())
                .query(Integer.class)
                .single() > 0;
        requireOwner(owner ? 1 : 0, run);
        return new Tag(run.threadId(), checkpoints);
    }

    /** Keeps the checkpoints: the next attempt at this run resumes from them. */
    @Override
    protected Tag releaseCheckpointsOnError(RunnableConfig config, LinkedList<Checkpoint> checkpoints, Throwable exception) {
        return new Tag(CaseRun.of(config).threadId(), checkpoints);
    }

    /** Released threads are not kept as tags: a finished run's record is its {@code case_analysis} row. */
    @Override
    public Optional<Tag> tag(RunnableConfig config, @Nullable Integer version) {
        return Optional.empty();
    }

    /** No interrupts in the analysis graph: the human gate is the AWAITING_DECISION stage, outside the graph. */
    @Override
    public <State extends AgentState> CompletableFuture<InterruptionMetadata<State>> registerInterruption(
            RunnableConfig config, InterruptionMetadata<State> interruptionMetadata) {
        return CompletableFuture.completedFuture(interruptionMetadata);
    }

    private static void requireOwner(int rows, CaseRun run) {
        if (rows == 0) {
            throw new LostClaimException(run);
        }
    }
}
