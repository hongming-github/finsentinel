# Drill control

- `true` at 15:30:02 UTC, exit 0

Fault held 0.0 s. First analysis 5.0 s after the heal; recovered (all up, backlog <= 6) after 5.0 s. Backlog peak 5. Flink restarts: 0.

Scenarios: 17/17 detected; 0 GEO_JUMP between land neighbours (no alert by design); 13 malformed sent.

Model calls: main provider failed 0, fallback answered 0, no provider answered 0; last fallback None s after the heal.

| Topic | Offsets added |
|---|---|
| case.events.v1 | 44 |
| risk.alerts.v2 | 45 |
| risk.alerts.v2.DLT | 0 |
| txn.transactions.dlq.v2 | 13 |
| txn.transactions.v2 | 3116 |

Stored in the sampled window: 3125 transactions (topic +3116), 45 alerts (topic +45).

Exact, 15:29:02-15:31:07 UTC by timestamp: 2614 transaction records (11 malformed), 2604 stored: **-1 missing**. 39 alert records, 39 alerts stored: 0 redelivered.

| Phase | Alerts | Not analysed | p50 s | p95 s | max s |
|---|---|---|---|---|---|
| before | 19 | 0 | 3.6 | 24.3 | 31.3 |
| during | 0 | 0 | None | None | None |
| after | 29 | 0 | 4.1 | 13.3 | 13.5 |

| Tool | Phase | Calls | Empty | p95 ms |
|---|---|---|---|---|
| getAlertTransactions | after | 29 | 0 | 11 |
| getAlertTransactions | before | 19 | 0 | 17 |
| searchRegulations | after | 29 | 0 | 22 |
| searchRegulations | before | 19 | 0 | 22 |

Case events: ALERT_ADDED>ANALYSIS 2, ANALYSED>AWAITING_DECISION 48, OPENED>ANALYSIS 35, REOPENED>ANALYSIS 11
FAILED cases: 0
