# Drill llm-all-down

- `curl -s -X POST 'localhost:8096/admin/behaviour?provider=openai' -d '{"errorStatus":503,"errorRate":1.0}'; curl -s -X POST 'localhost:8096/admin/behaviour?provider=groq' -d '{"errorStatus":503,"errorRate":1.0}'` at 15:42:09 UTC, exit 0
- `curl -s -X DELETE localhost:8096/admin/behaviour` at 15:44:09 UTC, exit 0

Fault held 120.0 s. First analysis 5.1 s after the heal; recovered (all up, backlog <= 6) after 325.4 s. Backlog peak 28. Flink restarts: 0.

Scenarios: 123/123 detected; 3 GEO_JUMP between land neighbours (no alert by design); 55 malformed sent.

Model calls: main provider failed 0, fallback answered 0, no provider answered 0; last fallback None s after the heal.

| Topic | Offsets added |
|---|---|
| case.events.v1 | 191 |
| risk.alerts.v2 | 198 |
| risk.alerts.v2.DLT | 0 |
| txn.transactions.dlq.v2 | 55 |
| txn.transactions.v2 | 12862 |

Stored in the sampled window: 12784 transactions (topic +12862), 197 alerts (topic +198).

Exact, 15:40:39-15:50:24 UTC by timestamp: 12303 transaction records (53 malformed), 12250 stored: **0 missing**. 187 alert records, 187 alerts stored: 0 redelivered.

| Phase | Alerts | Not analysed | p50 s | p95 s | max s |
|---|---|---|---|---|---|
| before | 30 | 1 | 3.2 | 176.5 | 176.7 |
| during | 34 | 2 | 125.9 | 594.9 | 930.2 |
| after | 136 | 0 | 5.2 | 115.8 | 131.5 |

| Tool | Phase | Calls | Empty | p95 ms |
|---|---|---|---|---|
| getAlertTransactions | after | 157 | 0 | 6 |
| getAlertTransactions | before | 27 | 0 | 5 |
| searchRegulations | after | 157 | 0 | 22 |
| searchRegulations | before | 27 | 0 | 22 |

Case events: ALERT_ADDED>ANALYSIS 6, ANALYSED>AWAITING_DECISION 185, ANALYSIS_ATTEMPT_FAILED>ANALYSIS 19, FAILED>FAILED 7, OPENED>ANALYSIS 118, REOPENED>ANALYSIS 76
FAILED cases: 7
