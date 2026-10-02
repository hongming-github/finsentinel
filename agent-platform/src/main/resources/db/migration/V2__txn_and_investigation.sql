-- Transaction history the investigation agent reads through its tools.
create table txn (
    tenant_id         text          not null,
    txn_id            text          not null,
    account_id        text          not null,
    customer_id       text          not null,
    amount            numeric(18,2) not null,
    currency          text          not null,
    merchant_id       text,
    merchant_category text,
    channel           text          not null,
    country           text,
    event_time        timestamptz   not null,
    ingested_at       timestamptz   not null default now(),
    primary key (tenant_id, txn_id)
);

create index txn_account_time_idx on txn (tenant_id, account_id, event_time desc);

-- Work-queue columns. claimed_at doubles as a fencing token: a worker may only complete
-- the claim it made, so a timed-out worker cannot overwrite a newer attempt.
alter table alert
    add column attempts   int not null default 0,
    add column claimed_at timestamptz;

create index alert_queue_idx on alert (status, detected_at) where status in ('NEW', 'INVESTIGATING');

create table case_report (
    alert_id      uuid        primary key references alert (alert_id),
    tenant_id     text        not null,
    disposition   text        not null,
    risk_score    int         not null,
    report        jsonb       not null,
    tool_calls    jsonb       not null,
    ungrounded_txn_ids text[] not null,
    model         text        not null,
    input_tokens  int,
    output_tokens int,
    latency_ms    bigint      not null,
    created_at    timestamptz not null default now()
);
