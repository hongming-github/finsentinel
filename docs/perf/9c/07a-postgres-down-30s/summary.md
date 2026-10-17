# Drill postgres-down

- `docker stop finsentinel-postgres-1` at 16:33:13 UTC, exit 0
- `docker start finsentinel-postgres-1` at 16:33:43 UTC, exit 0

Fault held 30.6 s. First analysis 9.5 s after the heal; recovered (all up, backlog <= 7) after None s. Backlog peak 18. Flink restarts: 0.

Scenarios: 29/29 detected; 0 GEO_JUMP between land neighbours (no alert by design); 9 malformed sent.

Model calls: main provider failed 0, fallback answered 0, no provider answered 0; last fallback None s after the heal.

| Topic | Offsets added |
|---|---|
| case.events.v1 | 54 |
| risk.alerts.v2 | 66 |
| risk.alerts.v2.DLT | 0 |
| txn.transactions.dlq.v2 | 9 |
| txn.transactions.v2 | 4030 |

Stored in the sampled window: 4022 transactions (topic +4030), 66 alerts (topic +66).

Exact, 16:31:42-16:34:24 UTC by timestamp: 3451 transaction records (8 malformed), 3443 stored: **0 missing**. 58 alert records, 58 alerts stored: 0 redelivered.

| Phase | Alerts | Not analysed | p50 s | p95 s | max s |
|---|---|---|---|---|---|
| before | 28 | 0 | 4.5 | 36.6 | 74.4 |
| during | 0 | 0 | None | None | None |
| after | 38 | 0 | 32.4 | 129.1 | 143.5 |

| Tool | Phase | Calls | Empty | p95 ms |
|---|---|---|---|---|
| getAlertTransactions | after | 26 | 0 | 12 |
| getAlertTransactions | before | 30 | 0 | 12 |
| getCustomerProfile | after | 26 | 0 | 122 |
| getCustomerProfile | before | 30 | 0 | 68 |
| searchRegulations | after | 26 | 0 | 12 |
| searchRegulations | before | 30 | 0 | 9 |

Case events: ALERT_ADDED>ANALYSIS 1, ANALYSED>AWAITING_DECISION 56, OPENED>ANALYSIS 19, REOPENED>ANALYSIS 46, UNTRUSTED_INSTRUCTIONS>ANALYSIS 1
FAILED cases: 0
