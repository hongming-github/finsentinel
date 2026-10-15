# 0020. One trace from the payment to the bank's systems: OpenTelemetry across Kafka, Flink, the queue and MCP

- Status: accepted (phase 8 step C)
- Date: 2026-10-15

## Context

An alert-to-report latency, a slow MCP server or a provider that rate-limits shows up today as log lines in five
processes (simulator, Flink, platform, two MCP servers), joined by hand through ids. Three gaps break any automatic
correlation: Kafka between processes, the case queue (an alert can wait minutes, and several alerts merge into one
case), and threads the libraries start (graph nodes, the MCP client's transport). The roadmap's done-when: one trace
spans Kafka → agent → MCP.

## Decision

- **W3C trace context in a `traceparent` Kafka header**, on transactions and alerts. Spring services use Spring Boot
  4.1's OpenTelemetry support (Micrometer Observation bridged to OpenTelemetry, OTLP/HTTP export); the plain-Java
  processes (simulator, Flink job) use a small `telemetry` module with the OpenTelemetry SDK 1.62.0 (the version Boot
  manages, so the system runs one version; declared only in that module because a BOM in the root POM would override
  Boot's management in the platform) and the JDK HTTP sender (no OkHttp in the Flink jar).
- **The simulator starts the trace**: a producer span per transaction, standing in for the bank's core system.
- **Flink: one span per alert, not per transaction.** The source keeps the header with the bytes (`RawMessage`);
  transactions and alerts travel as `TracedTransaction` / `TracedAlert` (public records, so still Flink POJOs). When a
  rule fires, `AlertTracing` creates a CONSUMER span `detect <RULE>` as a child of the triggering transaction, with
  the detection lag (event time to detection), and the alert carries that span's context. Alerts are about one
  transaction in a thousand, so tracing cost scales with alerts, not throughput. Without an OTLP endpoint no spans
  are made and the transaction's context is forwarded unchanged; tracing that cannot start is logged and switched off
  (it must never restart the detection job).
- **The platform continues the alert's trace across the queue.** The Kafka listener's consumer span (observation on)
  is stored with the alert (`alert.trace_parent`) and on the case as "what started this run" (`fraud_case.trace_parent`,
  set by the alert that opens or reopens the case, or by the analyst's request for another analysis; V12). The worker's
  `analyse case` span is a child of it, and links every other alert of the case, so each alert's trace leads to the
  analysis that used it.
- **Graph nodes on their own threads**: the graph keeps the trace context of each run in progress on this instance
  and every node runs in a `graph node <name>` span under it. Only in-flight runs of this instance are kept; nothing
  is coordinated through it.
- **Model calls**: `LlmGateway.ask` is one observation `llm <model>` around the whole call, fallback included: requested
  and answering model, whether the fallback answered, cost and tokens; Spring AI's chat and tool spans nest inside.
- **MCP**: every call is a CLIENT span `mcp tools/call <tool>` (tool name only). The context is captured on the calling
  thread with the MCP SDK's `transportContextProvider` and written into the request's `traceparent` header by the
  request customizer, so it survives the transport's own threads. The MCP servers' HTTP server spans continue it,
  and their calls to the bank's systems are client spans.
- **No case material in traces.** Spring AI's prompt, completion and tool-content capture is explicitly off (a test
  guards the settings); our spans carry ids, model names, counts and cost; the MCP servers record outbound calls by
  URI template only (`TracingConfig` drops `http.url`, which held account numbers and counterparty names).
- **Quiet by default**: spans are exported only when an OTLP endpoint is configured; scheduled polls do not start
  traces (an `ObservationPredicate`); Boot's OTLP metrics push (to localhost every minute) is off unless configured.
  Sampling is parent-based at 1.0 in development: the upstream decision (the transaction) is kept downstream.
- **Backend**: Grafana LGTM (Tempo, Prometheus, Loki) in the compose `obs` profile, Grafana on :3000, OTLP on :4317/:4318.

## Evidence

- Tests without a model or a collector: `TraceContextTest` (round trip, garbage, off); `AlertTracingTest` (child of the
  transaction, lag attribute, forwarded when off); `AvroWireTest` (headers in and out of the job);
  `TracingIntegrationTest` (an alert with a traceparent → consumer span → stored context → `analyse case` as its child
  across the queue → node spans; a second alert reopens the case: new parent, link to the first; the content flags
  are off); `McpTracingTest` (a real `McpServerConnection` calling the platform's own `/mcp`: the server span is the
  child of the client span); `BankingMcpServerTest` / `ComplianceMcpServerTest` (outbound spans carry the URI template
  and no account id or counterparty). Mutation-checked: without the stored parent, without the node context, without
  the MCP header, and without the URL filter, each test fails.
- Live, on the compose stack with Grafana LGTM, every agent on Groq (OpenAI blocked), one traced payment 30x an
  account's median: **one trace, 232 spans, 5 services**: `txn-simulator publish` → `stream-job detect LARGE_AMOUNT`
  (detection lag 346 ms) → `agent-platform risk.alerts.v2 process` → `analyse case` (4.7 s later, across the queue) →
  `graph node investigate` → `llm groq:openai/gpt-oss-120b` → `chat` → `mcp tools/call getAccountDetails` →
  `mcp-banking http post /mcp` → `http get /core/{tenant}/accounts/{account}`; the same for `screenCounterparty`
  through mcp-compliance, and the review node.
- What the trace showed at once: 41 of 79 requests to Groq were answered 429, so most of the ten-minute analysis was
  rate-limit waiting, not model time; the first call to an MCP server costs four HTTP requests (initialize, stream,
  initialized, call) and about 0.6 s to fetch the token keys; embedding requests to the blocked OpenAI endpoint
  failed four times each with back-off (3 s per search). The analysis itself failed validation after repair (Groq's
  known weakness on transfer reports, ADR 0017), and the failure is in the trace with its error.
- Found by the live run, both fixed: the full URL in the MCP servers' client spans (identifiers, see above); and a
  local Flink run could not load the OTLP exporter (its internals come at runtime scope, which the local run's
  compile classpath left out; the shaded jar was fine).
- Cost of the live run: $0.0255 on Groq (the case took the review, revision and repair path).

## Consequences

- One trace id answers "what happened to this payment": detection, how long the alert waited, every model and tool
  call with its cost, and the bank systems' latency. The analysis span carries the case id and run, so a case in the
  API leads to its trace and back.
- Trace context is now part of the message contract: a producer that drops the header starts a new trace downstream
  (detection still works). `case.events.v1` from the outbox does not carry it yet: the next step is to store the
  context with the outbox row so downstream consumers continue the case's trace.
- Traces in a regulated bank still carry tenant, case, alert and transaction ids. They must go to a backend with the
  same access controls as the case data; retention there is the bank's decision.
- Per-alert spans in Flink, not per-transaction: a transaction that raises no alert has only its producer span. For
  throughput and lag of the stream, Flink's own metrics are the tool, not traces.
