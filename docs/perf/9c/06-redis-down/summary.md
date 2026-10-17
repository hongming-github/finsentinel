# Drill redis-down

- `docker stop finsentinel-redis-1` at 16:27:58 UTC, exit 0
- `docker start finsentinel-redis-1` at 16:29:58 UTC, exit 0

Fault held 120.4 s. First analysis 9.9 s after the heal; recovered (all up, backlog <= 5) after 9.9 s. Backlog peak 6. Flink restarts: 0.

Scenarios: 48/48 detected; 0 GEO_JUMP between land neighbours (no alert by design); 24 malformed sent.

Model calls: main provider failed 0, fallback answered 0, no provider answered 0; last fallback None s after the heal.

| Topic | Offsets added |
|---|---|
| case.events.v1 | 89 |
| risk.alerts.v2 | 88 |
| risk.alerts.v2.DLT | 0 |
| txn.transactions.dlq.v2 | 24 |
| txn.transactions.v2 | 5766 |

Stored in the sampled window: 5765 transactions (topic +5766), 90 alerts (topic +88).

Exact, 16:26:27-16:30:34 UTC by timestamp: 5204 transaction records (21 malformed), 5183 stored: **0 missing**. 83 alert records, 83 alerts stored: 0 redelivered.

| Phase | Alerts | Not analysed | p50 s | p95 s | max s |
|---|---|---|---|---|---|
| before | 32 | 0 | 3.5 | 6.6 | 23.2 |
| during | 38 | 0 | 3.2 | 25.3 | 47.0 |
| after | 20 | 0 | 3.5 | 5.7 | 10.8 |

| Tool | Phase | Calls | Empty | p95 ms |
|---|---|---|---|---|
| getAlertTransactions | after | 20 | 0 | 11 |
| getAlertTransactions | before | 32 | 0 | 8 |
| getAlertTransactions | during | 37 | 0 | 21 |
| getCustomerProfile | after | 20 | 0 | 70 |
| getCustomerProfile | before | 32 | 0 | 66 |
| getCustomerProfile | during | 37 | 0 | 76 |
| searchRegulations | after | 20 | 0 | 17 |
| searchRegulations | before | 32 | 0 | 14 |
| searchRegulations | during | 37 | 0 | 38 |

Case events: ALERT_ADDED>ANALYSIS 1, ANALYSED>AWAITING_DECISION 89, OPENED>ANALYSIS 25, REOPENED>ANALYSIS 64
FAILED cases: 0
