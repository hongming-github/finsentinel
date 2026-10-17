# 0027. A web console over the tenant-scoped API, signed in with PKCE

- Status: accepted
- Date: 2026-10-17

## Context

The platform had no user interface of its own. Seeing the system meant curl with a password-grant token, Grafana, the
Flink web UI and Keycloak's admin console, each on its own. A demo, a client workshop or an interview needs the whole
system at a glance: the case queue and a case with its report, evidence, events, review and cost; the alerts; what the
models cost; whether every process is up; and, from a case, its trace in Tempo and its log lines in Loki.

Constraints from earlier decisions: the tenant comes only from the token (ADR 0014); every endpoint and security rule
is explicit, and anything not listed is denied; every API read is an audit entry (ADR 0016); the root POM stays free of
Spring BOMs and the Java build stays as it is (ADR 0022).

Two gaps showed up while planning. The case detail did not return the trace ids stored with the case and its alerts
(`trace_parent`, ADR 0020), so nothing could link to Tempo. And Loki was empty: the processes sent traces and metrics to
the LGTM container, but no logs.

## Decision

1. **A React + Vite + TypeScript single-page app in `console/`**, outside the Maven build and outside CI for now (the
   user's choice): `npm run dev` (or `scripts/stack.sh console`) on :8097. React 19, react-router 8, TanStack Query 5,
   oidc-client-ts 3, plain CSS; vitest for the pure parts (token claims, deep links). Versions were picked from npm,
   none younger than about two weeks. Server-side rendering would have needed a session and OAuth2 login in the
   platform, against its stateless bearer-token model. Static files inside the platform's jar would have given it UI
   duties and a second security chain to open.
2. **Sign-in: authorization code with PKCE (S256), a new public client `finsentinel-console`**: standard flow only,
   no password grant, no implicit flow, one exact redirect URI and web origin (`http://localhost:8097`), PKCE required
   by the client's attributes, audience `finsentinel-platform`. Its token reaches exactly the rules an analyst's or
   supervisor's token from any other client reaches. `KeycloakRealmTest` runs the browser flow against the real realm
   (login form, code, verifier) and checks that a request without a code challenge, a foreign redirect URI and the
   password grant are refused. Tokens stay in memory, never in localStorage; a reload signs in again through
   Keycloak's session, silently. The console shows the tenant and roles from the token but never chooses a tenant:
   the path's `{t}` is the token's, and the platform answers 404 for any other.
3. **Same origin through Vite's proxy, no CORS anywhere in the platform.** `/api/` goes to the platform; `/health/<name>`
   goes to exactly one health URL per process (platform, both MCP servers, fake-llm, Flink's `/jobs/overview`,
   Keycloak, the registry, Grafana, Kafka UI), nothing else of theirs. No endpoint and no security rule changed, so
   `SecurityTest` needed no case. The only cross-origin calls go to Keycloak, which allows the console's origin
   through the client's web origins. A deployment would put the static build behind the same ingress as the API.
4. **The case detail names its traces**: `traces.currentRun` (the trace the current analysis run continues) and
   `traces.alerts` (the traces its alerts arrived in, oldest first), trace ids only, no span ids or flags.
   `TracingIntegrationTest` checks them. The console links each into Grafana Explore (Tempo by trace id, Loki by trace
   id and by case id, the pipeline dashboard over the case's life), with the ids validated before they go into a
   query.
5. **Logs to Loki in profile `load` only**: the OpenTelemetry Logback appender (`opentelemetry-logback-appender-1.0`
   2.28.1-alpha, the release built against the OpenTelemetry 1.62.0 that Boot 4.1.1 manages) in `logback-spring.xml`
   under `<springProfile name="load">`, Boot's OTLP log exporter, and the platform's traces to the same collector,
   all set in `application-load.yaml`. Each line carries its trace and span id. Only in that profile, because log
   lines carry account ids (the alert intake logs tenant/account), and there every account is simulated. `stack.sh`
   also passes the traces endpoint to both MCP servers, so a case's trace reaches the bank systems.
6. **Kafka UI (kafbat/kafka-ui v1.5.0) in a compose profile of its own (`kafka-ui`, :8098)**: read-only, Avro decoded
   through the registry's Confluent-compatible API, no sign-in (a local tool, like Grafana here). Its own profile keeps
   the `obs` profile light on a 16 GB laptop.
7. **The console polls sparingly**, because every case and alert read is an audit entry: lists every 30 s, a case only
   while it is in ANALYSIS (every 5 s), health checks (not API reads) every 5 s.
8. **Screenshots are reproducible**: `npm run screenshots` signs in as alice and sam through the real Keycloak form,
   with the Chrome installed on the machine (`playwright-core`, no browser download), and writes `docs/console/`.

## Consequences

- One command more for a demo (`scripts/stack.sh console`, needs Node 22.22+); the Java build and CI are unchanged,
  so a broken console does not fail CI until a console job is added (follow-up).
- Load tests and drills from now on run with trace and log export on (profile `load`). The load tests of ADR 0025 ran
  without it: compare against them with that difference in mind, or start the platform with
  `--management.tracing.export.otlp.enabled=false`.
- The decision flow is visible end to end: the ETag in the panel, `If-Match` on the request, and a 409 shown as
  "the case changed since you loaded it", also tried by hand by changing the case underneath the console.
- The supervisor's audit page shows the console's own reads (client `finsentinel-console`), 403s and 409s included.
- Overview counts per stage come from the case list, capped at its page size (shown as "200+"); an exact count
  would need a count endpoint with its own rule and test.
- Analysts still see whatever the API returns (account ids are simulated here); the console adds no masking of
  its own.
