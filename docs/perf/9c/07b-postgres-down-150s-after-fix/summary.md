# Drill postgres-down-150s

- `docker stop finsentinel-postgres-1` at 15:46:16 UTC, exit 0
- `docker start finsentinel-postgres-1` at 15:48:46 UTC, exit 0

Fault held 150.8 s. First analysis 9.5 s after the heal; recovered (all up, backlog <= 4) after None s. Backlog peak 49. Flink restarts: 0.

Scenarios: 198/198 detected; 1 GEO_JUMP between land neighbours (no alert by design); 68 malformed sent.

Model calls: main provider failed 0, fallback answered 0, no provider answered 0; last fallback None s after the heal.

| Topic | Offsets added |
|---|---|
| case.events.v1 | 265 |
| risk.alerts.v2 | 348 |
| risk.alerts.v2.DLT | 0 |
| txn.transactions.dlq.v2 | 68 |
| txn.transactions.v2 | 18282 |

Stored in the sampled window: 18234 transactions (topic +18282), 347 alerts (topic +348).

Exact, 15:44:45-15:58:48 UTC by timestamp: 17724 transaction records (65 malformed), 17659 stored: **0 missing**. 332 alert records, 332 alerts stored: 0 records not stored (duplicates or dead letters).

| Phase | Alerts | Not analysed | p50 s | p95 s | max s |
|---|---|---|---|---|---|
| before | 26 | 0 | 3.3 | 156.7 | 159.7 |
| during | 0 | 0 | None | None | None |
| after | 322 | 22 | 8.0 | 299.3 | 698.5 |

| Tool | Phase | Calls | Empty | p95 ms |
|---|---|---|---|---|
| getAlertTransactions | after | 242 | 0 | 91 |
| getAlertTransactions | before | 24 | 0 | 23 |
| getCustomerProfile | after | 242 | 0 | 85 |
| getCustomerProfile | before | 24 | 0 | 88 |
| searchRegulations | after | 242 | 0 | 19 |
| searchRegulations | before | 24 | 0 | 23 |

Case events: ALERT_ADDED>ANALYSIS 57, ANALYSED>AWAITING_DECISION 265, ANALYSIS_SUPERSEDED>ANALYSIS 1, OPENED>ANALYSIS 15, REOPENED>ANALYSIS 276, UNTRUSTED_INSTRUCTIONS>ANALYSIS 2
FAILED cases: 0
