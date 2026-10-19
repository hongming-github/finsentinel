-- A failed analysis attempt waits a growing pause before the next one (ADR 0026, drill 02): attempts on consecutive
-- polls used up all of them within a model outage of half a minute. Null when the case is not waiting.
alter table fraud_case add column next_attempt_at timestamptz;
