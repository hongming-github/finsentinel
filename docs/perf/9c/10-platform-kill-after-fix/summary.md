# Drill platform-kill

- `target/stack/kill-platform.sh` at 09:13:43 UTC, exit 0
- `curl -s -X DELETE localhost:8096/admin/behaviour; target/stack/start-platform.sh` at 09:13:56 UTC, exit 0

Fault held 20.6 s. First analysis 19.5 s after the heal; recovered (all up, backlog <= 7) after 19.5 s. Backlog peak 20. Flink restarts: 0.

Scenarios: 48/48 detected; 0 GEO_JUMP between land neighbours (no alert by design); 24 malformed sent.

Model calls: main provider failed 0, fallback answered 0, no provider answered 0; last fallback None s after the heal.

| Topic | Offsets added |
|---|---|
| case.events.v1 | 85 |
| risk.alerts.v2 | 97 |
| risk.alerts.v2.DLT | 0 |
| txn.transactions.dlq.v2 | 24 |
| txn.transactions.v2 | 5948 |

Stored in the sampled window: 5924 transactions (topic +5948), 99 alerts (topic +97).

Exact, 09:11:28-09:15:41 UTC by timestamp: 5361 transaction records (20 malformed), 5341 stored: **0 missing**. 90 alert records, 91 alerts stored: -1 records not stored (duplicates or dead letters).

| Phase | Alerts | Not analysed | p50 s | p95 s | max s |
|---|---|---|---|---|---|
| before | 41 | 0 | 3.6 | 42.5 | 78.7 |
| during | 5 | 0 | 31.2 | 38.8 | 38.8 |
| after | 53 | 10 | 11.3 | 111.0 | 121.8 |

| Tool | Phase | Calls | Empty | p95 ms |
|---|---|---|---|---|
| getAlertTransactions | after | 46 | 0 | 138 |
| getAlertTransactions | before | 39 | 0 | 137 |
| getAlertTransactions | during | 2 | 0 | 109 |
| getCustomerProfile | after | 46 | 0 | 151 |
| getCustomerProfile | before | 39 | 0 | 144 |
| getCustomerProfile | during | 2 | 0 | 56 |
| searchRegulations | after | 46 | 0 | 26 |
| searchRegulations | before | 39 | 0 | 32 |
| searchRegulations | during | 2 | 0 | 8 |

Case events: ANALYSED>AWAITING_DECISION 87, REOPENED>ANALYSIS 99
FAILED cases: 0
