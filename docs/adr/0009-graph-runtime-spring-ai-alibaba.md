# 0009. Inner graph runtime: Spring AI Alibaba Graph instead of LangGraph4j

- Status: accepted (replaces the runtime choice in ADR 0008; the two-layer design stands)
- Date: 2026-10-03

## Context

ADR 0008 put a LangGraph4j graph inside the claimed ANALYSIS stage, behind our own fenced checkpoint saver. Before
building the multi-agent graph (phase 4 step C2) we asked whether LangGraph4j is the right runtime for a system
meant to look like enterprise work: it has a small maintainer base, and we had already found a connection leak in
its savers.

| Candidate (Oct 2026) | What it is | Fit |
|---|---|---|
| LangGraph4j 1.9.3 | Java port of LangGraph | Works; small project; drives graphs on the common fork-join pool |
| Spring AI Alibaba Graph | Alibaba's LangGraph-style graph runtime on Spring AI, multi-agent patterns, checkpoints | Widely used in the Alibaba ecosystem; for Spring AI 2.0 only a milestone, `2.0.0-M1.1` |
| AgentScope Java 2.0 | Alibaba, ReAct-agent centred | Not a durable workflow runtime |
| Temporal (Java SDK 1.40) | Durable execution platform | Enterprise grade, but a cluster to run, overlapping our Postgres lifecycle |
| Plain Java | Spring AI + virtual threads, our own checkpoints | No dependency; fixed pipelines need no framework |

## Spike (scratch project with our exact BOMs: Spring Boot 4.1.1, Spring AI 2.0.1, MCP SDK 2.0.0)

- Runs; Spring AI artifacts align to 2.0.1 through our BOM. No auto-configuration, no `ServiceLoader`.
- Node-level resume works, but only when the config names the checkpoint (`checkPointId`); null inputs are not a
  resume (our first attempt re-ran the finished node).
- A saver can fence: the claim travels in the config metadata, a stale write is rejected, nothing is written.
- Parallel nodes take an executor from the config (we pass virtual threads with the application class loader).
- `StateGraph` builds a default serializer in a static initializer that loads DeepSeek and ZhipuAI message classes:
  excluding those artifacts fails class initialisation (`NoClassDefFoundError`, tested). DeepSeek resolves to 2.0.1;
  ZhipuAI is absent from the 2.0.1 BOM and stays at 2.0.0-M1. Its MCP SDK 0.14 is unused and excluded.
- Its savers close connections correctly, but like LangGraph4j's they store base64 state, create their own tables,
  and have no notion of an owner. The project's own issue
  [#4792](https://github.com/alibaba/spring-ai-alibaba/issues/4792) (open since July 2026) asks for conditional
  checkpoint writes for multi-instance consistency: the problem our fenced saver solves.

## Decision

Spring AI Alibaba Graph `2.0.0-M1.1` runs the analysis graph. Everything that makes it safe stays ours: the fenced
saver on `graph_checkpoint` (same table, same SQL), the Jackson 3 state codec with declared types, the lifecycle
deleting checkpoints, the node executor. Only `CaseAnalysisGraph`, the saver adapter and the state codec changed.

**Fencing vs. #4792's proposal.** #4792 proposes compare-and-set on the thread's latest checkpoint. That stops two
writers appending on the same base, but it does not know who owns the thread: if the new owner has claimed but not
yet written, the stale worker's write still matches the latest checkpoint and wins, and the owner's first write
is the one rejected. Our fence checks the owner itself (the case's claim, `claimed_at`, in the same statement under a
share lock that conflicts with a new claim), so a takeover stops the stale worker at its next write whether or not
the new owner has written. A version check can be added on top for concurrent writes by one owner.

## Consequences

- A runtime with a large user base and Alibaba-ecosystem recognition; the multi-agent patterns (parallel nodes,
  routing, supervisor) are available for step C2.
- A milestone dependency, plus a ZhipuAI 2.0.0-M1 jar on the classpath that we never call. The full test suite and a
  packaged-jar run guard against linkage problems; revisit on every Spring AI or Spring AI Alibaba upgrade, and move
  to their 2.0 GA when it ships.
- Same evidence as before, re-run on the new runtime: the five resume and fencing tests pass unchanged; without the
  fence the stale-worker test fails exactly as before (`["investigate", "review"]` left behind); with the packaged
  jar, `kill -9` mid-investigation and restart resumes after `__START__` and completes.
- Our fencing approach is a candidate contribution to #4792.
