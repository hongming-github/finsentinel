# Drill registry-down

- `docker stop finsentinel-schema-registry-1` at 16:58:13 UTC, exit 0
- `docker start finsentinel-schema-registry-1` at 17:00:13 UTC, exit 0

Fault held 121.0 s. First analysis 9.3 s after the heal; recovered (all up, backlog <= 6) after 9.3 s. Backlog peak 4. Flink restarts: 0.

Scenarios: 51/51 detected; 0 GEO_JUMP between land neighbours (no alert by design); 26 malformed sent.

Model calls: main provider failed 0, fallback answered 0, no provider answered 0; last fallback None s after the heal.

| Topic | Offsets added |
|---|---|
| case.events.v1 | 86 |
| risk.alerts.v2 | 90 |
| risk.alerts.v2.DLT | 0 |
| txn.transactions.dlq.v2 | 25 |
| txn.transactions.v2 | 5978 |

Stored in the sampled window: 5975 transactions (topic +5978), 89 alerts (topic +90).

Exact, 16:56:42-17:00:59 UTC by timestamp: 5395 transaction records (22 malformed), 5373 stored: **0 missing**. 81 alert records, 81 alerts stored: 0 records not stored (duplicates or dead letters).

| Phase | Alerts | Not analysed | p50 s | p95 s | max s |
|---|---|---|---|---|---|
| before | 27 | 0 | 3.7 | 12.1 | 15.1 |
| during | 40 | 0 | 3.1 | 10.3 | 207.5 |
| after | 23 | 0 | 2.6 | 9.7 | 14.4 |

| Tool | Phase | Calls | Empty | p95 ms |
|---|---|---|---|---|
| getAlertTransactions | after | 22 | 0 | 10 |
| getAlertTransactions | before | 27 | 0 | 7 |
| getAlertTransactions | during | 40 | 0 | 12 |
| getCustomerProfile | after | 22 | 0 | 65 |
| getCustomerProfile | before | 27 | 0 | 75 |
| getCustomerProfile | during | 40 | 0 | 63 |
| searchRegulations | after | 22 | 0 | 11 |
| searchRegulations | before | 27 | 0 | 11 |
| searchRegulations | during | 40 | 0 | 10 |

Case events: ALERT_ADDED>ANALYSIS 2, ANALYSED>AWAITING_DECISION 88, ANALYSIS_SUPERSEDED>ANALYSIS 1, OPENED>ANALYSIS 19, REOPENED>ANALYSIS 69, UNTRUSTED_INSTRUCTIONS>ANALYSIS 1
FAILED cases: 0
