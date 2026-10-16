# 0024. Phase 9 foundation: a protocol-level fake LLM, alert-to-report metrics, one script for the stack

- Status: accepted (phase 9 step A; B load tests, C chaos drills, D published numbers, E demo and deck follow)
- Date: 2026-10-16

## Context

Phase 9 has to publish numbers: P95 alert-to-report latency, Flink throughput, recovery behaviour under faults. Two
things stood in the way. Load tests and drills against a real model would cost money with every analysis and could
not inject a slow or failing model on demand. And the platform measured nothing about the pipeline: no meter said where
an alert's time went between the payment and the report.

## Decision

1. **`fake-llm`, an OpenAI-compatible stand-in** (new module, plain Java: the JDK's HTTP server and Jackson 3, no
   Spring; port 8096). It speaks the protocol the platform uses rather than short-circuiting it, so a load test runs the
   real gateway, Spring AI client, tools, validation, review, budget and fallback:
   - offered tools and no tool results yet: it calls `getAlertTransactions` and, when offered, `searchRegulations`;
   - otherwise it answers the requested JSON schema (`response_format.json_schema`, `$ref` and nullable types
     included), citing only transaction and chunk ids that tool results of the conversation returned; with no tool
     results it reads them from the notes of the platform's two-step path (Groq);
   - the verdict is always NEEDS_REVIEW at 50: valid for every case, and it goes through the reviewer, which approves;
   - embeddings are a deterministic bag-of-words vector (same text, same vector; no semantics);
   - `/admin/behaviour?provider=` sets latency, jitter and an error rate (429, 500 or 503) per provider at runtime:
     `/v1` is "openai", `/groq/v1` is the fallback, so a drill can fail one and keep the other.
   The alternative, a switch that swaps in the test stub investigator, was simpler but would skip the gateway, tools
   and validation, and measure a latency the platform never has.
2. **Profile `load`** (`application-load.yaml`): every model and embedding call to fake-llm, the fallback too; a price
   of zero for the model fake-llm reports; `max-per-hour` raised to 100,000 (still a budget); the knowledge sync off,
   because fake vectors would overwrite the real embeddings in the database; metrics pushed by OTLP every 10 s.
3. **Pipeline meters** (`PipelineMetrics`): one timer `finsentinel.pipeline` with a `stage` tag and percentile
   histograms, measured on the alert that started the run: `detection` (payment event time to Flink's detection),
   `delivery` (to the platform, over Kafka), `queue` (waiting for a worker), `analysis` (claim to stored report) and
   `total`. A run an analyst requested times only its analysis (an hour-old alert would read as an hour in the queue);
   negative spans from clock skew are skipped. Plus a gauge `finsentinel.cases.waiting`, the backlog. The gateway's
   existing observation `finsentinel.llm.ask` gives model-call latency, fallback and errors.
4. **Grafana dashboard "FinSentinel pipeline"**, provisioned into the obs profile's LGTM container
   (`deploy/grafana/`): alert-to-report p50/p95, p95 by stage, backlog, analyses per minute, model-call p95 by
   answering model, fallback share and failures.
5. **`scripts/stack.sh`**: `up` starts the Docker profiles, registers the schemas and starts fake-llm, both MCP servers
   and the platform (profile `load`), waiting for each to answer; `down`, `status`; `flink` and `traffic` run the job and
   the simulator.

## Evidence

- `FakeLlmTest` (7, over HTTP): tool calls first, schema-shaped final answer citing only returned ids, reviewer and
  reranker answers, notes of the two-step path, deterministic unit embeddings, per-provider failures, latency, 400s.
- `FakeLlmContractTest` (2, runs in every build): a golden case through the real agents, gateway and Spring AI OpenAI
  client on fake-llm passes validation (NEEDS_REVIEW 50, tokens reported, cost 0); with the main provider failing every
  request, the gateway's answer comes from the fallback's two-step path.
- `PipelineMetricsTest` (3) and a `CaseWorkerTest` case: every stage timed, re-analysis timed as analysis only,
  negative spans skipped, the backlog gauge counts an open case.
- **On the local stack** (core, systems, obs; Flink embedded; simulator at 20 tps, a fraud scenario every 3 s, two
  runs): 66 alerts filed and analysed through fake-llm, 0 failed, all three rules; ~250 ms per analysis. First
  figures, to be measured properly in step B: p95 detection 12.4 s (thought to be Flink catching up on the backfill; ADR 0025 found the simulator backdating velocity bursts),
  delivery 0.3 s, queue 6.8 s (5 s poll, 2 cases per claim), analysis 3.3 s, total 16.0 s. With fake-llm's main
  provider at 1.5 s latency and 30% 503s, the backlog rose to 16 cases and queue p95 to about 17 s; only one call fell
  back, because the OpenAI SDK retries a 503 twice (0.3^3 = 2.7% of calls exhaust them).
- Found on the way (for the drills): after `docker compose up` recreated Kafka with a newer image, the Apicurio registry
  (Kafka-backed) kept serving reads but every write timed out until it was restarted.

## Consequences

- Load tests and drills cost nothing and can be repeated; their latencies describe the platform, not a model. The real
  model's latency is measured separately (17 golden cases: p50 11.0 s, p95 20.8 s on Luna, ADR 0023).
- The metric names in Prometheus carry the unit (`finsentinel_pipeline_milliseconds_bucket`); the dashboard queries
  those.
- fake-llm's answers are not realistic analyses: never use the profile to judge report quality.
- Recreating the LGTM container to load the dashboard dropped the telemetry it held (the phase 8C trace; ADR 0020 keeps
  its evidence).
