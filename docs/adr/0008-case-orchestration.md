# 0008. Case orchestration in two layers: a Postgres lifecycle around a LangGraph4j agent graph

- Status: accepted; the inner runtime is now Spring AI Alibaba Graph, see ADR 0009 (two layers and fencing unchanged)
- Date: 2026-10-02

## Context

Phase 4 turns alerts into cases and analyses them with several agents: triage, parallel specialists (transactions,
customer and counterparty via MCP, regulations), an investigator that writes the report, and a reviewer that can
send it back. A human then decides, possibly hours later. A crash must resume where it stopped, several platform
instances run at once, and LLM spend is capped. LangGraph is the reference design for agent graphs; its Java port,
LangGraph4j 1.9.3, targets our Spring AI 2.0.1. A spike ran this workflow shape on it.

## Spike findings: LangGraph4j 1.9.3

| Question | Result |
|---|---|
| Resume after a crash without re-running finished nodes | Works: a checkpoint per node, `GraphInput.resume()` continues from the last one |
| Human-in-the-loop | Works: `interruptBefore`, `updateState`, resume |
| Review sending the case back | Conditional edges express it cleanly |
| Official Postgres saver | **Leaks a connection on every `loadCheckpoints`** (`exec()` never closes it): with HikariCP (3 connections, leak detection on) the 4th `list()` times out, and Hikari names `AbstractPostgresSaver.exec` as the borrower. With the default `PGSimpleDataSource` 20 calls leave 20 server sessions until GC, which is why it went unnoticed. The same bug is in the MySQL, Oracle and SQLite savers, whose `execTransaction` also leaks |
| Checkpoint contents | Java-serialised state, base64 inside jsonb: not queryable, fragile across class changes |
| Schema | Created by the library, outside Flyway; its `db/migration/v1.0__init.sql` is skipped by our Flyway only because of the lower-case `v` |
| Fit with our queue | The saver knows nothing about claims, so a timed-out worker could overwrite a newer worker's checkpoints |

## Decision

Two layers, each owning one question.

**Case lifecycle (ours, Postgres): which stage is the case in, and who may work on it.**
`fraud_case` with stages `ANALYSIS → AWAITING_DECISION → CLOSED` (re-opened to `ANALYSIS` when a new alert joins).
Workers claim `ANALYSIS` cases with `SKIP LOCKED`, `claimed_at` fencing and the hourly budget, exactly as alerts
are claimed today. `AWAITING_DECISION` is never claimed: the decision API moves it on (the human-in-the-loop gate,
with identity, audit and optimistic locking). `case_event` records every transition. Decisions leave through a
transactional outbox.

**Analysis (LangGraph4j, in-process): how far has this analysis got.**
Inside the claimed `ANALYSIS` stage the worker runs a LangGraph4j `StateGraph`: triage (may end early), specialist
agents in parallel with reducer-merged findings, investigator, reviewer with a bounded send-back loop.

**The seam: our own checkpoint saver.** `FencedPostgresCheckpointSaver` implements LangGraph4j's
`AbstractCheckpointSaver` on a Flyway-managed table, stores state as JSON, uses `JdbcClient` (connections always
returned), and writes a checkpoint only while the case's `claimed_at` still matches the worker's claim. A crashed
worker's successor resumes the graph from the last finished node; a stale worker cannot write.

## Step A as built

- Intake files an alert in one transaction: `INSERT ... ON CONFLICT (tenant_id, account_id) WHERE stage <> 'CLOSED'`
  against a partial unique index, else `SELECT ... FOR UPDATE` the open case. Eight concurrent alerts on one account
  produce one case (tested).
- New evidence bumps `analysis_run`. A worker that finishes an older run stores its analysis for the record but the
  case goes back to the queue (`SUPERSEDED`), so the analyst never sees a report missing an alert.
- Decisions use HTTP conditional requests: the detail's `ETag` is the case version; `POST .../decision` needs
  `If-Match` (428 without, 409 with the current case if it changed).
- Outbox → `case.events.v1`, at-least-once with `eventId` for de-duplication.
- V5 migrated the per-alert queue: 27 alerts on 24 accounts became 24 cases, the newest report per case became run 1.

## Step B as built

- `CaseAnalysisGraph`: `START → investigate → review → END` (the reviewer approves every validated report until step
  C), compiled with `FencedPostgresCheckpointSaver` and without `releaseThread`. Thread id `<caseId>:<run>`; the run
  and the worker's `claimed_at` travel in the `RunnableConfig` metadata. If the thread has checkpoints the worker
  resumes (`GraphInput.resume()`), else it starts with the case context as input.
- Fencing: every write starts with `select ... from fraud_case where stage = 'ANALYSIS' and claimed_at = :claimedAt
  for share` in the same statement. A claim updates that row, so check and write are atomic against it; the loser
  gets `LostClaimException` at its next checkpoint and the worker abandons the run. Without the fence, the
  stale-worker test fails: the old worker writes `investigate` and `review` checkpoints after its successor finished.
- State is JSON (`graph_checkpoint.state`, one property per key) with a declared type per key
  (`CaseGraphState.TYPES`). The same codec clones the state between nodes, so a state that would not survive a
  restart fails in every normal run, not only after a crash.
- The case lifecycle owns the rows: `complete` deletes the run's checkpoints (and older runs') in its transaction,
  a FAILED case loses all of them, a failed attempt keeps them so the next attempt resumes. A crash between the
  graph's last checkpoint and `complete` resumes straight to `END` without calling any agent.
- LangGraph4j 1.9.3 drives the graph on the common fork-join pool and offers no executor for it; nodes do their
  blocking work on virtual threads. Node exceptions arrive wrapped four times and are unwrapped for the audit trail.
- Measured on the dev database with the real model: `kill -9` during the investigation, restart with a 30 s claim
  timeout; the next process re-claimed the case, logged `resuming after node '__START__'`, finished in 5.7 s and
  deleted the checkpoints.

## Consequences

- Real multi-agent orchestration on a mainstream framework, without its persistence weaknesses.
- One authority per concern: the lifecycle table for stage and ownership, graph checkpoints for analysis progress.
  Checkpoints belong to an analysis run and are released when the stage completes.
- Node-level resume avoids repeating finished agents' LLM calls after a crash.
- An extra dependency on a project with a small maintainer base; graph nodes stay thin wrappers around our own
  agent classes, so they could move to another runtime.
- Upstream fix for the saver leak prepared (try-with-resources in `exec`/`execTransaction` across four savers, with
  regression tests).
