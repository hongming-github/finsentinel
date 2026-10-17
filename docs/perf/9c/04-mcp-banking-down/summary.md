# Drill mcp-banking-down

- `kill $(cat target/stack/mcp-banking.pid)` at 16:15:22 UTC, exit 0
- `scripts/stack.sh up 2>&1 | grep -E "^(up|died|slow)"` at 16:17:30 UTC, exit 0

Fault held 120.0 s. First analysis 5.2 s after the heal; recovered (all up, backlog <= 8) after 10.2 s. Backlog peak 6. Flink restarts: 0.

Scenarios: 49/49 detected; 1 GEO_JUMP between land neighbours (no alert by design); 19 malformed sent.

Model calls: main provider failed 0, fallback answered 0, no provider answered 0; last fallback None s after the heal.

| Topic | Offsets added |
|---|---|
| case.events.v1 | 101 |
| risk.alerts.v2 | 100 |
| risk.alerts.v2.DLT | 0 |
| txn.transactions.dlq.v2 | 19 |
| txn.transactions.v2 | 5978 |

Stored in the sampled window: 5957 transactions (topic +5978), 99 alerts (topic +100).

Exact, 16:13:52-16:18:05 UTC by timestamp: 5379 transaction records (17 malformed), 5362 stored: **0 missing**. 91 alert records, 91 alerts stored: 0 redelivered.

| Phase | Alerts | Not analysed | p50 s | p95 s | max s |
|---|---|---|---|---|---|
| before | 33 | 0 | 4.5 | 23.9 | 43.1 |
| during | 41 | 0 | 3.9 | 15.8 | 17.8 |
| after | 26 | 0 | 3.7 | 11.8 | 13.5 |

| Tool | Phase | Calls | Empty | p95 ms |
|---|---|---|---|---|
| getAlertTransactions | after | 25 | 0 | 10 |
| getAlertTransactions | before | 34 | 0 | 12 |
| getAlertTransactions | during | 42 | 0 | 4 |
| getCustomerProfile | after | 25 | 2 | 350 |
| getCustomerProfile | before | 34 | 0 | 96 |
| getCustomerProfile | during | 42 | 42 | 24 |
| searchRegulations | after | 25 | 0 | 21 |
| searchRegulations | before | 34 | 0 | 25 |
| searchRegulations | during | 42 | 0 | 14 |

Case events: ALERT_ADDED>ANALYSIS 2, ANALYSED>AWAITING_DECISION 101, OPENED>ANALYSIS 34, REOPENED>ANALYSIS 64, UNTRUSTED_INSTRUCTIONS>ANALYSIS 2
FAILED cases: 0
