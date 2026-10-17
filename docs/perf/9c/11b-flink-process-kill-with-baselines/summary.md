# Drill flink-kill-with-baselines

- `kill -9 $(pgrep -f "[R]iskDetectionJob"); pkill -9 -P $(cat target/stack/flink.pid) 2>/dev/null; kill -9 $(cat target/stack/flink.pid) 2>/dev/null; true` at 18:01:31 UTC, exit 0
- `target/stack/flink-start.sh` at 18:02:05 UTC, exit 0

Fault held 30.1 s. First analysis 10.1 s after the heal; recovered (all up, backlog <= 12) after 10.1 s. Backlog peak 10. Flink restarts: 0.

Scenarios: 80/80 detected; 1 GEO_JUMP between land neighbours (no alert by design); 35 malformed sent.

Model calls: main provider failed 0, fallback answered 0, no provider answered 0; last fallback None s after the heal.

| Topic | Offsets added |
|---|---|
| case.events.v1 | 150 |
| risk.alerts.v2 | 160 |
| risk.alerts.v2.DLT | 0 |
| txn.transactions.dlq.v2 | 36 |
| txn.transactions.v2 | 9020 |

Stored in the sampled window: 8985 transactions (topic +9020), 160 alerts (topic +160).

Exact, 18:00:01-18:06:45 UTC by timestamp: 8457 transaction records (35 malformed), 8422 stored: **0 missing**. 147 alert records, 147 alerts stored: 0 records not stored (duplicates or dead letters).

| Phase | Alerts | Not analysed | p50 s | p95 s | max s |
|---|---|---|---|---|---|
| before | 39 | 0 | 3.8 | 43.8 | 73.1 |
| during | 0 | 0 | None | None | None |
| after | 121 | 6 | 3.9 | 30.8 | 57.2 |

| Tool | Phase | Calls | Empty | p95 ms |
|---|---|---|---|---|
| getAlertTransactions | after | 110 | 0 | 13 |
| getAlertTransactions | before | 34 | 0 | 37 |
| getAlertTransactions | during | 8 | 0 | 7 |
| getCustomerProfile | after | 110 | 0 | 23 |
| getCustomerProfile | before | 34 | 0 | 15 |
| getCustomerProfile | during | 8 | 0 | 10 |
| searchRegulations | after | 110 | 0 | 6 |
| searchRegulations | before | 34 | 0 | 3 |
| searchRegulations | during | 8 | 0 | 4 |

Case events: ALERT_ADDED>ANALYSIS 5, ANALYSED>AWAITING_DECISION 152, OPENED>ANALYSIS 10, REOPENED>ANALYSIS 145, UNTRUSTED_INSTRUCTIONS>ANALYSIS 2
FAILED cases: 0
