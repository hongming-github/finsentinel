# 0003. At-least-once alert delivery with deterministic ids and an idempotent consumer

- Status: accepted
- Date: 2026-09-30

## Context

The stream job writes alerts to Kafka and the platform turns each alert into a case. Losing an alert is
unacceptable. A duplicate alert opens a duplicate case, wasting analyst time and LLM spend. Exactly-once Kafka
sinks need transactional producers, `read_committed` consumers, and delivery delays tied to checkpoint intervals.

## Decision

- The Flink sink is `AT_LEAST_ONCE`. On recovery some alerts are sent again.
- `Alert.alertId` is a name-based UUID of `tenant | account | rule | triggering txnId`. The same trigger always
  gives the same id.
- The platform inserts with `on conflict (alert_id) do nothing`, so redeliveries are no-ops.
- Unparseable messages go to a dead-letter topic on both sides (`txn.transactions.dlq.v1`, `risk.alerts.v1.DLT`),
  so one bad record cannot block a partition.

## Consequences

- No checkpoint-bound latency on alerts, and the consumer stays simple.
- Correctness depends on the id derivation staying stable. Changing it is a breaking change.
- Tested in `AlertIntakeIntegrationTest` (duplicate delivery, poison message) and `RuleEngineTest` (id stability).
