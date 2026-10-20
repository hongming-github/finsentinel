-- Case assistant (ADR 0028): an analyst's conversation about one case. Tenant, case and analyst are fixed when the
-- conversation is created, from the token and the checked case; no later request can change them. Turns are appended,
-- never changed: what the assistant said stays what it said.
create table conversation (
    conversation_id  uuid        primary key,
    tenant_id        text        not null,
    case_id          uuid        not null references fraud_case (case_id),
    analyst_subject  text        not null,          -- token sub: the only analyst who may read or continue it
    analyst_username text        not null,
    -- Turns stored so far; a new turn n is accepted only while this is n - 1 (with the primary key below, a turn
    -- number is taken once, whichever instance answers).
    turns            int         not null default 0,
    -- Older turns folded into a summary, so the prompt stays within its budget; turns 1..summary_through are in it.
    summary          text,
    summary_through  int         not null default 0,
    created_at       timestamptz not null,
    updated_at       timestamptz not null
);

create index conversation_case_idx on conversation (tenant_id, case_id, analyst_subject, created_at desc);

create table conversation_turn (
    conversation_id        uuid          not null references conversation (conversation_id),
    seq                    int           not null,
    question               text          not null,  -- as the analyst typed it
    standalone_question    text          not null,  -- condensed with the history, so it reads on its own
    search_query           text,                    -- what retrieval searched for; null when no regulation was needed
    answer                 text          not null,
    cited_txn_ids          jsonb         not null,
    cited_chunk_ids        jsonb         not null,
    -- What the question did that data should not do (UntrustedInstructions); empty for an ordinary question.
    untrusted_instructions jsonb         not null,
    case_version           int           not null,  -- the version of the case the answer was given on
    model                  text,
    input_tokens           int,
    output_tokens          int,
    cached_input_tokens    int,
    cache_write_tokens     int,
    llm_calls              int           not null,
    cost_usd               numeric(12, 6) not null,
    latency_ms             bigint        not null,
    created_at             timestamptz   not null,
    primary key (conversation_id, seq)
);

-- Under row-level security like every tenant table (ADR 0015). Turns are visible when their conversation is.
alter table conversation enable row level security;
alter table conversation_turn enable row level security;

create policy tenant_isolation on conversation
    using (tenant_id = app_tenant()) with check (tenant_id = app_tenant());
create policy tenant_isolation on conversation_turn
    using (exists (select 1 from conversation c where c.conversation_id = conversation_turn.conversation_id))
    with check (exists (select 1 from conversation c where c.conversation_id = conversation_turn.conversation_id));

-- A conversation belongs to a case of its own bank: the policy above checks the tenant, this the case.
create function conversation_case_in_tenant() returns trigger
    language plpgsql
as $$
begin
    if not exists (select 1 from fraud_case c where c.case_id = new.case_id and c.tenant_id = new.tenant_id) then
        raise exception 'case % is not a case of tenant %', new.case_id, new.tenant_id using errcode = '42501';
    end if;
    return new;
end
$$;

create trigger conversation_case_in_tenant
    before insert on conversation
    for each row execute function conversation_case_in_tenant();

-- The default privileges of V10 granted everything. Tenant, case and analyst can never change once written: the
-- platform's roles may update only the turn counter and the summary. Turns are append-only.
revoke update, delete, truncate on conversation from finsentinel_app, finsentinel_system;
grant update (turns, summary, summary_through, updated_at) on conversation to finsentinel_app, finsentinel_system;
revoke update, delete, truncate on conversation_turn from finsentinel_app, finsentinel_system;
