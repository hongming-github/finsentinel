# Drill systems-slow

- `curl -s -X POST localhost:8093/__admin/settings -d '{"fixedDelay":10000}'` at 16:20:41 UTC, exit 0
- `curl -s -X POST localhost:8093/__admin/settings -d '{"fixedDelay":0}'` at 16:22:41 UTC, exit 0

Fault held 120.1 s. First analysis 5.1 s after the heal; recovered (all up, backlog <= 7) after 120.4 s. Backlog peak 18. Flink restarts: 0.

Scenarios: 72/72 detected; 1 GEO_JUMP between land neighbours (no alert by design); 38 malformed sent.

Model calls: main provider failed 0, fallback answered 0, no provider answered 0; last fallback None s after the heal.

| Topic | Offsets added |
|---|---|
| case.events.v1 | 120 |
| risk.alerts.v2 | 128 |
| risk.alerts.v2.DLT | 0 |
| txn.transactions.dlq.v2 | 38 |
| txn.transactions.v2 | 8227 |

Stored in the sampled window: 8185 transactions (topic +8227), 127 alerts (topic +128).

Exact, 16:19:11-16:25:17 UTC by timestamp: 7676 transaction records (37 malformed), 7639 stored: **0 missing**. 119 alert records, 119 alerts stored: 0 redelivered.

| Phase | Alerts | Not analysed | p50 s | p95 s | max s |
|---|---|---|---|---|---|
| before | 32 | 0 | 3.9 | 12.8 | 42.5 |
| during | 41 | 0 | 18.9 | 159.9 | 186.9 |
| after | 60 | 0 | 4.8 | 90.1 | 116.7 |

| Tool | Phase | Calls | Empty | p95 ms |
|---|---|---|---|---|
| getAlertTransactions | after | 67 | 0 | 9 |
| getAlertTransactions | before | 29 | 0 | 15 |
| getAlertTransactions | during | 24 | 0 | 8 |
| getCustomerProfile | after | 67 | 0 | 91 |
| getCustomerProfile | before | 29 | 0 | 116 |
| getCustomerProfile | during | 24 | 24 | 5152 |
| searchRegulations | after | 67 | 0 | 11 |
| searchRegulations | before | 29 | 0 | 16 |
| searchRegulations | during | 24 | 0 | 53 |

Case events: ALERT_ADDED>ANALYSIS 7, ANALYSED>AWAITING_DECISION 120, OPENED>ANALYSIS 43, REOPENED>ANALYSIS 83, UNTRUSTED_INSTRUCTIONS>ANALYSIS 1
FAILED cases: 0
