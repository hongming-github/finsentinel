# 0014. Identity and access: Keycloak JWTs, the tenant from the token, deny by default

- Status: accepted (phase 6 step A; Postgres row-level security follows in step B)
- Date: 2026-10-09

## Context

Until now the REST API and the MCP endpoint trusted the caller: the tenant came from the URL, the deciding analyst
from an `X-Analyst-Id` header, and MCP tools took a `tenantId` argument, so an MCP client's model could ask for any
bank's cases. mcp-banking and mcp-compliance answered anyone. The roadmap's phase 6 asks for Keycloak, RBAC, OAuth 2.1
on MCP, and cross-tenant access tests that fail closed.

## Decision

- **Keycloak realm `finsentinel`, as code** (`deploy/keycloak/finsentinel-realm.json`, imported on start, port 8094).
  Every access token carries flat claims the services read directly: `tenant` (user attribute, declared in the user
  profile because Keycloak 26 drops undeclared attributes), `roles` (realm roles), `sub`, `preferred_username`, and an
  audience per service. Banks: `straits`, `harbour`. Development users: alice and bob (analysts), sam (supervisor).
- **Clients**: `analyst-cli` (public, password grant, development only: curl in the README); `analyst-assistant`
  (client credentials, a hard-coded `tenant` claim: an assistant works for one bank; role `assistant`);
  `platform-service` (client credentials, audiences `mcp-banking` and `mcp-compliance`, role `systems-reader`, no
  tenant: it acts for every bank, and per-case scoping stays the platform's job, ADR 0002).
- **The platform is a JWT resource server** with Spring Boot's issuer-based decoder (keys from the realm's JWKS,
  fetched on first use so the platform starts while Keycloak does) and audience `finsentinel-platform`. A token for
  the MCP servers is refused by the platform and vice versa.
- **The tenant comes only from the token.** `/api/tenants/{t}/...` stays (stable URLs), and `TenantPathFilter` lets a
  request through only when `{t}` is the caller's tenant: otherwise 404, the same answer as for a case that does not
  exist, so a caller cannot probe which tenants have data. It is a filter in the security chain, not a check in each
  controller, so new endpoints under the path are covered by default. It decodes the path variable exactly as Spring
  MVC does (an encoded own tenant still matches; an encoded foreign one is still refused). The deciding analyst is the
  token's user; `X-Analyst-Id` is gone.
- **Roles**: `analyst` reads its bank's cases and alerts and decides; `supervisor` reads them and the LLM cost report
  but does not decide; `assistant` may use MCP only. Both security chains end in `denyAll()`: an endpoint nobody
  listed is closed.
- **MCP endpoint per the MCP authorization spec** (`org.springaicommunity:mcp-server-security` 0.1.14): a 401 carries
  `WWW-Authenticate: Bearer resource_metadata=.../.well-known/oauth-protected-resource/mcp`, and that document names the
  realm as authorization server. MCP tools take no tenant parameter; the schema rejects one if a client sends it.
- **Service to service**: the platform calls mcp-banking and mcp-compliance with its own client-credentials token
  (`ServiceToken`, Spring Security's `AuthorizedClientServiceOAuth2AuthorizedClientManager`, cached until shortly
  before expiry, usable from the graph's pool threads). The client registration uses `token-uri`, not `issuer-uri`,
  so startup never depends on Keycloak. No token is handled like a system that is down: `Unavailable`, and the
  analysis carries on. Each MCP server accepts only its own audience and the `systems-reader` role.
- **Tests run the production decoder.** `TestIssuer` is an in-process stand-in for the realm (OpenID discovery, JWKS,
  a client-credentials endpoint) with a key generated at startup; `TestTokens` signs tokens shaped like the realm's.
  No key files in the repository, no mocked `JwtDecoder`. `KeycloakRealmTest` (opt-in) runs the realm file in a real
  Keycloak container and checks the claims and the rules end to end.

## Found on the way

- Spring Boot 4 ignores `public-key-location` whenever `issuer-uri` is set (`KeyValueCondition`). The first test setup
  relied on it and silently fetched keys from the developer's running Keycloak: every test token failed with "Invalid
  signature". Without a local Keycloak it would have failed differently. The in-process issuer removes the hidden
  dependency.
- Keycloak's `sslRequired: external` treats requests through Docker's bridge as external ("HTTPS required"); the
  development realm uses `none`.
- With explicit default client scopes the built-in `basic` and `profile` scopes no longer apply, so `sub` and
  `preferred_username` were missing from user tokens; the realm's own scope maps them.
- Removing `TenantPathFilter` makes three tests fail, including a real leak: another bank's analyst reading this
  bank's case through this bank's path (200).

## Consequences

- Cross-tenant isolation at the API rests on one filter and one rule (tenant from the token); the repositories still
  filter every read by tenant. Step B adds Postgres row-level security underneath, so a query that forgets the filter
  returns nothing instead of another bank's rows.
- The password grant on `analyst-cli` exists for the README's curl commands only; a real analyst UI would use the
  authorization code flow with PKCE. Client secrets in the realm file are development values.
- MCP clients that implement OAuth discovery with dynamic client registration (e.g. Claude Code) would need DCR
  enabled in the realm; today they pass a bearer token in a header.
- Keycloak adds about 600 MB to the `core` profile.
