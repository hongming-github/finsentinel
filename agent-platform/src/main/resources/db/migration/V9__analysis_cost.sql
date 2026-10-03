-- Phase 5A: what each analysis cost at list prices, in total and by model (reranking included). Null for analyses
-- stored before cost accounting.
alter table case_analysis add column cost_usd numeric(12, 6);
alter table case_analysis add column cost_by_model jsonb;
