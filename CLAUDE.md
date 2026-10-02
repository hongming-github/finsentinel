# FinSentinel

Portfolio project: Flink risk rules + Spring Boot AI investigation agents. Roadmap in `docs/roadmap.md`,
decisions in `docs/adr/`.

## Commands

- Build: `./mvnw install -DskipTests`
- Test: `./mvnw test` (Docker required; agent-platform uses Testcontainers)
- Infra: `docker compose -f deploy/docker-compose.yml --profile core up -d`
- Run: see README "Quick start"
- Live LLM tests (cost money): `./mvnw -pl agent-platform test -Dtest=LiveInvestigationTest -Dlive=true -Dsurefire.failIfNoSpecifiedTests=false`

## Rules

- `common` stays dependency-free. `stream-job` and `batch-job` never depend on Spring (ADR 0001).
- Only `agent-platform` and MCP modules import the Spring Boot / Spring AI BOMs. Keep the root POM free of them.
- Jackson 3 (`tools.jackson`) everywhere in our code.
- Rule logic lives in `stream-job/.../rules` as pure Java; Flink functions stay thin wrappers.
- Types that cross Flink operators or live in state must stay Flink POJOs; `SerializationTest` guards this.
- Every read in the platform is tenant-scoped.
- Alert ids are deterministic (ADR 0003). Do not change `Alert.idFor` casually.
- Check dependency versions on Maven Central before adding or bumping; do not guess.
- Agent tools are scoped by construction (tenant/account fixed in `InvestigationTools`); never add a tool
  parameter that selects tenant or account.
- Normal tests must not call an LLM: `@PlatformTest` swaps in `StubCaseInvestigator`.
- Never remove or bypass `max-per-hour`; any new LLM-calling path needs a budget.
- Spring AI 2.0 OpenAI uses Chat Completions: gpt-5.x rejects tools + reasoning there, so `reasoning-effort: none`.
- No tunable numbers in code: Spring settings go in `application.yaml` via `@ConfigurationProperties` records
  (validated in their compact constructors); Flink settings go in `stream-job.properties`.
- SQL is hand-written with `JdbcClient` (ADR 0005); bind every value as a parameter, constants included.
- Tool results returned to the model are compact views; do not return full domain records.
- Only validated reports are stored (ADR 0006). Repairs are bounded by `report.max-repairs`.
- After changing the prompt, tools, or model, run the live tests at least 3 times: one pass proves little.
- Avoid nested ternaries mixing `Integer` and `int` (silent unboxing NPE).
- Host ports: Redis 6380 and platform 8090, because 6379 and 8080 are used by other local projects.
