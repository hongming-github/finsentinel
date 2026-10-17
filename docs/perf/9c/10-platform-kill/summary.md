# Drill platform-kill

- `target/stack/kill-platform.sh` at 17:34:26 UTC, exit 0
- `curl -s -X DELETE localhost:8096/admin/behaviour; target/stack/start-platform.sh` at 17:34:37 UTC, exit 0

Fault held 20.7 s. First analysis 14.5 s after the heal; recovered (all up, backlog <= 8) after 14.5 s. Backlog peak 14. Flink restarts: 0.

Scenarios: 27/27 detected; 0 GEO_JUMP between land neighbours (no alert by design); 23 malformed sent.

Model calls: main provider failed 0, fallback answered 0, no provider answered 0; last fallback None s after the heal.

| Topic | Offsets added |
|---|---|
| case.events.v1 | 60 |
| risk.alerts.v2 | 66 |
| risk.alerts.v2.DLT | 0 |
| txn.transactions.dlq.v2 | 23 |
| txn.transactions.v2 | 4470 |

Stored in the sampled window: 4446 transactions (topic +4470), 66 alerts (topic +66).

Exact, 17:32:10-17:35:17 UTC by timestamp: 3876 transaction records (21 malformed), 3858 stored: **-3 missing**. 55 alert records, 55 alerts stored: 0 records not stored (duplicates or dead letters).

| Phase | Alerts | Not analysed | p50 s | p95 s | max s |
|---|---|---|---|---|---|
| before | 37 | 0 | 2.7 | 15.2 | 57.1 |
| during | 5 | 2 | 23.9 | 26.8 | 26.8 |
| after | 25 | 8 | 17.1 | 59.0 | 59.0 |

| Tool | Phase | Calls | Empty | p95 ms |
|---|---|---|---|---|
| getAlertTransactions | after | 18 | 0 | 25 |
| getAlertTransactions | before | 40 | 0 | 12 |
| getAlertTransactions | during | 2 | 0 | 8 |
| getCustomerProfile | after | 18 | 0 | 807 |
| getCustomerProfile | before | 40 | 0 | 50 |
| getCustomerProfile | during | 2 | 0 | 50 |
| searchRegulations | after | 18 | 0 | 216 |
| searchRegulations | before | 40 | 0 | 13 |
| searchRegulations | during | 2 | 0 | 10 |

Case events: ANALYSED>AWAITING_DECISION 60, OPENED>ANALYSIS 9, REOPENED>ANALYSIS 58
FAILED cases: 0
