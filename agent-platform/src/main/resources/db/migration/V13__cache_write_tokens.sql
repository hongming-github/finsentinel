-- Prompt tokens each analysis wrote to the provider's prompt cache (ADR 0023): billed at 1.25x input from GPT-5.6 on,
-- and with cached_input_tokens the measure of whether prompts reuse the cache. Null for older analyses and for models
-- that do not report writes.
alter table case_analysis add column cache_write_tokens int;
