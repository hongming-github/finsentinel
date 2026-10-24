# 0025. Phase 9B load tests: Gatling for the APIs, the simulator for the stream, the database for the numbers

- Status: accepted (phase 9 step B; results in `docs/perf/2026-10-16-phase-9b.md`)
- Date: 2026-10-16

## Context

Phase 9A (ADR 0024) built the measurement stack: fake-llm, profile `load`, pipeline timers per stage, the dashboard and
`scripts/stack.sh`. Step B had to load it: Flink throughput and detection latency, the analyst API and the MCP
endpoint, alert bursts through the case queue, the effect of the worker's settings, and whether the budgets hold under
load; plus one real-model sample for the real alert-to-report latency. 9A had also left a number to explain: a
detection p95 of 12.4 s.

## Decision

1. **Gatling 3.16.0 in a module of its own (`load-test`)**, Java DSL, `gatling-maven-plugin` 4.21.12, enforcer
   `no-spring`. The build compiles the simulations and runs none; `gatling:test` runs one by hand against the stack.
   - `AnalystApiSimulation`: one virtual user = one analyst visit (realm token, the queue awaiting decision, one case's
     detail with its ETag, a share of decisions with `If-Match`; 409 counts as success). Open injection model: analysts
     arrive whether or not the platform keeps up, so overload shows as latency and failures instead of a slower
     load generator.
   - `McpSimulation`: an assistant reading cases over `/mcp` (stateless JSON-RPC `tools/call`) and a searcher spending
     the regulation-search budget faster than it allows; an assertion fails the run if more searches are answered
     than the budget permits.
   - Tokens are fetched once per identity and again shortly before they expire (realm tokens live 5 minutes): the load
     is on the platform, not on Keycloak's password grant.
   - Settings in `load-test.properties`, each overridable with `-D`: no tunable numbers in code.
   Gatling over k6 or JMeter: Java like the rest of the code, simulations compile in the same build (a broken
   simulation fails CI), HTML reports per run.
2. **The stream and the queue are loaded through Kafka, not Gatling**: the simulator in steps (`--tps`), and a new
   `--burst=N` that puts N fraud scenarios on N different accounts at once. Gatling has no maintained Kafka protocol,
   and the real producer path (Avro, registry, Flink) is what has to be loaded.
3. **Numbers from the database, the dashboard for watching.** `alert` has event, detection and receipt times;
   `case_analysis` has the report time and the analysis duration. Per run and per rule, SQL gives exact percentiles;
   Prometheus histograms interpolate within buckets and mix runs. `scripts/perf/flink-rate.py` samples Flink from
   counters and Kafka offsets: Flink's own per-second meters average over a minute (the first catch-up looked like
   8,000/s instead of 90,000/s).
4. **Live scenarios happen now**: the simulator publishes each scenario transaction at its event time (a burst lasts
   18 s) instead of backdating them. A backdated burst was 6-14 s old when sent, which was 9A's 12.4 s detection p95.
5. **The simulator's population is per account**: each account draws from a generator seeded by its id, so
   `--accounts` adds or drops customers instead of changing who an existing account id is (the old shared sequence
   changed every account of the second bank with the population size, against the code's own comment).
6. **Flink checkpoints on a file system** (`checkpoint.dir` in `stream-job.properties`, default
   `~/.finsentinel/checkpoints`). Without it the job kept checkpoints in the JobManager's memory, 5 MB per subtask: at
   about 200,000 accounts every checkpoint failed and the job restarted in a loop.
7. **Defaults unchanged**: `batch-size` 2 and `poll-interval` 5 s stay the development defaults (they bound spend on a
   real model while developing); the measured effect is documented for a deployment to choose from, and `max-per-hour`
   bounds spend whatever they are.

## Evidence

All on one laptop (8 cores, 16 GB, Docker Desktop); details and every table in `docs/perf/2026-10-16-phase-9b.md`.

- **Detection** p95: 142 ms at 50 tps, 392 ms at 1,000, 254 ms at 5,000, 626 ms at 10,000 (p50 85-209 ms). Above
  10,000 tps the generator and Kafka in Docker are the limit; Flink's busiest subtask is busy 10% of the time.
  Catching up on 4 million transactions: 92,000/s at the peak, 80,000/s on average, bound by the 6 source tasks
  (6 partitions), not by CPU (2.65 cores used).
- **The platform's transaction copy** (`TransactionStoreListener` into Postgres) is the end-to-end ceiling: 3,000-4,200
  rows/s, 4,500-5,900 with 3 consumer threads, 1,300 once the table reached 8 million rows (I/O-bound in a 512 MB
  container). While it lagged, 44 analyses ran before their alert's transactions were stored and failed validation.
- **Queue** (120 alerts, fake-llm instant): default 2 / 5 s drained in 306 s, payment-to-report p95 277 s; 8 / 1 s:
  15.6 s; 32 / 1 s: 6.6 s. With a real model's pace (analysis p95 19 s), 40 alerts: 394 s, 85 s, 26 s. A worker waits
  for its whole batch, so every round lasts as long as its slowest analysis.
- **Budgets**: two instances with a shared `max-per-hour` of 511 peaked at exactly 511 in the trailing hour, 81 cases
  waited; 300 MCP searches against a budget of 200: exactly 200 answered.
- **Analyst API**: p95 14-37 ms up to 385 requests/s (200 visits/s), 0 failures; at 500 visits/s the pool of 10
  connections ran dry in `AuditFilter`, p50 15 s, 28% failed. Each request appends to its bank's hash-chained audit log
  under a lock on the chain head (ADR 0016): with two banks, two locks serialise the whole API. MCP reads: p95 15-32
  ms, 0 failures.
- **Real model** (gpt-6-luna, 23 alerts, 8 / 1 s, `max-attempts` 1): 22 reports, $0.066 ($0.0030 each), analysis
  p50 24 s / p95 38 s, payment-to-report p50 47 s / p95 107 s. One transfer case was rejected by the validator after its
  repair (no regulation search), as designed.
- `TrafficGeneratorTest` (3): population independent of size, no backdated scenario transactions, bursts on distinct
  accounts; both fixes mutation-checked (the old code fails two of them). `JobConfigTest`: checkpoint storage default.

## Consequences

- `./mvnw verify` compiles the simulations; running them needs the stack (`scripts/stack.sh up`, Flink, simulator).
- The published alert-to-report figure has two parts: Flink detects in a few hundred milliseconds; the case queue and
  the model take the rest, and under a burst the queue dominates unless the worker runs more analyses at once.
- Load tests leave many cases in the development database; drain them through the worker (profile `load`, a large
  `batch-size`) or start from a fresh database.

## Next

- **Evidence before analysis**: do not claim a case until its alerts' transactions are stored (or a bounded wait has
  passed, after which the agents are told the evidence is missing). A claim-query predicate keeps it multi-instance
  safe. **Done 2026-10-24**: the claim query skips a case while any transaction its alerts name is missing from `txn`,
  until `finsentinel.investigation.evidence-wait` (60 s) after its latest alert arrived; 0 turns the wait, and the
  missing-evidence handling below, off. A case claimed without some of them runs anyway: the brief names them, the
  report cannot clear the case while no tool has returned them (`CaseReportValidator`: missing evidence is not
  evidence of legitimacy), and the stored analysis gets an `EVIDENCE_INCOMPLETE` case event with the ids.
  `EvidenceGateTest` (not claimed until stored, every alert of the case counts, claimed after the wait, the analysis
  told and the case recorded) and `CaseReportValidatorTest`; each part fails its test when reverted. Not re-measured
  under the 9B load.
- **A worker pool instead of batches**: claim whenever a slot frees up, so one slow analysis does not hold back the
  rest; then measure 8-16 concurrent analyses with a real model against provider rate limits. (The pool is done, ADR
  0026: `batch-size` became `concurrency`, still 2. The real-model measurement is not.)
- **Audit log throughput**: append through a per-bank single writer that chains a batch of entries per transaction,
  or chain per (bank, shard) and anchor the shard heads; a bigger connection pool only moves the knee.
- **Transaction copy**: `COPY` or partitioned `txn` by day, and concurrency matched to partitions; or let the alert
  carry its transactions.
- More partitions for `txn.transactions.v2` if detection must exceed about 90,000 transactions/s.
