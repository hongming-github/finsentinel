# Drill systems-slow

- `curl -s -X POST localhost:8093/__admin/settings -d '{"fixedDelay":10000}'` at 13:05:59 UTC, exit 0
- `curl -s -X POST localhost:8093/__admin/settings -d '{"fixedDelay":0}'` at 13:07:59 UTC, exit 0

Fault held 120.3 s. First analysis 5.5 s after the heal; recovered (all up, backlog <= 5) after 5.5 s. Backlog peak 7. Flink restarts: 0.

Scenarios: 40/40 detected; 0 GEO_JUMP between land neighbours (no alert by design); 18 malformed sent.

Model calls: main provider failed 0, fallback answered 0, no provider answered 0; last fallback None s after the heal.

| Topic | Offsets added |
|---|---|
| case.events.v1 | 76 |
| risk.alerts.v2 | 78 |
| risk.alerts.v2.DLT | 0 |
| txn.transactions.dlq.v2 | 18 |
| txn.transactions.v2 | 5232 |

Stored in the sampled window: 5195 transactions (topic +5232), 79 alerts (topic +78).

Exact, 13:04:29-13:08:09 UTC by timestamp: 4683 transaction records (17 malformed), 4665 stored: **1 missing**. 71 alert records, 71 alerts stored: 0 records not stored (duplicates or dead letters).

| Phase | Alerts | Not analysed | p50 s | p95 s | max s |
|---|---|---|---|---|---|
| before | 27 | 0 | 4.3 | 15.9 | 44.2 |
| during | 41 | 0 | 7.9 | 42.3 | 51.0 |
| after | 12 | 0 | 3.0 | 12.8 | 18.6 |

| Tool | Phase | Calls | Empty | p95 ms |
|---|---|---|---|---|
| getAlertTransactions | after | 14 | 0 | 239 |
| getAlertTransactions | before | 25 | 0 | 1074 |
| getAlertTransactions | during | 37 | 0 | 308 |
| getCustomerProfile | after | 14 | 2 | 162 |
| getCustomerProfile | before | 25 | 0 | 184 |
| getCustomerProfile | during | 37 | 36 | 5274 |
| searchRegulations | after | 14 | 0 | 23 |
| searchRegulations | before | 25 | 0 | 97 |
| searchRegulations | during | 37 | 0 | 36 |

Case events: ALERT_ADDED>ANALYSIS 3, ANALYSED>AWAITING_DECISION 76, OPENED>ANALYSIS 2, REOPENED>ANALYSIS 75
FAILED cases: 0
