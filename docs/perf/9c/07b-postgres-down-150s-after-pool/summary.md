# Drill postgres-down-150s

- `docker stop finsentinel-postgres-1` at 13:44:33 UTC, exit 0
- `docker start finsentinel-postgres-1` at 13:47:03 UTC, exit 0

Fault held 150.8 s. First analysis 6.4 s after the heal; recovered (all up, backlog <= 6) after 11.5 s. Backlog peak 40. Flink restarts: 0.

Scenarios: 52/52 detected; 0 GEO_JUMP between land neighbours (no alert by design); 21 malformed sent.

Model calls: main provider failed 0, fallback answered 0, no provider answered 0; last fallback None s after the heal.

| Topic | Offsets added |
|---|---|
| case.events.v1 | 86 |
| risk.alerts.v2 | 91 |
| risk.alerts.v2.DLT | 0 |
| txn.transactions.dlq.v2 | 20 |
| txn.transactions.v2 | 5911 |

Stored in the sampled window: 5911 transactions (topic +5911), 92 alerts (topic +91).

Exact, 13:43:02-13:47:18 UTC by timestamp: 5395 transaction records (19 malformed), 5377 stored: **-1 missing**. 80 alert records, 80 alerts stored: 0 records not stored (duplicates or dead letters).

| Phase | Alerts | Not analysed | p50 s | p95 s | max s |
|---|---|---|---|---|---|
| before | 28 | 0 | 2.2 | 162.4 | 162.8 |
| during | 0 | 0 | None | None | None |
| after | 64 | 0 | 66.3 | 149.0 | 223.2 |

| Tool | Phase | Calls | Empty | p95 ms |
|---|---|---|---|---|
| getAlertTransactions | after | 60 | 0 | 158 |
| getAlertTransactions | before | 27 | 0 | 154 |
| getCustomerProfile | after | 60 | 0 | 55 |
| getCustomerProfile | before | 27 | 0 | 133 |
| searchRegulations | after | 60 | 0 | 19 |
| searchRegulations | before | 27 | 0 | 26 |

Case events: ALERT_ADDED>ANALYSIS 6, ANALYSED>AWAITING_DECISION 87, OPENED>ANALYSIS 3, REOPENED>ANALYSIS 83
FAILED cases: 0
