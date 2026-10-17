# Drill flink-taskmanager-kill

- `docker kill finsentinel-flink-taskmanager-1` at 18:12:50 UTC, exit 0
- `docker start finsentinel-flink-taskmanager-1` at 18:13:20 UTC, exit 0

Fault held 30.2 s. First analysis 10.0 s after the heal; recovered (all up, backlog <= 23) after 10.0 s. Backlog peak 21. Flink restarts: 1.

Scenarios: 38/38 detected; 0 GEO_JUMP between land neighbours (no alert by design); 28 malformed sent.

Model calls: main provider failed 0, fallback answered 0, no provider answered 0; last fallback None s after the heal.

| Topic | Offsets added |
|---|---|
| case.events.v1 | 87 |
| risk.alerts.v2 | 74 |
| risk.alerts.v2.DLT | 0 |
| txn.transactions.dlq.v2 | 28 |
| txn.transactions.v2 | 5364 |

Stored in the sampled window: 5357 transactions (topic +5364), 74 alerts (topic +74).

Exact, 18:11:20-18:15:07 UTC by timestamp: 4763 transaction records (26 malformed), 4737 stored: **0 missing**. 65 alert records, 64 alerts stored: 1 records not stored (duplicates or dead letters).

| Phase | Alerts | Not analysed | p50 s | p95 s | max s |
|---|---|---|---|---|---|
| before | 30 | 0 | 4.2 | 91.5 | 94.8 |
| during | 0 | 0 | None | None | None |
| after | 46 | 0 | 4.8 | 51.5 | 74.0 |

| Tool | Phase | Calls | Empty | p95 ms |
|---|---|---|---|---|
| getAlertTransactions | after | 41 | 0 | 47 |
| getAlertTransactions | before | 34 | 0 | 55 |
| getAlertTransactions | during | 12 | 0 | 55 |
| getCustomerProfile | after | 41 | 0 | 82 |
| getCustomerProfile | before | 34 | 0 | 55 |
| getCustomerProfile | during | 12 | 0 | 62 |
| searchRegulations | after | 41 | 0 | 14 |
| searchRegulations | before | 34 | 0 | 8 |
| searchRegulations | during | 12 | 0 | 15 |

Case events: ALERT_ADDED>ANALYSIS 8, ANALYSED>AWAITING_DECISION 87, OPENED>ANALYSIS 2, REOPENED>ANALYSIS 66, UNTRUSTED_INSTRUCTIONS>ANALYSIS 1
FAILED cases: 0
