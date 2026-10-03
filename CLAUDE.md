# FinSentinel

Portfolio project: Flink risk rules + Spring Boot AI investigation agents. Roadmap in `docs/roadmap.md`,
decisions in `docs/adr/`.

**If `HANDOFF.md` exists in the repository root, read it first**: it holds in-flight work, decisions not yet
committed, and how the user likes to work. Keep it updated at the end of each session.

## Commands

- Build: `./mvnw install -DskipTests`
- Test: `./mvnw test` (Docker required; agent-platform uses Testcontainers)
- Infra: `docker compose -f deploy/docker-compose.yml --profile core --profile systems up -d`
- Run: see README "Quick start"
- Retrieval eval (costs a little): `./mvnw -pl agent-platform test -Dtest=RetrievalEvalTest -Dlive=true -Dsurefire.failIfNoSpecifiedTests=false`
- Knowledge documents: `./scripts/fetch-knowledge.sh` (PDFs are gitignored, MAS copyright)
- Live LLM tests (cost money: about $0.10 per run in the default SINGLE mode, prints cost per case; add
  `-Dlive.modes=SINGLE,MULTI` for a mode comparison): `./mvnw -pl agent-platform test -Dtest=LiveInvestigationTest -Dlive=true -Dsurefire.failIfNoSpecifiedTests=false`

## Rules

- `common` stays dependency-free. `stream-job` and `batch-job` never depend on Spring (ADR 0001).
- Only `agent-platform` and MCP modules import the Spring Boot / Spring AI BOMs. Keep the root POM free of them.
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
- Agent tools are scoped by construction (tenant/account fixed in `InvestigationTools`); never add a tool
  parameter that selects tenant or account. MCP server tools are never handed to the model directly: wrap each in a
  case-scoped tool via `ExternalSystems`, and return `Unavailable` (not an exception) when the system is down.
- The platform's own MCP server tools are registered as MCP tool specifications only, never as
  `ToolCallbackProvider` beans (Spring AI's resolver collects those for agents). They take the tenant as a parameter
  like the REST path until phase 6 takes it from the token; every case read stays tenant-filtered.
- Any path that lets outside callers trigger model or embedding calls needs a budget; shared limits are counted in
  Postgres (`RateLimiter`, atomic upsert), never in memory.
- Agent tool parameters are boxed (`Integer`, not `int`): the model sometimes sends null, which fails a primitive.
- Normal tests must not call an LLM: `@PlatformTest` swaps in `StubCaseInvestigator`.
- Never remove or bypass `max-per-hour`; any new LLM-calling path needs a budget.
- The platform must be safe to run as several instances: coordinate through Postgres (row locks, fencing,
  advisory locks), never through in-memory state. Prove concurrency fixes with a test that fails without them,
  on a `clean` build.
- Spring AI 2.0 OpenAI uses Chat Completions: gpt-5.x rejects tools + reasoning there, so `reasoning-effort: none`.
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
  was checked on developers.openai.com); startup fails otherwise. Cost is computed from the model the provider
  reports, so do not fake it in tests that assert on cost.
- Model choice is per agent role and deterministic (`ModelRouter`); escalation to gpt-5.5 is off by default on
  measured data (ADR 0011). Change it only with a new comparison that includes cost.
- Review is risk-based (`review-dispositions`); a skipped review is recorded as skipped (`Review.skipped`), never as
  an approval.
- `finsentinel.analysis.mode` defaults to SINGLE on measured data (ADR 0010); change it only with a new comparison
  from `LiveInvestigationTest`. Specialists write their own state keys; never let parallel nodes write the same key.
- Only validated reports are stored (ADR 0006). Repairs are bounded by `report.max-repairs`.
- After changing the prompt, tools, or model, run the live tests at least 3 times: one pass proves little.
- Avoid nested ternaries mixing `Integer` and `int` (silent unboxing NPE).
- Host ports: Redis 6380, platform 8090 (REST and `/mcp`), mcp-banking 8091, mcp-compliance 8092, fake systems (WireMock) 8093;
  6379 and 8080 are used by other local projects.
- Code inside graph nodes runs on pool-spawned threads; never rely on `ServiceLoader` defaults there (pass
  dependencies explicitly). Verify library integrations with the packaged jar, not only tests.
