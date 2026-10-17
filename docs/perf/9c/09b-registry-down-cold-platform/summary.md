# Drill registry-down-cold

- `docker stop finsentinel-schema-registry-1 && kill $(cat target/stack/platform.pid) && sleep 5 && target/stack/start-platform.sh` at 17:05:49 UTC, exit 0
- `docker start finsentinel-schema-registry-1` at 17:07:50 UTC, exit 0

Fault held 248.9 s. First analysis 327.0 s after the heal; recovered (all up, backlog <= 8) after None s. Backlog peak 134. Flink restarts: 0.

Scenarios: 368/368 detected; 1 GEO_JUMP between land neighbours (no alert by design); 141 malformed sent.

Model calls: main provider failed 0, fallback answered 0, no provider answered 0; last fallback None s after the heal.

| Topic | Offsets added |
|---|---|
| case.events.v1 | 398 |
| risk.alerts.v2 | 519 |
| risk.alerts.v2.DLT | 0 |
| txn.transactions.dlq.v2 | 141 |
| txn.transactions.v2 | 33595 |

Stored in the sampled window: 33432 transactions (topic +33595), 519 alerts (topic +519).

Exact, 17:02:10-17:28:21 UTC by timestamp: 33015 transaction records (139 malformed), 32876 stored: **0 missing**. 512 alert records, 512 alerts stored: 0 records not stored (duplicates or dead letters).

| Phase | Alerts | Not analysed | p50 s | p95 s | max s |
|---|---|---|---|---|---|
| before | 36 | 0 | 3.9 | 66.2 | 1500.6 |
| during | 0 | 0 | None | None | None |
| after | 485 | 33 | 65.7 | 977.9 | 1436.8 |

| Tool | Phase | Calls | Empty | p95 ms |
|---|---|---|---|---|
| getAlertTransactions | after | 366 | 0 | 15 |
| getAlertTransactions | before | 35 | 0 | 5 |
| getCustomerProfile | after | 366 | 0 | 83 |
| getCustomerProfile | before | 35 | 0 | 111 |
| searchRegulations | after | 366 | 0 | 14 |
| searchRegulations | before | 35 | 0 | 5 |

Case events: ALERT_ADDED>ANALYSIS 88, ANALYSED>AWAITING_DECISION 401, OPENED>ANALYSIS 67, REOPENED>ANALYSIS 366, UNTRUSTED_INSTRUCTIONS>ANALYSIS 3
FAILED cases: 0
