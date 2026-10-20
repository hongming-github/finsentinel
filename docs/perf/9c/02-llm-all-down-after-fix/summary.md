# Drill llm-all-down

- `curl -s -X POST 'localhost:8096/admin/behaviour?provider=openai' -d '{"errorStatus":503,"errorRate":1.0}'; curl -s -X POST 'localhost:8096/admin/behaviour?provider=groq' -d '{"errorStatus":503,"errorRate":1.0}'` at 08:23:56 UTC, exit 0
- `curl -s -X DELETE localhost:8096/admin/behaviour` at 08:25:56 UTC, exit 0

Fault held 120.4 s. First analysis 6.9 s after the heal; recovered (all up, backlog <= 7) after 508.6 s. Backlog peak 34. Flink restarts: 0.

Scenarios: 164/164 detected; 0 GEO_JUMP between land neighbours (no alert by design); 63 malformed sent.

Model calls: main provider failed 32, fallback answered 20, no provider answered 0; last fallback 25.9 s after the heal.

| Topic | Offsets added |
|---|---|
| case.events.v1 | 230 |
| risk.alerts.v2 | 250 |
| risk.alerts.v2.DLT | 0 |
| txn.transactions.dlq.v2 | 63 |
| txn.transactions.v2 | 15882 |

Stored in the sampled window: 15840 transactions (topic +15882), 250 alerts (topic +250).

Exact, 08:22:26-08:34:33 UTC by timestamp: 15324 transaction records (62 malformed), 15262 stored: **0 missing**. 238 alert records, 238 alerts stored: 0 records not stored (duplicates or dead letters).

| Phase | Alerts | Not analysed | p50 s | p95 s | max s |
|---|---|---|---|---|---|
| before | 28 | 0 | 4.2 | 205.6 | 246.4 |
| during | 34 | 0 | 147.5 | 533.1 | 603.8 |
| after | 189 | 0 | 5.1 | 174.4 | 517.1 |

| Tool | Phase | Calls | Empty | p95 ms |
|---|---|---|---|---|
| getAlertTransactions | after | 204 | 0 | 148 |
| getAlertTransactions | before | 27 | 0 | 203 |
| getCustomerProfile | after | 204 | 0 | 193 |
| getCustomerProfile | before | 27 | 0 | 127 |
| searchRegulations | after | 204 | 0 | 29 |
| searchRegulations | before | 27 | 0 | 40 |

Case events: ALERT_ADDED>ANALYSIS 16, ANALYSED>AWAITING_DECISION 230, ANALYSIS_ATTEMPT_FAILED>ANALYSIS 26, ANALYSIS_SUPERSEDED>ANALYSIS 1, OPENED>ANALYSIS 10, REOPENED>ANALYSIS 225, UNTRUSTED_INSTRUCTIONS>ANALYSIS 1
FAILED cases: 0
