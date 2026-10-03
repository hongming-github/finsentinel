-- Phase 4C3: fixed-window rate limits shared by every platform instance (no in-memory counters). One row per key
-- and window; an atomic upsert counts the call and returns the new total.
create table rate_limit_window (
    key          text        not null,
    window_start timestamptz not null,
    used         int         not null,
    primary key (key, window_start)
);
