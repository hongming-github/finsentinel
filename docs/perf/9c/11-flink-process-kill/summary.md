# Drill flink-kill

- `kill -9 $(pgrep -f "[R]iskDetectionJob"); pkill -9 -P $(cat target/stack/flink.pid) 2>/dev/null; kill -9 $(cat target/stack/flink.pid) 2>/dev/null; true` at 17:41:41 UTC, exit 0
- `target/stack/flink-start.sh` at 17:42:15 UTC, exit 0

Fault held 30.1 s. First analysis 10.1 s after the heal; recovered (all up, backlog <= 5) after 25.1 s. Backlog peak 7. Flink restarts: 0.

Scenarios: 34/37 detected, missed: LARGE_AMOUNT at 8 s, LARGE_AMOUNT at 17 s, LARGE_AMOUNT at 53 s; 0 GEO_JUMP between land neighbours (no alert by design); 20 malformed sent.

Model calls: main provider failed 0, fallback answered 0, no provider answered 0; last fallback None s after the heal.

| Topic | Offsets added |
|---|---|
| case.events.v1 | 76 |
| risk.alerts.v2 | 78 |
| risk.alerts.v2.DLT | 0 |
| txn.transactions.dlq.v2 | 22 |
| txn.transactions.v2 | 4947 |

Stored in the sampled window: 4928 transactions (topic +4947), 78 alerts (topic +78).

Exact, 17:40:11-17:43:40 UTC by timestamp: 4395 transaction records (18 malformed), 4377 stored: **0 missing**. 66 alert records, 66 alerts stored: 0 records not stored (duplicates or dead letters).

| Phase | Alerts | Not analysed | p50 s | p95 s | max s |
|---|---|---|---|---|---|
| before | 31 | 0 | 3.2 | 5.1 | 8.5 |
| during | 0 | 0 | None | None | None |
| after | 50 | 0 | 4.4 | 39.0 | 71.3 |

| Tool | Phase | Calls | Empty | p95 ms |
|---|---|---|---|---|
| getAlertTransactions | after | 44 | 0 | 9 |
| getAlertTransactions | before | 31 | 0 | 29 |
| getAlertTransactions | during | 3 | 0 | 3 |
| getCustomerProfile | after | 44 | 0 | 84 |
| getCustomerProfile | before | 31 | 0 | 39 |
| getCustomerProfile | during | 3 | 0 | 293 |
| searchRegulations | after | 44 | 0 | 10 |
| searchRegulations | before | 31 | 0 | 7 |
| searchRegulations | during | 3 | 0 | 3 |

Case events: ALERT_ADDED>ANALYSIS 3, ANALYSED>AWAITING_DECISION 78, OPENED>ANALYSIS 7, REOPENED>ANALYSIS 71, UNTRUSTED_INSTRUCTIONS>ANALYSIS 3
FAILED cases: 0
