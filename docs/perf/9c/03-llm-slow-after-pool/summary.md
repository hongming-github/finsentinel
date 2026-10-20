# Drill llm-slow

- `curl -s -X POST 'localhost:8096/admin/behaviour?provider=openai' -d '{"latencyMs":5000}'` at 13:30:10 UTC, exit 0
- `curl -s -X DELETE localhost:8096/admin/behaviour` at 13:32:10 UTC, exit 0

Fault held 120.1 s. First analysis 13.1 s after the heal; recovered (all up, backlog <= 4) after 13.1 s. Backlog peak 24. Flink restarts: 0.

Scenarios: 37/37 detected; 0 GEO_JUMP between land neighbours (no alert by design); 25 malformed sent.

Model calls: main provider failed 0, fallback answered 0, no provider answered 0; last fallback None s after the heal.

| Topic | Offsets added |
|---|---|
| case.events.v1 | 76 |
| risk.alerts.v2 | 79 |
| risk.alerts.v2.DLT | 0 |
| txn.transactions.dlq.v2 | 25 |
| txn.transactions.v2 | 5407 |

Stored in the sampled window: 5423 transactions (topic +5407), 78 alerts (topic +79).

Exact, 13:28:40-13:32:30 UTC by timestamp: 4856 transaction records (22 malformed), 4834 stored: **0 missing**. 70 alert records, 70 alerts stored: 0 records not stored (duplicates or dead letters).

| Phase | Alerts | Not analysed | p50 s | p95 s | max s |
|---|---|---|---|---|---|
| before | 24 | 0 | 3.1 | 8.4 | 19.9 |
| during | 39 | 0 | 24.7 | 104.9 | 124.2 |
| after | 17 | 0 | 1.8 | 4.7 | 5.0 |

| Tool | Phase | Calls | Empty | p95 ms |
|---|---|---|---|---|
| getAlertTransactions | after | 40 | 0 | 432 |
| getAlertTransactions | before | 22 | 0 | 139 |
| getAlertTransactions | during | 14 | 0 | 302 |
| getCustomerProfile | after | 40 | 0 | 139 |
| getCustomerProfile | before | 22 | 0 | 208 |
| getCustomerProfile | during | 14 | 0 | 251 |
| searchRegulations | after | 40 | 0 | 50 |
| searchRegulations | before | 22 | 0 | 53 |
| searchRegulations | during | 14 | 0 | 56 |

Case events: ALERT_ADDED>ANALYSIS 3, ANALYSED>AWAITING_DECISION 76, OPENED>ANALYSIS 1, REOPENED>ANALYSIS 76, UNTRUSTED_INSTRUCTIONS>ANALYSIS 2
FAILED cases: 0
