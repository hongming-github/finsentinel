# FinSentinel

Portfolio project: Flink risk rules + Spring Boot AI investigation agents. Roadmap in `docs/roadmap.md`,
decisions in `docs/adr/`.

## Commands

- Build: `./mvnw install -DskipTests`
- Test: `./mvnw test` (Docker required; agent-platform uses Testcontainers)
- Infra: `docker compose -f deploy/docker-compose.yml --profile core up -d`
- Run: see README "Quick start"

## Rules

- `common` stays dependency-free. `stream-job` and `batch-job` never depend on Spring (ADR 0001).
- Only `agent-platform` and MCP modules import the Spring Boot / Spring AI BOMs. Keep the root POM free of them.
- Jackson 3 (`tools.jackson`) everywhere in our code.
- Rule logic lives in `stream-job/.../rules` as pure Java; Flink functions stay thin wrappers.
- Types that cross Flink operators or live in state must stay Flink POJOs; `SerializationTest` guards this.
- Every read in the platform is tenant-scoped.
- Alert ids are deterministic (ADR 0003). Do not change `Alert.idFor` casually.
- Check dependency versions on Maven Central before adding or bumping; do not guess.
- Host ports: Redis 6380 and platform 8090, because 6379 and 8080 are used by other local projects.
