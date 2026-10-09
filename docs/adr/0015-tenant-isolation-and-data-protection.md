# 0015. Tenant isolation in the database, per-bank tool permissions, pseudonymised prompts

- Status: accepted (phase 6 step B)
- Date: 2026-10-09

## Context

After phase 6A the tenant comes only from the access token, and every repository query filters by it. That still
rests on each query being written correctly: one missing `where tenant_id = :tenant`, or the wrong tenant passed to a
repository, returns another bank's rows. The roadmap's phase 6 also asks for isolation of tool permissions and for
masking personal data before LLM calls.

## Decision

### Postgres row-level security (V10)

- **Three roles.** Migrations run as the schema owner (`spring.flyway.user`), which the platform never uses at run
  time. The platform logs in as `finsentinel_app`, which owns nothing and is subject to every policy. Cross-tenant
  queue work switches to `finsentinel_system` (`BYPASSRLS`), which the app may `SET ROLE` to but does not inherit.
- **Policies** on `txn`, `alert`, `fraud_case` (`tenant_id = app_tenant()`, for reads and writes) and on the rows that
  belong to a case, `case_event`, `case_analysis`, `graph_checkpoint` (visible when their case is; the subquery is
  itself filtered). `app_tenant()` raises when the session has no tenant: a code path that forgot its scope fails
  loudly instead of an agent quietly seeing an empty transaction history. Not under RLS: the regulatory knowledge
  base (public, global by design), `outbox` and `rate_limit_window` (internal, never served to a caller).
- **`Tenancy` scope, set where the tenant is known from a trusted source**, never from a method parameter: the token
  (`TenancyFilter`, every request), the alert (`CaseIntake`), the transaction (`TransactionStoreListener`, per tenant
  within a batch), the claimed case (`CaseWorker`), and on the graph's pool threads the case itself (`InvestigationTools`,
  `CasePlanner`, `FencedPostgresCheckpointSaver` via `CaseRun.tenantId`). The system scope is used in one place:
  `CaseWorker` claiming and timing out cases across banks.
- **`TenantAwareDataSource`** wraps the pool and sets each borrowed connection's `app.tenant` and role in one round trip
  (`set_config`), while it is still in auto-commit so a later rollback cannot undo it. Every borrow, so a pooled
  connection never keeps the previous borrower's tenant.
- **Tests log in as `finsentinel_app` too.** A Testcontainers service connection would use the container's superuser,
  which bypasses RLS, so the database is wired by hand. Test code runs in the system scope (`SystemScopeExtension`);
  the code under test sets its own scope at every entry point, and other threads never see the test's.

### Per-bank tool permissions

- `finsentinel.systems.connected` lists, per bank, the outside systems it has connected (`BANKING`: CRM and core
  banking; `COMPLIANCE`: the screening vendor); `connected-default` covers the rest. `CaseContext.connectedSystems`
  carries it into the analysis (missing in an old checkpoint means none: fails closed).
- Tools of a system the bank has not connected are not given to the model, refuse to run if called anyway, and never
  reach the system; the planner skips the customer specialist for a bank with no outside systems; the brief tells the
  agent which checks are not available. The brief changes only for such banks, so every other bank's prompt and its
  provider-cache prefix stay as they were.
- "Vectors" and "prompts" of the roadmap item need no per-tenant isolation today: the only vector store is the public
  MAS corpus, and no prompt is tenant-specific. A tenant-specific playbook would go next to the rule playbooks with the
  tenant in its key.

### Pseudonymised prompts

- `PiiMasker` replaces Singapore NRIC/FIN, email addresses, phone numbers and Luhn-valid card numbers with
  `[KIND_8 hex]`, an HMAC-SHA256 of the value under `finsentinel.privacy.pseudonym-key` and the case id. Within a case
  the same number is the same pseudonym (the agent can still tell that the OTP number changed); across cases they are
  unrelated, and without the key the provider cannot reverse them. Masking masked text changes nothing.
- Applied at the two places case material enters a model call: `AgentModels.ask` (the user message: alerts, analyst
  note, specialist reports, reviewer evidence) and the tool callbacks (each result before the model sees it; the
  reviewer gets the same masked evidence). Model outputs are built only from masked inputs, so reports, search queries
  and specialist findings carry pseudonyms, not values. System prompts are our own text.
- Boundaries chosen not to mangle evidence: no match inside a longer token, so UUIDs, transaction and alert ids,
  timestamps, amounts and regulation references pass unchanged (tested). Names and free-form addresses are not
  detected; the defence there is minimisation: the CRM view given to agents has no name or address fields.

## Evidence

- `RowLevelSecurityTest`: a query without a tenant filter sees only the scope's rows in every tenant table; the wrong
  tenant passed to a repository finds nothing; another bank's row cannot be inserted or updated; no scope is an error;
  the app login is neither superuser nor `BYPASSRLS` nor table owner. With RLS not enabled, 4 of its 6 tests fail.
- The suite with RLS on surfaced every unscoped path at once, as errors: test code polling from Awaitility's thread,
  the concurrency test claiming from its own threads, and one real gap in the change itself (the MULTI planner node
  reading transactions on a pool thread).
- Query plans on the dev data (102k transactions): with the usual tenant filter the policy becomes a one-time filter
  and the index scan is unchanged (0.9 ms); with no filter at all `app_tenant()` is the index condition (index-only
  scan of the bank's 51k rows). No per-row function calls.
- Live suite 3 runs, 18/18: the account-takeover scenario's security event carries two real-shaped phone numbers;
  none appears in any request body (OpenAI debug log), two distinct pseudonyms do, and the case is still LIKELY_FRAUD
  97-98. No other pseudonym appeared in any request (no false matches). The large-transfer scenario came out
  LIKELY_FRAUD 90-92 in all three runs (NEEDS_REVIEW 66-78 in phase 5C) with identical tool results: a borderline
  APP-scam case, both inside the test's expectation.
- Packaged jar on the dev database: V10 applied, the pool logged in as `finsentinel_app`; a harbour transfer case
  reanalysed by bob: no screening tool offered, zero screening requests for harbour at the fake vendor, summary says
  the compliance checks were not available; LIKELY_FRAUD 96, $0.009.

## Consequences

- A forgotten tenant filter is now harmless and a forgotten scope is loud. New code that reads tenant tables on a
  thread it starts must set the scope there.
- Creating `BYPASSRLS` roles needs a superuser; outside development a DBA creates both roles and V10 skips them.
- Analysts see pseudonyms in reports where the agent quoted personal data; mapping them back for display would need a
  per-case vault, not built.
