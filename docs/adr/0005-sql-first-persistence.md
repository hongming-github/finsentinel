# 0005. SQL-first persistence with JdbcClient, not JPA

- Status: accepted
- Date: 2026-10-02

## Context

The platform's queries are a work queue (`UPDATE ... WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED) RETURNING *`),
idempotent inserts (`ON CONFLICT DO NOTHING`), percentile statistics (`percentile_cont`), Postgres arrays and
`jsonb`, and batched ingestion. The data is mostly append-only events, modelled as immutable records.

## Decision

Use Spring's `JdbcClient` / `JdbcTemplate` with hand-written SQL in repositories. Every read and write takes a
tenant id. Each repository is covered by Testcontainers tests against the same Postgres image we deploy.

## Alternatives

- **Spring Data JPA.** Nearly every query above would be `@Query(nativeQuery = true)` anyway, so we would carry
  the persistence context, dirty checking and lazy loading without using them. JPA entities must be mutable
  with a no-arg constructor; our types are records.
- **jOOQ.** Type-safe SQL that checks queries at compile time. The best fit if the schema grows; not worth the
  code generation step at this size.
- **Spring Data JDBC.** Light and record-friendly. A good fit for aggregate-style CRUD such as case management
  (phase 4), which may use it.

## Consequences

- SQL is visible and tunable; Postgres features are used directly.
- No compile-time check of SQL or column names, and row mapping is manual. Mitigated by integration tests that
  execute every query.
- Values are always bound as parameters, never concatenated, including constants.
