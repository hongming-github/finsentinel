# 0026. Phase 9C chaos drills: one fault at a time under steady traffic, counted from the database and Kafka

- Status: accepted (phase 9 step C; results in `docs/perf/2026-10-17-phase-9c.md`)
- Date: 2026-10-17

## Context

Phase 9 has to publish recovery behaviour under faults. The design promises a lot of it: the gateway falls back and
opens a circuit (ADR 0012), outside systems return `Unavailable` (ADR 0002), Redis is disposable (ADR 0013), claims are
fenced and analyses resume from checkpoints (ADR 0008, 0009), alerts are at-least-once with deterministic ids
(ADR 0003), only unreadable messages are dead-lettered (ADR 0018), and batch baselines stand in for missing stream
history (ADR 0019). None of it had been tried against a running stack with traffic flowing: the tests exercise one
component at a time, with the failure staged inside the test.

## Decision

1. **One fault per drill, under steady traffic, with a control run.** The simulator runs at 20 transactions/s with a
   fraud scenario every 3 s throughout; each drill takes a 90 s baseline, injects one fault, holds it for a fixed time
   (30-150 s), repairs it and waits for recovery. Twelve faults, chosen to cover each promise above: the main model
   failing, both models failing, a slow model, an MCP server down, slow outside systems, Redis, Postgres (30 s and
   150 s), Kafka, the schema registry (warm, and with a platform restart during it), a platform instance killed
   mid-analysis, the embedded Flink process killed, and a TaskManager killed on the compose cluster.
2. **`scripts/perf/drill.py`, standard library only, like `flink-rate.py`.** It runs the inject and heal commands,
   samples Postgres, Kafka end offsets, process health and the Flink REST API every 5 s, decides recovery from those
   samples (everything up again, a report stored since the repair, the backlog at its baseline plus 2), and writes a
   summary, the samples, an SVG chart, the platform's warnings and screenshots. Faults are plain shell commands
   (`docker stop`, `kill -9`, fake-llm's `/admin/behaviour`, WireMock's `/__admin/settings`), so a drill can be read
   and repeated without the tool.
3. **Loss counted exactly, not inferred from rates.** Transactions: records on the topic in the window by Kafka's
   time index against rows stored with a send time in the window (the GEO_JUMP home payment is sent 20 minutes after
   its event time; tolerance +-3 at the edges). Alerts: records against rows, with the dead-letter topic read to tell
   duplicates from dead letters. Detection: every scenario in the simulator's log against an alert of the matching
   rule. The summary is recomputed from the database, so a drill can be re-reported later; the Grafana screenshots are
   taken at the end of each drill because the LGTM container's series do not outlive it.
4. **fake-llm calls an outside system's tool** (`getCustomerProfile`, when offered) in its first tool round, so a load
   test or drill reaches mcp-banking the way a real model does. Without it the first MCP drill touched no MCP tool.
   `FakeLlmTest` asserts the new first round; `FakeLlmContractTest` passes.
5. **Histograms that cover minutes**: `management.metrics.distribution` sets the maximum expected value of the
   pipeline timers (15 min) and the gateway's model calls (5 min), and turns on the model-call histogram. Micrometer's
   default range ends at 30 s, so a three-minute payment-to-report time read as 30 s on the dashboard.
6. **Findings are recorded, not fixed in this step.** Each fix changes a concurrency or error-handling path and needs
   its own test that fails without it; they are listed under Next in order of consequence.

## Evidence

From `docs/perf/2026-10-17-phase-9c.md` (1,301 fraud scenarios across the drills):

- Held as designed: main model down 120 s, 5 calls to it and 86 fallback answers, nothing failed (1); MCP server down,
  42 CRM calls `Unavailable` in about 24 ms, nothing failed, client reconnected by itself (4); Redis down, nothing
  failed (6); Kafka down 60 s, nothing lost (8); registry down with warm caches, no effect (9); `kill -9` mid-analysis,
  resumed from the checkpoint with no duplicate report (10); TaskManager killed, restored checkpoint 13, 38/38
  detected, one duplicate alert stored once (12); after a cold Flink restart with batch baselines, 27/27 large amounts
  detected from "batch baseline as of 2026-10-16" (11b).
- Did not hold: a 150 s Postgres outage dead-lettered 1 alert and 1 transaction (7b); the platform cannot start while
  the registry is down and nothing local restarts it (9b, 9 minutes down); both models down for 120 s left 7 cases
  FAILED for good (2); a killed instance's claims waited the full 5-minute claim timeout although it was back in 15 s
  (10); slow outside systems cost the full 5 s timeout on every call (5); a cold Flink restart without baselines
  detected 36 of 76 large amounts in the next 12 minutes (11).
- Recovery time is mostly queue capacity: 2 cases per round and a 5 s pause give 20-24 analyses a minute against about
  20 alerts a minute, so a 2-minute stall takes 5-7 minutes to drain (2, 3, 7b) and a 9-minute one did not drain in 20
  (9b).

## Consequences

- The recovery numbers are the platform's with an instant model; with a real one (38 s per analysis, ADR 0025) the
  same stalls would take longer to drain at the current worker settings.
- The drills cost nothing and take 5-25 minutes each; they need the stack, traffic and, for drill 12, the compose
  `stream` profile with `--local.web-ui-port=8081` passed to `flink run`.
- The design rules that held are now backed by a run each; the ones that did not are known and ranked.

## Next

In order of consequence, each with a test that fails without the fix:

1. **Never dead-letter on infrastructure errors**: retry without a limit (backoff, listener paused) and dead-letter
   only `InvalidMessageException`; a test where Postgres is gone for longer than the retries.
2. **Start without the registry**: resolve writer schemas on first use, so the process, the analyst API and the MCP
   server stay up and the listeners retry.
3. **Back off between analysis attempts** (`next_attempt_at`), so a provider outage of minutes does not use up the
   attempts.
4. **Claim heartbeat**: renew claims of live runs and shorten the lease, fencing unchanged; a test that kills a run's
   owner and measures the takeover.
5. **Circuit breaker per outside system** in `ExternalSystems`.
6. **Worker pool** (ADR 0025 Next), then repeat drills 2, 3 and 7b to measure the drain.
7. Local Flink: restore the latest retained checkpoint in `scripts/stack.sh flink`; compose `stream` profile: a
   shared checkpoint volume and a REST port that does not override the cluster's.
