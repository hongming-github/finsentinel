# FinSentinel

Portfolio project: Flink risk rules + Spring Boot AI investigation agents. Roadmap in `docs/roadmap.md`,
decisions in `docs/adr/`.

**If `HANDOFF.md` exists in the repository root, read it first**: it holds in-flight work, decisions not yet
committed, and how the user likes to work. Keep it updated at the end of each session.

## Commands

- Build: `./mvnw install -DskipTests`
- Test: `./mvnw test` (Docker required; agent-platform uses Testcontainers)
- Infra: `docker compose -f deploy/docker-compose.yml --profile core --profile systems up -d`, then register schemas
  once: `./mvnw -pl contracts exec:java`. Traces: add `--profile obs` (Grafana :3000) and see README "Tracing"
- Batch baselines (Spark local mode, no LLM): `./mvnw -pl batch-job exec:exec -Djob.args="--as-of=YYYY-MM-DD"`
- Run: see README "Quick start"
- Real Keycloak realm test (no LLM): `./mvnw -pl agent-platform test -Dtest=KeycloakRealmTest -Dkeycloak=true -Dsurefire.failIfNoSpecifiedTests=false`
- Retrieval eval (costs a little): `./mvnw -pl agent-platform test -Dtest=RetrievalEvalTest -Dlive=true -Dsurefire.failIfNoSpecifiedTests=false`
- Semantic cache evaluation (embeddings only, under $0.001): `./mvnw -pl agent-platform test -Dtest=SemanticCacheEvalTest -Dlive=true -Dsurefire.failIfNoSpecifiedTests=false`
- Knowledge documents: `./scripts/fetch-knowledge.sh` (PDFs are gitignored, MAS copyright)
- Evaluation (costs money, stops at its budget; see ADR 0017 for the estimate):
  `./mvnw -pl agent-platform test -Dtest=GoldenEvalTest -Deval=true -Deval.maxCostUsd=1.00 -Dsurefire.failIfNoSpecifiedTests=false`
  (`-Deval.repeats`, `-Deval.cases`, `-Deval.modes`, `-Deval.judge=true`; Spring settings as `-D` for comparisons)
- Live LLM tests (cost money: about $0.10 per run in the default SINGLE mode, prints cost per case; add
  `-Dlive.modes=SINGLE,MULTI` for a mode comparison): `./mvnw -pl agent-platform test -Dtest=LiveInvestigationTest -Dlive=true -Dsurefire.failIfNoSpecifiedTests=false`

## Rules

- `common` stays dependency-free. `stream-job` and `batch-job` never depend on Spring (ADR 0001). `contracts` (Avro,
  registry client) stays Spring-free too: the Flink job uses it.
- Only `agent-platform` and MCP modules import the Spring Boot / Spring AI BOMs. Keep the root POM free of them.
  A version override for those modules (security fixes) goes ahead of the Boot BOM in each of them, from a root
  property: imported BOMs never override each other, the first listed wins. Remove it once Boot manages a fixed
  version (ADR 0022 "Upgrades").
- Jackson 3 (`tools.jackson`) everywhere in our code.
- Rule logic lives in `stream-job/.../rules` as pure Java; Flink functions stay thin wrappers.
- Types that cross Flink operators or live in state must stay Flink POJOs; `SerializationTest` guards this.
- The case (`fraud_case`), not the alert, is the unit of work, review and decision. Every state change writes a
  `case_event`; anything downstream systems need goes through the outbox in the same transaction (never a direct
  Kafka send inside a business transaction).
- Analyst writes require `If-Match` with the case version; any change to a case bumps `version`.
- Every read in the platform is tenant-scoped. Exception: the regulatory knowledge base (public MAS text) is global.
- Rule-specific agent guidance goes in `prompts/playbooks/<RULE>.md`, never in the shared system prompt (ADR 0007).
- After changing the chunker or retrieval, run `RetrievalEvalTest` and compare with the table in ADR 0007.
- Alert ids are deterministic (ADR 0003). Do not change `Alert.idFor` casually.
- Check dependency versions on Maven Central before adding or bumping; do not guess.
- The build is the lint (ADR 0022): javac `-Xlint` and Error Prone warnings fail it. Fix a finding; suppress only at the
  site with `@SuppressWarnings("Check")` and the reason. Module bans (`no-spring`, `no-runtime-dependencies`) are
  enforcer executions; CLAUDE.md rules the compiler cannot see go into an `ArchitectureTest`.
- Workflows: actions pinned by commit SHA (version in a comment), images by digest, `permissions: contents: read`,
  inputs into `run:` only through `env:`. A vulnerability exception in `.trivyignore.yaml` names its package
  versions, a reason and an expiry; never a bare CVE id.
- Agent tools are scoped by construction (tenant/account fixed in `InvestigationTools`); never add a tool
  parameter that selects tenant or account.
- Identity comes from Keycloak JWTs (ADR 0014): the tenant only ever from the token (`Caller`), never from a header,
  body or tool argument. `/api/tenants/{t}/...` must match the token's tenant (`TenantPathFilter`, 404 otherwise).
  Security chains deny anything not listed: a new endpoint needs an explicit rule and a case in `SecurityTest`.
- Postgres row-level security backs every tenant filter (ADR 0015). The platform logs in as `finsentinel_app`, never
  the owner; tenant tables need a `Tenancy` scope set where the tenant is trusted (token, alert, claimed case), never
  from a method parameter. Code on a thread it starts (graph nodes, executors) sets the scope again there. The system
  scope (`Tenancy.callAsSystem`) is for cross-bank queue work only. New tenant tables get a policy in a migration and
  a case in `RowLevelSecurityTest`.
- Tools of outside systems are offered per bank (`finsentinel.systems.connected`, `BankSystem`); a new MCP-backed tool
  goes into `InvestigationTools.SYSTEM_TOOLS`.
- Case material reaches models only through `AgentModels.ask` and the tool callbacks, both pseudonymised by
  `PiiMasker`; never send case text to a model another way.
- Untrusted text is flagged by `UntrustedInstructions` in the tool callbacks; a flagged case can never be cleared
  (`CaseReportValidator`) and is recorded on the case. Keep the detector narrow (ordinary bank data must pass, see its
  test) and do not rely on prompts alone: on the payee-name scenario the model's first draft cleared the case 11 of
  15 times (ADR 0016).
- `audit_log` is append-only and hash-chained per bank (ADR 0016): never grant UPDATE/DELETE on it, record identity
  only from a verified token, and add new routes' audit expectations to `AuditLogTest` when they matter.
- Tests authenticate with `TestTokens` (signed by the in-process `TestIssuer`, so the production decoder runs); never
  mock the `JwtDecoder` or use `jwt()` post-processors. Realm changes: run `KeycloakRealmTest -Dkeycloak=true`. MCP server tools are never handed to the model directly: wrap each in a
  case-scoped tool via `ExternalSystems`, and return `Unavailable` (not an exception) when the system is down.
- The platform's own MCP server tools are registered as MCP tool specifications only, never as
  `ToolCallbackProvider` beans (Spring AI's resolver collects those for agents). They take the tenant from the token,
  never as a parameter; every case read stays tenant-filtered. The platform calls mcp-banking/mcp-compliance with its
  own client-credentials token (`ServiceToken`); no token is a system `Unavailable`, not a failed analysis.
- Any path that lets outside callers trigger model or embedding calls needs a budget; shared limits are counted in
  Postgres (`RateLimiter`, atomic upsert), never in memory.
- Agent tool parameters are boxed (`Integer`, not `int`): the model sometimes sends null, which fails a primitive.
- Normal tests must not call an LLM: `@PlatformTest` swaps in `StubCaseInvestigator`.
- Never remove or bypass `max-per-hour`; any new LLM-calling path needs a budget.
- The platform must be safe to run as several instances: coordinate through Postgres (row locks, fencing,
  advisory locks), never through in-memory state. Prove concurrency fixes with a test that fails without them,
  on a `clean` build.
- Spring AI 2.0 OpenAI uses Chat Completions: tools work there only without reasoning (gpt-5.x rejects the mix,
  gpt-6-luna documents tools with reasoning effort none only), so `reasoning-effort: none`. Default model: gpt-6-luna
  for every role and the reranker (ADR 0023).
- No tunable numbers in code: Spring settings go in `application.yaml` via `@ConfigurationProperties` records
  (validated in their compact constructors); Flink settings go in `stream-job.properties`.
- SQL is hand-written with `JdbcClient` (ADR 0005); bind every value as a parameter, constants included.
- Tool results returned to the model are compact views; do not return full domain records.
- The analysis graph runs on Spring AI Alibaba Graph `2.0.0-M1.1` (ADR 0009) and checkpoints only through
  `FencedPostgresCheckpointSaver`: never the library's savers, no `releaseThread`. The case lifecycle deletes a run's
  checkpoints in the transaction that ends the run. Resuming needs the checkpoint id in the config.
- Do not exclude `spring-ai-deepseek`/`spring-ai-zhipuai` from graph-core: `StateGraph`'s static initializer needs
  them. After any Spring AI or Spring AI Alibaba upgrade, run the full suite and a packaged-jar case.
- Graph state keys are declared with their type in `CaseGraphState.TYPES`; values are immutable records that
  round-trip through JSON.
- The reviewer always sees exactly the evidence the report's author saw (raw tool results for the single agent,
  specialist reports for the writer); without it, it flags supported claims and the writer deletes them (ADR 0010).
- Playbook guidance that differs by channel goes in separate sections (card vs transfer); agents misapply mixed lists.
- Every model the platform can call has a price in `finsentinel.llm.prices` (USD per million tokens, with the date it
  was checked on developers.openai.com); startup fails otherwise. Models that bill prompt-cache writes (OpenAI from
  GPT-5.6) need `cache-write` too: written tokens come from the SDK's usage, which Spring AI 2.0.1 does not map. Cost is computed from the model the provider
  reports, so do not fake it in tests that assert on cost.
- Regulation search results are cached in Redis by exact normalised question only (`SearchCache`, ADR 0013). Never
  add a semantic (embedding-similarity) cache in front of retrieval or reranking without re-running
  `SemanticCacheEvalTest`: near-miss questions embed above most paraphrases. Redis is disposable: errors are misses,
  degraded rankings are not cached, nothing that must be correct lives there.
- Text shared by cases (role prompt, playbooks, the bank's unconnected-systems note) goes in the system message
  (`ReportWriting.systemPrompt`); case data only in the user message. GPT-5.6+ caches at message boundaries: shared
  text at the head of the user message was never read back (ADR 0023). Tool definitions stay sorted by name. Watch
  the cached-token share after prompt changes.
- Every model call goes through `LlmGateway` (never a ChatClient directly): it prices the call and falls back to
  another provider on provider failure only (OpenAI SDK errors), with a per-instance circuit breaker (ADR 0012).
  Models are `provider:model`; a new provider needs `structured-output-with-tools` and `schema-formats-on-non-strings`
  set honestly (Groq: both false; without the first, tool calls run in two steps, ADR 0012).
- Model choice is per agent role and deterministic (`ModelRouter`); escalation to gpt-5.5 is off by default on
  measured data (ADR 0011). Change it only with a new comparison that includes cost.
- Review is risk-based (`review-dispositions`); a skipped review is recorded as skipped (`Review.skipped`), never as
  an approval.
- `finsentinel.analysis.mode` defaults to SINGLE on measured data (ADR 0010); change it only with a new comparison
  from `LiveInvestigationTest`. Specialists write their own state keys; never let parallel nodes write the same key.
- Only validated reports are stored (ADR 0006). Repairs are bounded by `report.max-repairs`.
- After changing the prompt, tools, or model, run the live tests at least 3 times: one pass proves little. Prefer the
  evaluation (pass rates per case) once a baseline exists; state its estimated cost first.
- Golden cases live in `src/test/resources/eval/golden-cases.json` (ADR 0017); a new failure mode found live gets a
  case there. `GoldenEvalDryRunTest` validates them for free; never loosen an expectation to make a run pass without
  saying why in the ADR.
- Avoid nested ternaries mixing `Integer` and `int` (silent unboxing NPE).
- Transactions and alerts are Avro on v2 topics (ADR 0018): change the `.avsc` in `contracts`, never the wire format
  by hand. Producers never auto-register; register with `SchemaRegistration`, and a breaking change means a new topic
  version. Only `AvroSerde.InvalidMessageException` may dead-letter a message: a registry outage must propagate (Flink
  restarts, listeners retry). Tests use the Apicurio container in `TestcontainersConfig` (or
  `InMemorySchemaRegistry` outside Spring), never a mocked serde.
- The lake (ADR 0019): Flink appends to `landing/` only; `curated/`, `quarantine/` and `gold/` belong to `batch-job`
  and must stay rebuildable from landing (dynamic partition overwrite, idempotent re-runs). Days are UTC everywhere.
  Batch statistics are robust (median, MAD) because the batch cannot see which past amounts were fraud. The rule uses
  streaming history first and a fresh batch baseline only as a fallback; the alert reason names the source.
- Travel feasibility (ADR 0021) is a lower bound, never an estimate: `TravelFeasibility` in `common`, data built by
  `scripts/travel-data.py` (never edit the CSVs by hand). Unknown countries are UNKNOWN, never impossible; the rule's
  `rules.geo-jump.max-speed-kmh` and the platform's `tools.travel-max-speed-kmh` stay equal. Agents read the verdict
  from `travel`; playbooks never list flight times.
- Stateful Flink operators keep their `uid(...)`; never rename one without a savepoint migration plan.
- Container images are pinned twice: `deploy/docker-compose.yml` (Dependabot updates it) and the Testcontainers
  setup (`TestcontainersConfig` and a few tests, by hand). Keep them equal in the same change; the compose Flink
  image moves only with `flink.version`.
- Kafka sources that rebuild state from a compacted topic (account baselines) start from `earliest()` on a cold
  start; event streams (transactions) continue from the group's committed offsets. Test restarts the way they happen
  in production (same group, no savepoint), not with a fresh group (`StartingOffsetsTest`).
- Spark runs on Java 21 with the opens in `batch-job/pom.xml` (`spark.jvm.opens`); local runs use the test classpath.
- Tracing (ADR 0020): trace context crosses every gap the instrumentation cannot see (Kafka `traceparent` header,
  `trace_parent` columns across the queue, the graph's per-run context on node threads, the MCP
  `transportContextProvider`); a new async boundary must carry it, with a test in the style of
  `TracingIntegrationTest`. Spans carry ids, model names, counts and cost, never prompts, answers, tool arguments or
  customer identifiers (no full URLs of bank systems). Tracing failures must never stop detection or analysis.
  The plain-Java processes use the `telemetry` module (OpenTelemetry at Boot's managed version); never put an
  OpenTelemetry BOM in the root POM.
- Host ports: Redis 6380, platform 8090 (REST and `/mcp`), mcp-banking 8091, mcp-compliance 8092, fake systems (WireMock) 8093, Keycloak 8094, schema registry (Apicurio) 8095, Grafana 3000 and OTLP 4317/4318 (`obs` profile);
  6379 and 8080 are used by other local projects.
- Code inside graph nodes runs on pool-spawned threads; never rely on `ServiceLoader` defaults there (pass
  dependencies explicitly). Verify library integrations with the packaged jar, not only tests.
