create table alert (
    alert_id    uuid primary key,
    tenant_id   text        not null,
    account_id  text        not null,
    customer_id text        not null,
    rule        text        not null,
    severity    text        not null,
    reason      text        not null,
    txn_ids     text[]      not null,
    event_time  timestamptz not null,
    detected_at timestamptz not null,
    received_at timestamptz not null default now(),
    status      text        not null default 'NEW'
);

-- Every read is tenant-scoped and newest-first.
create index alert_tenant_detected_idx on alert (tenant_id, detected_at desc);
