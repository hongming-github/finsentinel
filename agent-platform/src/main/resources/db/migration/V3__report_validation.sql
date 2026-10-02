-- Reports are now validated before they are stored, so an ungrounded report never lands here.
-- What is worth keeping instead: which problems a repair call had to fix, and prompt-cache usage.
alter table case_report
    drop column ungrounded_txn_ids,
    add column repaired_issues     text[] not null default '{}',
    add column cached_input_tokens int,
    add column llm_calls           int    not null default 1;
