# 0004. Investigation work queue in Postgres, with fencing and a spend circuit breaker

- Status: accepted
- Date: 2026-09-30

## Context

Every stored alert needs one LLM investigation. Investigations take seconds, cost money, can fail, and the
platform may crash or be scaled to several instances. Options were an in-memory executor fed by the Kafka
listener, a second Kafka topic, or a queue table.

## Decision

- The alert table is the queue: `status` (NEW → INVESTIGATING → REPORTED / FAILED), `attempts`, `claimed_at`.
- Workers claim with `UPDATE ... WHERE alert_id IN (SELECT ... FOR UPDATE SKIP LOCKED) RETURNING *`, HIGH
  severity first. Several instances can poll without handing out the same alert twice.
- A claim older than `claim-timeout` is taken back, so a crashed worker's alerts are retried.
- `claimed_at` is a **fencing token**: a worker writes its report only if the row still carries the
  `claimed_at` it claimed with. A worker that hung past its timeout cannot overwrite the newer attempt.
- Failures return the alert to NEW until `max-attempts`, then FAILED. (Since ADR 0026, on cases: each failed attempt
  waits a growing pause, `next_attempt_at`, before the next claim.)
- **Spend circuit breaker:** at most `max-per-hour` investigations start per rolling hour. When it is
  reached, alerts stay queued and a warning is logged.

## Running several instances

`@Scheduled` is only a per-instance alarm clock; nothing that affects correctness is held in memory.

- Claims use `FOR UPDATE SKIP LOCKED`, so concurrent instances get disjoint alerts without waiting on each other.
- The budget check and the claim run in one transaction under `pg_advisory_xact_lock`. Without it, instances read
  "N left" at the same moment and overshoot: `ConcurrentClaimTest` claimed 12-13 against a budget of 10 with the
  lock removed, exactly 10 with it. The lock is held for milliseconds.
- Waiting for the lock is bounded by a transaction-local `lock_timeout` (`claim-lock-timeout`, 2 s). An instance
  that stalls while holding it cannot stall the others: they skip the poll and retry. Postgres reports the timeout
  as SQLSTATE 55P03, which Spring 7 does not translate to `CannotAcquireLockException`, so the worker checks the
  state directly.
- This is a distributed lock, held in Postgres. It suits this case better than a Redis lock because it lives in the
  same transaction as the data it protects: it is released the moment the holder's connection dies, it cannot
  expire under a holder that is merely slow, and the protected write commits together with the release.
- ShedLock-style "one instance runs the job" was rejected: every instance should investigate in parallel; only the
  millisecond-long budget check needs to be serial.
- Redis is not needed for this. Postgres already provides the mutual exclusion, and a Redis lock would still need
  the fencing token for correctness. Redis earns its place for cross-service rate limits and the semantic cache
  (phase 5). A single queue table stops scaling at thousands of claims per second; LLM-bound investigations run at
  a few per second.

## Consequences

- Durable and restart-safe with no extra infrastructure; the state is queryable with SQL.
- `batch-size` bounds concurrent LLM calls per instance.
- Polling adds up to one poll interval of latency (5 s), acceptable for analyst workflows.
- Found the hard way: a simulator bug produced ~640 bogus alerts in seconds, and without a budget each one
  would have become an LLM call. An upstream fault must not turn into an unbounded bill. Phase 5 extends this
  into per-tenant budgets and model routing in the LLM gateway.
