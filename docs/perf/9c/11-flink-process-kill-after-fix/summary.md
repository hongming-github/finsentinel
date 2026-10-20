# Drill flink-process-kill

- `kill -9 $(pgrep -f "[R]iskDetectionJob"); pkill -9 -P $(cat target/stack/flink.pid) 2>/dev/null; kill -9 $(cat target/stack/flink.pid) 2>/dev/null; true` at 14:08:40 UTC, exit 0
- `target/stack/flink-start.sh` at 14:09:14 UTC, exit 0

Fault held 30.1 s. First analysis 10.1 s after the heal; recovered (all up, backlog <= 5) after 10.1 s. Backlog peak 4. Flink restarts: 0.

Scenarios: 203/203 detected; 1 GEO_JUMP between land neighbours (no alert by design); 78 malformed sent.

Model calls: main provider failed 0, fallback answered 0, no provider answered 0; last fallback None s after the heal.

| Topic | Offsets added |
|---|---|
| case.events.v1 | 291 |
| risk.alerts.v2 | 296 |
| risk.alerts.v2.DLT | 0 |
| txn.transactions.dlq.v2 | 79 |
| txn.transactions.v2 | 19170 |

Stored in the sampled window: 19132 transactions (topic +19170), 294 alerts (topic +296).

Exact, 14:07:10-14:21:54 UTC by timestamp: 18649 transaction records (75 malformed), 18574 stored: **0 missing**. 288 alert records, 286 alerts stored: 2 records not stored (duplicates or dead letters).

| Phase | Alerts | Not analysed | p50 s | p95 s | max s |
|---|---|---|---|---|---|
| before | 29 | 0 | 2.1 | 5.3 | 5.4 |
| during | 0 | 0 | None | None | None |
| after | 266 | 0 | 2.9 | 5.2 | 47.8 |

| Tool | Phase | Calls | Empty | p95 ms |
|---|---|---|---|---|
| getAlertTransactions | after | 262 | 0 | 100 |
| getAlertTransactions | before | 29 | 0 | 158 |
| getAlertTransactions | during | 1 | 0 | 7 |
| getCustomerProfile | after | 262 | 0 | 69 |
| getCustomerProfile | before | 29 | 0 | 74 |
| getCustomerProfile | during | 1 | 0 | 11 |
| searchRegulations | after | 262 | 0 | 16 |
| searchRegulations | before | 29 | 0 | 16 |
| searchRegulations | during | 1 | 0 | 3 |

Case events: ALERT_ADDED>ANALYSIS 2, ANALYSED>AWAITING_DECISION 292, OPENED>ANALYSIS 3, REOPENED>ANALYSIS 290, UNTRUSTED_INSTRUCTIONS>ANALYSIS 3
FAILED cases: 0
