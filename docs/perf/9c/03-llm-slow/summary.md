# Drill llm-slow

- `curl -s -X POST 'localhost:8096/admin/behaviour?provider=openai' -d '{"latencyMs":5000}'` at 15:54:18 UTC, exit 0
- `curl -s -X DELETE localhost:8096/admin/behaviour` at 15:56:18 UTC, exit 0

Fault held 120.0 s. First analysis 5.1 s after the heal; recovered (all up, backlog <= 5) after 390.6 s. Backlog peak 29. Flink restarts: 0.

Scenarios: 144/144 detected; 2 GEO_JUMP between land neighbours (no alert by design); 55 malformed sent.

Model calls: main provider failed 0, fallback answered 0, no provider answered 0; last fallback None s after the heal.

| Topic | Offsets added |
|---|---|
| case.events.v1 | 216 |
| risk.alerts.v2 | 227 |
| risk.alerts.v2.DLT | 0 |
| txn.transactions.dlq.v2 | 55 |
| txn.transactions.v2 | 13945 |

Stored in the sampled window: 13891 transactions (topic +13945), 226 alerts (topic +227).

Exact, 15:52:48-16:03:24 UTC by timestamp: 13428 transaction records (51 malformed), 13376 stored: **1 missing**. 220 alert records, 220 alerts stored: 0 redelivered.

| Phase | Alerts | Not analysed | p50 s | p95 s | max s |
|---|---|---|---|---|---|
| before | 30 | 0 | 4.0 | 16.2 | 19.3 |
| during | 43 | 0 | 92.3 | 251.5 | 287.5 |
| after | 155 | 0 | 4.4 | 157.1 | 228.1 |

| Tool | Phase | Calls | Empty | p95 ms |
|---|---|---|---|---|
| getAlertTransactions | after | 180 | 0 | 7 |
| getAlertTransactions | before | 28 | 0 | 8 |
| getAlertTransactions | during | 12 | 0 | 10 |
| searchRegulations | after | 180 | 0 | 8 |
| searchRegulations | before | 28 | 0 | 9 |
| searchRegulations | during | 12 | 0 | 10 |

Case events: ALERT_ADDED>ANALYSIS 10, ANALYSED>AWAITING_DECISION 218, ANALYSIS_SUPERSEDED>ANALYSIS 2, OPENED>ANALYSIS 91, REOPENED>ANALYSIS 127
FAILED cases: 0
