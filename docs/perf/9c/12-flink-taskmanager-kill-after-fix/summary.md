# Drill flink-taskmanager-kill

- `docker kill finsentinel-flink-taskmanager-1` at 14:39:11 UTC, exit 0
- `docker rm -f finsentinel-flink-taskmanager-1 && docker compose -f deploy/docker-compose.yml --profile stream up -d flink-taskmanager` at 14:39:43 UTC, exit 0

Fault held 30.3 s. First analysis 19.9 s after the heal; recovered (all up, backlog <= 5) after 19.9 s. Backlog peak 15. Flink restarts: 1.

Scenarios: 79/79 detected; 0 GEO_JUMP between land neighbours (no alert by design); 43 malformed sent.

Model calls: main provider failed 0, fallback answered 0, no provider answered 0; last fallback None s after the heal.

| Topic | Offsets added |
|---|---|
| case.events.v1 | 149 |
| risk.alerts.v2 | 153 |
| risk.alerts.v2.DLT | 0 |
| txn.transactions.dlq.v2 | 43 |
| txn.transactions.v2 | 9235 |

Stored in the sampled window: 9215 transactions (topic +9235), 151 alerts (topic +153).

Exact, 14:37:41-14:44:33 UTC by timestamp: 8684 transaction records (40 malformed), 8644 stored: **0 missing**. 148 alert records, 145 alerts stored: 3 records not stored (duplicates or dead letters).

| Phase | Alerts | Not analysed | p50 s | p95 s | max s |
|---|---|---|---|---|---|
| before | 24 | 0 | 2.5 | 4.4 | 4.4 |
| during | 0 | 0 | None | None | None |
| after | 128 | 0 | 2.7 | 22.1 | 45.0 |

| Tool | Phase | Calls | Empty | p95 ms |
|---|---|---|---|---|
| getAlertTransactions | after | 126 | 0 | 89 |
| getAlertTransactions | before | 25 | 0 | 114 |
| getAlertTransactions | during | 1 | 0 | 8 |
| getCustomerProfile | after | 126 | 0 | 86 |
| getCustomerProfile | before | 25 | 0 | 49 |
| getCustomerProfile | during | 1 | 0 | 47 |
| searchRegulations | after | 126 | 0 | 11 |
| searchRegulations | before | 25 | 0 | 11 |
| searchRegulations | during | 1 | 0 | 5 |

Case events: ALERT_ADDED>ANALYSIS 2, ANALYSED>AWAITING_DECISION 151, ANALYSIS_SUPERSEDED>ANALYSIS 1, OPENED>ANALYSIS 3, REOPENED>ANALYSIS 147
FAILED cases: 0
