-- Phase 4C2: an analysis records how it was produced (single investigator or multi-agent) and the reviewer's last
-- verdict, so an analyst sees the reviewer's objections and the two modes can be compared from the data.
alter table case_analysis add column mode text;
alter table case_analysis add column review jsonb;
