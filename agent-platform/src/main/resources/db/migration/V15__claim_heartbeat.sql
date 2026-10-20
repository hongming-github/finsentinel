-- A running analysis renews its claim (ADR 0026, drill 10): a claim nobody renews for a lease lapses, so a killed
-- instance's cases are taken over in seconds instead of after the claim timeout. claimed_at stays the fencing token and
-- is never touched by a renewal. Null until the first renewal.
alter table fraud_case add column claim_renewed_at timestamptz;
