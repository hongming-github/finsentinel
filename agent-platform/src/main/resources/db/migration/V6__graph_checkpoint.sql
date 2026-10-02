-- Phase 4B: checkpoints of the LangGraph4j analysis graph (ADR 0008). One graph thread per case analysis run.
-- Rows are written only by the worker that holds the case's claim, and deleted by the case lifecycle when the run
-- ends (analysed, superseded or failed for good). An attempt that fails keeps them, so the next attempt resumes.

create table graph_checkpoint (
    checkpoint_id uuid        primary key,
    case_id       uuid        not null references fraud_case (case_id),
    run           int         not null,
    -- Insertion order within the thread; the newest checkpoint is where a resumed graph continues.
    seq           bigint      generated always as identity,
    node_id       text        not null,
    next_node_id  text        not null,
    -- Graph state as plain JSON (one property per state key), readable with SQL and stable across class changes.
    state         jsonb       not null,
    created_at    timestamptz not null default clock_timestamp()
);
create index graph_checkpoint_thread_idx on graph_checkpoint (case_id, run, seq);
