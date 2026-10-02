-- Phase 4: alerts are grouped into cases, and the case (not the alert) is the unit of work, review and decision.

create table fraud_case (
    case_id       uuid        primary key,
    tenant_id     text        not null,
    account_id    text        not null,
    customer_id   text        not null,
    -- ANALYSIS (claimable) -> AWAITING_DECISION (human) -> CLOSED; FAILED when analysis gave up.
    stage         text        not null,
    priority      text        not null,
    -- Work queue, as for alerts before: claimed_at is also the fencing token.
    attempts      int         not null default 0,
    claimed_at    timestamptz,
    -- Bumped whenever the case needs a fresh analysis (new alert, analyst sends it back). A worker that finishes
    -- an older run does not move the case on.
    analysis_run  int         not null default 1,
    analyst_note  text,
    -- Optimistic locking for the decision API.
    version       int         not null default 0,
    opened_at     timestamptz not null default now(),
    updated_at    timestamptz not null default now(),
    closed_at     timestamptz,
    decision      text,
    decided_by    text
);

-- At most one open case per account: concurrent alerts on the same account join the same case.
create unique index fraud_case_one_open_per_account on fraud_case (tenant_id, account_id) where stage <> 'CLOSED';
create index fraud_case_queue_idx on fraud_case (priority, opened_at) where stage = 'ANALYSIS';
create index fraud_case_tenant_stage_idx on fraud_case (tenant_id, stage, updated_at desc);

-- Audit trail: every transition, who caused it, and why.
create table case_event (
    event_id   bigserial   primary key,
    case_id    uuid        not null references fraud_case (case_id),
    at         timestamptz not null default clock_timestamp(),
    type       text        not null,
    from_stage text,
    to_stage   text,
    actor      text        not null,
    detail     jsonb       not null default '{}'
);
create index case_event_case_idx on case_event (case_id, event_id);

-- One analysis per case and run. Replaces the per-alert case_report.
create table case_analysis (
    case_id             uuid        not null references fraud_case (case_id),
    run                 int         not null,
    disposition         text        not null,
    risk_score          int         not null,
    report              jsonb       not null,
    tool_calls          jsonb       not null,
    repaired_issues     text[]      not null default '{}',
    model               text        not null,
    input_tokens        int,
    output_tokens       int,
    cached_input_tokens int,
    llm_calls           int         not null default 1,
    latency_ms          bigint      not null,
    created_at          timestamptz not null default now(),
    primary key (case_id, run)
);

-- Transactional outbox: written in the same transaction as the state change, published to Kafka afterwards.
create table outbox (
    event_id     bigserial   primary key,
    aggregate_id uuid        not null,
    type         text        not null,
    payload      jsonb       not null,
    created_at   timestamptz not null default now(),
    published_at timestamptz
);
create index outbox_unpublished_idx on outbox (event_id) where published_at is null;

-- ---------- existing data: one case per account ----------

insert into fraud_case (case_id, tenant_id, account_id, customer_id, stage, priority, opened_at)
select gen_random_uuid(), tenant_id, account_id, min(customer_id),
       case when bool_and(status = 'REPORTED') then 'AWAITING_DECISION'
            when bool_or(status in ('NEW', 'INVESTIGATING')) then 'ANALYSIS'
            else 'FAILED' end,
       case max(case severity when 'HIGH' then 3 when 'MEDIUM' then 2 else 1 end)
            when 3 then 'HIGH' when 2 then 'MEDIUM' else 'LOW' end,
       min(received_at)
from alert
group by tenant_id, account_id;

alter table alert add column case_id uuid references fraud_case (case_id);
update alert a set case_id = c.case_id from fraud_case c where c.tenant_id = a.tenant_id and c.account_id = a.account_id;
alter table alert alter column case_id set not null;
create index alert_case_idx on alert (case_id);

-- Keep the newest per-alert report of each case as that case's first analysis.
insert into case_analysis (case_id, run, disposition, risk_score, report, tool_calls, repaired_issues, model,
                           input_tokens, output_tokens, cached_input_tokens, llm_calls, latency_ms, created_at)
select distinct on (a.case_id)
       a.case_id, 1, r.disposition, r.risk_score, r.report, r.tool_calls, r.repaired_issues, r.model,
       r.input_tokens, r.output_tokens, r.cached_input_tokens, r.llm_calls, r.latency_ms, r.created_at
from case_report r join alert a using (alert_id)
order by a.case_id, r.created_at desc;

insert into case_event (case_id, type, to_stage, actor, detail)
select case_id, 'MIGRATED', stage, 'system', '{"from": "per-alert queue"}' from fraud_case;

drop table case_report;
-- The alert is no longer a unit of work; its queue columns move to fraud_case.
alter table alert drop column status, drop column attempts, drop column claimed_at;
