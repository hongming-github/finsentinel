# Drill llm-main-down

- `curl -s -X POST 'localhost:8096/admin/behaviour?provider=openai' -d '{"errorStatus":503,"errorRate":1.0}'` at 15:35:29 UTC, exit 0
- `curl -s -X DELETE localhost:8096/admin/behaviour` at 15:37:29 UTC, exit 0

Fault held 120.1 s. First analysis 5.2 s after the heal; recovered (all up, backlog <= 7) after 5.2 s. Backlog peak 5. Flink restarts: 0.

Scenarios: 39/39 detected; 1 GEO_JUMP between land neighbours (no alert by design); 29 malformed sent.

Model calls: main provider failed 0, fallback answered 0, no provider answered 0; last fallback None s after the heal.

| Topic | Offsets added |
|---|---|
| case.events.v1 | 83 |
| risk.alerts.v2 | 81 |
| risk.alerts.v2.DLT | 0 |
| txn.transactions.dlq.v2 | 29 |
| txn.transactions.v2 | 5664 |

Stored in the sampled window: 5632 transactions (topic +5664), 81 alerts (topic +81).

Exact, 15:33:59-15:38:04 UTC by timestamp: 5114 transaction records (27 malformed), 5087 stored: **0 missing**. 74 alert records, 74 alerts stored: 0 redelivered.

| Phase | Alerts | Not analysed | p50 s | p95 s | max s |
|---|---|---|---|---|---|
| before | 25 | 0 | 3.1 | 21.1 | 27.4 |
| during | 39 | 0 | 2.5 | 15.1 | 19.1 |
| after | 20 | 0 | 4.5 | 19.9 | 24.2 |

| Tool | Phase | Calls | Empty | p95 ms |
|---|---|---|---|---|
| getAlertTransactions | after | 20 | 0 | 3 |
| getAlertTransactions | before | 26 | 0 | 15 |
| getAlertTransactions | during | 39 | 0 | 7 |
| searchRegulations | after | 20 | 0 | 8 |
| searchRegulations | before | 26 | 0 | 19 |
| searchRegulations | during | 39 | 0 | 7 |

Case events: ALERT_ADDED>ANALYSIS 1, ANALYSED>AWAITING_DECISION 85, OPENED>ANALYSIS 50, REOPENED>ANALYSIS 33
FAILED cases: 0
