# 0001. Agent platform as a modular monolith; stream and batch jobs as separate Spring-free modules

- Status: accepted
- Date: 2026-09-30

## Context

The platform needs alert intake, investigation agents, RAG, an LLM gateway, MCP client/server, and a review API.
It must run on a 16 GB laptop next to Kafka, Postgres, Flink, and an IDE. The Flink and Spark jobs run in their
own runtimes (a Flink cluster, `spark-submit`), and those runtimes pin their own library versions. Spark in
particular is strict about its Jackson 2 version.

## Decision

- `agent-platform` is one Spring Boot 4.1 application, split into packages by capability (`alert`,
  `investigation`, `rag`, `gateway`, `tenancy`, ...). Packages talk through public types, not shared tables.
- `stream-job` and `batch-job` are plain Java with no Spring dependency.
- The root POM does not inherit `spring-boot-starter-parent`. Only Spring modules import the Boot BOM, so the
  Boot BOM cannot change the versions Flink or Spark use.
- `common` holds event records only and has zero dependencies.
- MCP servers (`mcp-banking`, `mcp-compliance`) are separate processes, because they stand in for systems owned by
  other teams and vendors (see 0002).

## Consequences

- One JVM for the platform (~400 MB) instead of five (~2 GB). One deploy, one trace, no network hops between
  agent steps.
- Package boundaries are enforced by review, not by the network. If a capability later needs to scale or ship on
  its own, it can be extracted along an existing package boundary.
- Jackson 2 and 3 coexist: Boot uses Jackson 3 (`tools.jackson`), Flink shades its own Jackson 2, and our jobs
  use Jackson 3 directly. They have different packages and never collide.
