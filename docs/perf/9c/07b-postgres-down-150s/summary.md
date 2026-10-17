# Drill postgres-down-150s

- `docker stop finsentinel-postgres-1` at 16:38:31 UTC, exit 0
- `docker start finsentinel-postgres-1` at 16:41:01 UTC, exit 0

Fault held 150.6 s. First analysis 9.8 s after the heal; recovered (all up, backlog <= 9) after 420.4 s. Backlog peak 45. Flink restarts: 0.

Scenarios: 156/156 detected; 0 GEO_JUMP between land neighbours (no alert by design); 65 malformed sent.

Model calls: main provider failed 0, fallback answered 0, no provider answered 0; last fallback None s after the heal.

| Topic | Offsets added |
|---|---|
| case.events.v1 | 220 |
| risk.alerts.v2 | 230 |
| risk.alerts.v2.DLT | 1 |
| txn.transactions.dlq.v2 | 66 |
| txn.transactions.v2 | 15288 |

Stored in the sampled window: 15222 transactions (topic +15288), 228 alerts (topic +230).

Exact, 16:37:00-16:48:38 UTC by timestamp: 14711 transaction records (61 malformed), 14649 stored: **1 missing**. 221 alert records, 220 alerts stored: 1 redelivered.

| Phase | Alerts | Not analysed | p50 s | p95 s | max s |
|---|---|---|---|---|---|
| before | 28 | 0 | 4.5 | 42.8 | 347.2 |
| during | 1 | 0 | 181.7 | 181.7 | 181.7 |
| after | 200 | 0 | 8.9 | 269.4 | 375.2 |

| Tool | Phase | Calls | Empty | p95 ms |
|---|---|---|---|---|
| getAlertTransactions | after | 188 | 0 | 13 |
| getAlertTransactions | before | 32 | 0 | 9 |
| getCustomerProfile | after | 188 | 0 | 72 |
| getCustomerProfile | before | 32 | 0 | 89 |
| searchRegulations | after | 188 | 0 | 15 |
| searchRegulations | before | 32 | 0 | 6 |

Case events: ALERT_ADDED>ANALYSIS 9, ANALYSED>AWAITING_DECISION 220, OPENED>ANALYSIS 55, REOPENED>ANALYSIS 165, UNTRUSTED_INSTRUCTIONS>ANALYSIS 2
FAILED cases: 0
