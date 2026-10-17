# Drill kafka-down

- `docker stop finsentinel-kafka-1` at 16:53:29 UTC, exit 0
- `docker start finsentinel-kafka-1` at 16:54:29 UTC, exit 0

Fault held 61.9 s. First analysis 13.4 s after the heal; recovered (all up, backlog <= 6) after 13.4 s. Backlog peak 6. Flink restarts: 0.

Scenarios: 23/23 detected; 0 GEO_JUMP between land neighbours (no alert by design); 12 malformed sent.

Model calls: main provider failed 0, fallback answered 0, no provider answered 0; last fallback None s after the heal.

| Topic | Offsets added |
|---|---|
| case.events.v1 | 55 |
| risk.alerts.v2 | 59 |
| risk.alerts.v2.DLT | 0 |
| txn.transactions.dlq.v2 | 12 |
| txn.transactions.v2 | 3355 |

Stored in the sampled window: 3362 transactions (topic +3355), 60 alerts (topic +59).

Exact, 16:51:57-16:55:14 UTC by timestamp: 2754 transaction records (10 malformed), 2744 stored: **0 missing**. 46 alert records, 46 alerts stored: 0 redelivered.

| Phase | Alerts | Not analysed | p50 s | p95 s | max s |
|---|---|---|---|---|---|
| before | 31 | 0 | 3.2 | 9.6 | 11.7 |
| during | 0 | 0 | None | None | None |
| after | 29 | 3 | 3.6 | 15.5 | 64.0 |

| Tool | Phase | Calls | Empty | p95 ms |
|---|---|---|---|---|
| getAlertTransactions | after | 23 | 0 | 8 |
| getAlertTransactions | before | 30 | 0 | 8 |
| getAlertTransactions | during | 2 | 0 | 1 |
| getCustomerProfile | after | 23 | 0 | 160 |
| getCustomerProfile | before | 30 | 0 | 81 |
| getCustomerProfile | during | 2 | 0 | 22 |
| searchRegulations | after | 23 | 0 | 8 |
| searchRegulations | before | 30 | 0 | 28 |
| searchRegulations | during | 2 | 0 | 5 |

Case events: ANALYSED>AWAITING_DECISION 55, OPENED>ANALYSIS 13, REOPENED>ANALYSIS 47
FAILED cases: 0
