# Drill registry-down-cold

- `docker stop finsentinel-schema-registry-1 && kill $(cat target/stack/platform.pid) && sleep 5 && target/stack/start-platform.sh` at 07:25:27 UTC, exit 0
- `docker start finsentinel-schema-registry-1` at 07:29:23 UTC, exit 0

Fault held 261.6 s. First analysis 36.2 s after the heal; recovered (all up, backlog <= 11) after None s. Backlog peak 72. Flink restarts: 0.

Scenarios: 224/224 detected; 1 GEO_JUMP between land neighbours (no alert by design); 80 malformed sent.

Model calls: main provider failed 0, fallback answered 0, no provider answered 0; last fallback None s after the heal.

| Topic | Offsets added |
|---|---|
| case.events.v1 | 244 |
| risk.alerts.v2 | 326 |
| risk.alerts.v2.DLT | 0 |
| txn.transactions.dlq.v2 | 79 |
| txn.transactions.v2 | 20498 |

Stored in the sampled window: 20469 transactions (topic +20498), 327 alerts (topic +326).

Exact, 07:23:30-07:39:23 UTC by timestamp: 20009 transaction records (76 malformed), 19933 stored: **0 missing**. 317 alert records, 317 alerts stored: 0 records not stored (duplicates or dead letters).

| Phase | Alerts | Not analysed | p50 s | p95 s | max s |
|---|---|---|---|---|---|
| before | 26 | 0 | 5.2 | 54.8 | 57.1 |
| during | 1 | 0 | 41.5 | 41.5 | 41.5 |
| after | 301 | 48 | 16.8 | 519.9 | 766.9 |

| Tool | Phase | Calls | Empty | p95 ms |
|---|---|---|---|---|
| getAlertTransactions | after | 214 | 0 | 330 |
| getAlertTransactions | before | 30 | 0 | 291 |
| getAlertTransactions | during | 2 | 0 | 586 |
| getCustomerProfile | after | 214 | 0 | 315 |
| getCustomerProfile | before | 30 | 0 | 314 |
| getCustomerProfile | during | 2 | 0 | 2569 |
| searchRegulations | after | 214 | 0 | 74 |
| searchRegulations | before | 30 | 0 | 148 |
| searchRegulations | during | 2 | 0 | 1339 |

Case events: ALERT_ADDED>ANALYSIS 35, ANALYSED>AWAITING_DECISION 246, OPENED>ANALYSIS 13, REOPENED>ANALYSIS 280, UNTRUSTED_INSTRUCTIONS>ANALYSIS 2
FAILED cases: 0
