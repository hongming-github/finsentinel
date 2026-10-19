# Drill llm-all-down

- `curl -s -X POST 'localhost:8096/admin/behaviour?provider=openai' -d '{"errorStatus":503,"errorRate":1.0}'; curl -s -X POST 'localhost:8096/admin/behaviour?provider=groq' -d '{"errorStatus":503,"errorRate":1.0}'` at 13:36:30 UTC, exit 0
- `curl -s -X DELETE localhost:8096/admin/behaviour` at 13:38:30 UTC, exit 0

Fault held 120.1 s. First analysis 5.7 s after the heal; recovered (all up, backlog <= 4) after 107.0 s. Backlog peak 36. Flink restarts: 0.

Scenarios: 66/66 detected; 0 GEO_JUMP between land neighbours (no alert by design); 28 malformed sent.

Model calls: main provider failed 53, fallback answered 30, no provider answered 0; last fallback 14.7 s after the heal.

| Topic | Offsets added |
|---|---|
| case.events.v1 | 113 |
| risk.alerts.v2 | 124 |
| risk.alerts.v2.DLT | 0 |
| txn.transactions.dlq.v2 | 27 |
| txn.transactions.v2 | 7221 |

Stored in the sampled window: 7213 transactions (topic +7221), 123 alerts (topic +124).

Exact, 13:35:00-13:40:20 UTC by timestamp: 6675 transaction records (25 malformed), 6650 stored: **0 missing**. 112 alert records, 112 alerts stored: 0 records not stored (duplicates or dead letters).

| Phase | Alerts | Not analysed | p50 s | p95 s | max s |
|---|---|---|---|---|---|
| before | 27 | 0 | 2.6 | 4.7 | 4.7 |
| during | 42 | 0 | 70.2 | 201.4 | 222.6 |
| after | 56 | 0 | 2.6 | 5.2 | 88.7 |

| Tool | Phase | Calls | Empty | p95 ms |
|---|---|---|---|---|
| getAlertTransactions | after | 86 | 0 | 129 |
| getAlertTransactions | before | 28 | 0 | 133 |
| getCustomerProfile | after | 86 | 0 | 164 |
| getCustomerProfile | before | 28 | 0 | 105 |
| searchRegulations | after | 86 | 0 | 32 |
| searchRegulations | before | 28 | 0 | 23 |

Case events: ALERT_ADDED>ANALYSIS 10, ANALYSED>AWAITING_DECISION 114, ANALYSIS_ATTEMPT_FAILED>ANALYSIS 48, OPENED>ANALYSIS 2, REOPENED>ANALYSIS 113
FAILED cases: 0
