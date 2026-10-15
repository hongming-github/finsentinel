package com.finsentinel.platform.analysis;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.finsentinel.platform.security.Tenancy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Checkpoint saver for the analysis graph (ADR 0008), in place of the library's savers.
 *
 * <ul>
 *   <li>Fenced: every write first takes a share lock on the case row and checks that the case is still in ANALYSIS
 *       under this worker's claim. A worker whose claim timed out cannot write once a successor has claimed the
 *       case; it gets {@link LostClaimException} at its next checkpoint and stops. The share lock makes check and
 *       write atomic against a concurrent claim, which updates the same row. The library's savers (and LangGraph's)
 *       have no notion of an owner, so a stale worker could keep appending to a thread its successor now runs.</li>
 *   <li>Plain JSON state on a Flyway-managed table, through {@link CaseGraphStateSerializer}; the library's savers
 *       store base64 inside jsonb and create their own tables.</li>
 *   <li>{@link JdbcClient}: connections always go back to the pool.</li>
 * </ul>
 *
 * <p>The case lifecycle owns the rows: {@code CaseRepository} deletes a run's checkpoints in the same transaction
 * that ends the run. The graph is compiled without {@code releaseThread}, so a checkpoint after the last node
 * survives until the case has moved on; a worker that crashes in between resumes straight to the end.
 */
@Component
class FencedPostgresCheckpointSaver implements BaseCheckpointSaver {

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

    /** Newest first, as the library expects. */
    @Override
    public Collection<Checkpoint> list(RunnableConfig config) {
        CaseRun run = CaseRun.of(config);
        return Tenancy.call(run.tenantId(), () -> load(run));
    }

    /** The checkpoint named in the config, else the newest. */
    @Override
    public Optional<Checkpoint> get(RunnableConfig config) {
        CaseRun run = CaseRun.of(config);
        List<Checkpoint> checkpoints = Tenancy.call(run.tenantId(), () -> load(run));
        return config.checkPointId()
                .map(id -> checkpoints.stream().filter(cp -> cp.getId().equals(id)).findFirst())
                .orElseGet(() -> checkpoints.stream().findFirst());
    }

    /**
     * Appends a checkpoint, or replaces the one named in the config (state edits between runs; a graph run only
     * appends). Returns the config pointing at the written checkpoint.
     */
    @Override
    public RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) {
        CaseRun run = CaseRun.of(config);
        // Graph nodes run on pool threads: the scope comes from the run, not from the calling thread.
        return Tenancy.call(run.tenantId(), () -> write(config, checkpoint, run));
    }

    private RunnableConfig write(RunnableConfig config, Checkpoint checkpoint, CaseRun run) {
        if (config.checkPointId().isPresent()) {
            requireOwner(jdbc.sql(OWNER + """
                            update graph_checkpoint g
                            set checkpoint_id = :checkpointId, node_id = :nodeId, next_node_id = :nextNodeId,
                                state = cast(:state as jsonb)
                            from owner
                            where g.checkpoint_id = :replaced and g.case_id = owner.case_id and g.run = :run
                            """)
                    .param("caseId", run.caseId())
                    .param("claimedAt", Timestamp.from(run.claimedAt()))
                    .param("replaced", UUID.fromString(config.checkPointId().get()))
                    .param("checkpointId", UUID.fromString(checkpoint.getId()))
                    .param("run", run.run())
                    .param("nodeId", checkpoint.getNodeId())
                    .param("nextNodeId", checkpoint.getNextNodeId())
                    .param("state", serializer.toJson(checkpoint.getState()))
                    .update(), run);
            return config;
        }
        requireOwner(jdbc.sql(OWNER + """
                        insert into graph_checkpoint (checkpoint_id, case_id, run, node_id, next_node_id, state)
                        select :checkpointId, case_id, :run, :nodeId, :nextNodeId, cast(:state as jsonb) from owner
                        """)
                .param("caseId", run.caseId())
                .param("claimedAt", Timestamp.from(run.claimedAt()))
                .param("checkpointId", UUID.fromString(checkpoint.getId()))
                .param("run", run.run())
                .param("nodeId", checkpoint.getNodeId())
                .param("nextNodeId", checkpoint.getNextNodeId())
                .param("state", serializer.toJson(checkpoint.getState()))
                .update(), run);
        return RunnableConfig.builder(config).checkPointId(checkpoint.getId()).build();
    }

    /** Not called by our graph (compiled without releaseThread); deletes the thread like the lifecycle would. */
    @Override
    public Tag release(RunnableConfig config) {
        CaseRun run = CaseRun.of(config);
        return Tenancy.call(run.tenantId(), () -> releaseRun(run));
    }

    private Tag releaseRun(CaseRun run) {
        List<Checkpoint> checkpoints = load(run);
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

    private List<Checkpoint> load(CaseRun run) {
        List<Checkpoint> rows = jdbc.sql("""
                        select checkpoint_id, node_id, next_node_id, state from graph_checkpoint
                        where case_id = :caseId and run = :run
                        order by seq desc
                        """)
                .param("caseId", run.caseId())
                .param("run", run.run())
                .query((rs, n) -> Checkpoint.builder()
                        .id(rs.getString("checkpoint_id"))
                        .state(serializer.fromJson(rs.getString("state")))
                        .nodeId(rs.getString("node_id"))
                        .nextNodeId(rs.getString("next_node_id"))
                        .build())
                .list();
        return rows;
    }

    private static void requireOwner(int rows, CaseRun run) {
        if (rows == 0) {
            throw new LostClaimException(run);
        }
    }
}
