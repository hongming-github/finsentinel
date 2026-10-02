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
- Failures return the alert to NEW until `max-attempts`, then FAILED.
- **Spend circuit breaker:** at most `max-per-hour` investigations start per rolling hour. When it is
  reached, alerts stay queued and a warning is logged.

## Consequences

- Durable and restart-safe with no extra infrastructure; the state is queryable with SQL.
- `batch-size` bounds concurrent LLM calls per instance.
- Polling adds up to one poll interval of latency (5 s), acceptable for analyst workflows.
- Found the hard way: a simulator bug produced ~640 bogus alerts in seconds, and without a budget each one
  would have become an LLM call. An upstream fault must not turn into an unbounded bill. Phase 5 extends this
  into per-tenant budgets and model routing in the LLM gateway.
