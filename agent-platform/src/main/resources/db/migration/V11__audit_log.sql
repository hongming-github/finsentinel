-- Phase 6C: who accessed what (ADR 0016). Every request that reaches the platform, allowed or refused, and every case
-- an MCP client read. Append-only, and tamper-evident: each row's hash covers the previous row of the same chain.
create table audit_log (
    audit_id    bigserial   primary key,
    chain       text        not null,          -- the tenant, or '-' for requests without a verified tenant
    at          timestamptz not null,
    tenant_id   text,                          -- from the verified token only; null when unauthenticated
    subject     text,                          -- token sub
    username    text,
    client_id   text,                          -- token azp: which application acted for the user
    action      text        not null,          -- "GET /api/tenants/{tenantId}/cases/{caseId}", "mcp getCase", ...
    resource    text,                          -- the case id, when there is one
    outcome     text        not null,          -- HTTP status, or OK / NOT_FOUND / ERROR for MCP tools
    detail      jsonb       not null default '{}',
    prev_hash   text        not null,
    hash        text        not null
);

create index audit_log_tenant_at_idx on audit_log (tenant_id, at desc);
create index audit_log_chain_idx on audit_log (chain, audit_id);

-- The last hash of each chain. Its row lock serialises appends within one chain (one bank); banks do not wait for
-- each other.
create table audit_chain_head (
    chain     text primary key,
    last_hash text not null
);

-- Append-only for the platform's roles: no UPDATE, DELETE or TRUNCATE privilege, and a trigger for anyone else
-- (the owner can drop the trigger; that leaves the chain to show what changed).
revoke update, delete, truncate on audit_log from finsentinel_app, finsentinel_system;

create function audit_log_is_append_only() returns trigger
    language plpgsql
as $$
begin
    raise exception 'audit_log is append-only' using errcode = '42501';
end
$$;

create trigger audit_log_append_only
    before update or delete on audit_log
    for each statement execute function audit_log_is_append_only();
create trigger audit_log_no_truncate
    before truncate on audit_log
    for each statement execute function audit_log_is_append_only();

-- A bank reads its own entries only. Writing needs no tenant scope: refused and unauthenticated requests have none.
alter table audit_log enable row level security;
create policy audit_read on audit_log for select using (tenant_id = app_tenant());
create policy audit_append on audit_log for insert with check (true);
