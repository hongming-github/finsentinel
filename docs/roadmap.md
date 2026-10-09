# Roadmap

Twelve weeks at 10–15 hours/week. Each phase ends with something runnable and a measured result.

| Phase | Weeks | Scope | Done when |
|---|---|---|---|
| 1. Data path ✅ | 1–2 | Simulator → Kafka → Flink rules → alerts → platform intake. DLQs, idempotent consumer. | Injected fraud shows up at `/api/tenants/{t}/alerts`; bad messages land in the DLQ |
| 2. First agent ✅ | 3–4 | Transaction store; Spring AI `ChatClient`; in-process tools (alert, transaction history, counterparty history); structured case report; evidence grounding check; Postgres work queue; hourly spend cap | One alert → one case report with the evidence it used |
| 3. RAG ✅ | 5 | Ingest public MAS guidance into pgvector; structure-aware chunking; hybrid search + RRF + LLM rerank with a retrieval eval; cited regulatory notes; per-rule playbooks | Every regulatory claim in a report cites a retrieved chunk |
| 4. MCP + orchestration ✅ | 6–7 | Spike done (ADR 0008): two layers, a Postgres case lifecycle (claims, budget, human decision, outbox) around a multi-agent analysis graph (Spring AI Alibaba Graph since ADR 0009; LangGraph4j before) with our own fenced JSON checkpoint saver. WireMock systems; `mcp-banking`, `mcp-compliance`; triage → investigator → reviewer agents; **group related alerts on one account into one case** (e.g. the return leg of a geo jump); **durable, resumable investigation state machine**; human approval API; **outbox** to ticketing (outbox to Kafka built; a ticketing consumer is not) | Kill the platform mid-investigation, restart, it resumes from the last checkpoint |
| 5. LLM gateway ✅ (A model per role, cost per case; B provider fallback, circuit breaker; C caching) | 8 | **Model routing** by task (route transfer and HIGH cases to `gpt-5.5`: 9/9 vs 8/9 for `gpt-5.4-mini` in phase 3, and the mini misses were on transfer scams; measured in 5A: no gain, off by default); rerank on a faster provider; provider fallback; rate limits; Redis ~~semantic~~ cache (semantic measured and rejected, exact-match built, ADR 0013); provider prompt caching; per-case token and cost accounting | Report shows cost per case and cache hit rate; fallback works with the primary provider down |
| 6. Tenancy + security | 9 | **Multi-tenant** isolation (data, vectors, prompts, tool permissions); Keycloak; RBAC; OAuth 2.1 on MCP; PII masking before LLM calls; prompt-injection defenses; audit log | Cross-tenant access tests fail closed; injected CRM note does not change agent behavior |
| 7. Evaluation | 10 | Golden dataset run N times per case with pass rates (validation + repair already landed in phase 2); LLM-as-judge; tool-selection accuracy; RAG recall; CI gate | CI fails when accuracy drops below the threshold |
| 8. Batch + contracts | 11 | Spark daily customer baselines fed back to Flink; **Apicurio schema registry** and Avro migration; OpenTelemetry end to end | Schema change is rejected by the registry; one trace spans Kafka → agent → MCP |
| 9. Prove it | 12 | **Chaos drills** (TaskManager kill, MCP outage, slow LLM); Gatling load test; README numbers; demo video; client-workshop deck | Published P95 alert-to-report latency, Flink throughput, and recovery behavior |

Bold items are the six additions agreed on top of the base design.

## Deliberately out of scope

Kubernetes before phase 9, fine-tuning, local model hosting, more microservices, multiple vector stores,
Kafka Streams alongside Flink.
