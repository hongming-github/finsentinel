-- Phase 6B: Postgres row-level security underneath the application's tenant filters (ADR 0015). A query that forgets
-- "where tenant_id = ..." returns the session tenant's rows only; a session without a tenant fails instead of
-- returning anything.
--
-- Roles. Migrations run as the schema owner, which bypasses RLS (owners do unless FORCE is set) and is never used by
-- the running platform. The platform logs in as finsentinel_app, subject to every policy. Cross-tenant background
-- work (claiming queued cases, timing out abandoned ones) switches to finsentinel_system, which bypasses RLS, and only
-- through Tenancy.asSystem in code. Creating BYPASSRLS roles needs a superuser: the migration user in development and
-- tests. Elsewhere a DBA creates both roles first and this block skips them.
do $$
begin
    if not exists (select from pg_roles where rolname = 'finsentinel_app') then
        create role finsentinel_app login password '${app_password}';
    end if;
    if not exists (select from pg_roles where rolname = 'finsentinel_system') then
        create role finsentinel_system nologin bypassrls;
    end if;
end
$$;

-- The app may become the system role (SET ROLE) but does not inherit its privileges: BYPASSRLS needs the explicit switch.
grant finsentinel_system to finsentinel_app with inherit false, set true;

grant usage on schema public to finsentinel_app, finsentinel_system;
grant select, insert, update, delete on all tables in schema public to finsentinel_app, finsentinel_system;
grant usage, select on all sequences in schema public to finsentinel_app, finsentinel_system;
alter default privileges in schema public grant select, insert, update, delete on tables to finsentinel_app, finsentinel_system;
alter default privileges in schema public grant usage, select on sequences to finsentinel_app, finsentinel_system;
revoke all on flyway_schema_history from finsentinel_app, finsentinel_system;

-- The session's tenant, set by the platform for every connection it hands out (TenantAwareDataSource). Unset or empty
-- is an error rather than "no rows": a code path that forgot to say whose data it reads fails loudly in tests, instead
-- of an agent quietly seeing an empty transaction history.
create function app_tenant() returns text
    language plpgsql stable
as $$
declare
    tenant text := current_setting('app.tenant', true);
begin
    if tenant is null or tenant = '' then
        raise exception 'no tenant set for this database session' using errcode = '42501';
    end if;
    return tenant;
end
$$;

alter table txn enable row level security;
alter table alert enable row level security;
alter table fraud_case enable row level security;
alter table case_event enable row level security;
alter table case_analysis enable row level security;
alter table graph_checkpoint enable row level security;

create policy tenant_isolation on txn
    using (tenant_id = app_tenant()) with check (tenant_id = app_tenant());
create policy tenant_isolation on alert
    using (tenant_id = app_tenant()) with check (tenant_id = app_tenant());
create policy tenant_isolation on fraud_case
    using (tenant_id = app_tenant()) with check (tenant_id = app_tenant());

-- Rows that belong to a case: visible when their case is. The subquery is itself filtered by fraud_case's policy.
create policy tenant_isolation on case_event
    using (exists (select 1 from fraud_case c where c.case_id = case_event.case_id))
    with check (exists (select 1 from fraud_case c where c.case_id = case_event.case_id));
create policy tenant_isolation on case_analysis
    using (exists (select 1 from fraud_case c where c.case_id = case_analysis.case_id))
    with check (exists (select 1 from fraud_case c where c.case_id = case_analysis.case_id));
create policy tenant_isolation on graph_checkpoint
    using (exists (select 1 from fraud_case c where c.case_id = graph_checkpoint.case_id))
    with check (exists (select 1 from fraud_case c where c.case_id = graph_checkpoint.case_id));

-- Not under RLS, on purpose: the regulatory knowledge base (kb_document, kb_chunk) is public MAS text, global by design;
-- outbox and rate_limit_window are the platform's internal queues and counters, never served to a caller.
