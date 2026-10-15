# 0011. Model per agent role, deterministic escalation, and cost per case at list prices

- Status: accepted
- Date: 2026-10-03

## Context

Phase 5 (LLM gateway) starts with two questions an operator of this system asks first: what does one alert cost,
and is the stronger model worth it where it matters? Phase 3 had measured gpt-5.5 9/9 against gpt-5.4-mini 8/9 on
an earlier scenario set, the misses on transfer scams, so the plan was to route transfer and HIGH cases to gpt-5.5.

## Decision

- **A model per agent role** (`finsentinel.llm.models`: investigator, specialist, writer, reviewer), set per call:
  ChatClient starts from the model's configured options (`reasoning-effort: none` and the tools survive) and only the
  model name is overridden. Reranking keeps its own setting.
- **Deterministic escalation** (`finsentinel.llm.escalation`): a case with a transfer or an alert of a listed
  severity switches the listed roles to the escalation model. No model decides which model to use.
- **Cost per case at list prices.** `finsentinel.llm.prices` (USD per million tokens, standard tier, with the date
  they were checked); every call is priced from the model the provider reports (a dated snapshot is priced by the
  longest configured prefix) with cached input billed at the cached rate, not twice. Usage carries cost in total and
  by model, sums across agents, revisions and resumes, and includes the reranker's calls inside regulation searches
  (query embeddings are not counted: about $0.000001 per search). Stored per analysis (V9 `cost_usd`,
  `cost_by_model`), logged by the worker, and reported by `GET /api/tenants/{t}/llm-usage?days=` (analyses, total,
  average, p95 and max cost per case, cost by model, cost and latency by analysis mode). A configured model without a
  price fails at startup.
- **Escalation off by default**, on the measurement below. One configuration line turns it on.

## Measurement (live, 6 scenarios x 3 runs x both modes per configuration)

| Configuration | SINGLE: expected / cost per case / latency | MULTI: expected / cost per case / latency |
|---|---|---|
| A: every role on gpt-5.4-mini | 18/18, $0.0147, 9.9 s | 17/17 (one invalid-report error), $0.0240, 13.8 s |
| B: investigator and writer escalated to gpt-5.5 on transfer or HIGH | 18/18, $0.0772, 15.1 s | 18/18, $0.0583, 16.3 s |
| C: B plus the reviewer on gpt-5.5 | incomplete: the API credit ran out after one run (5 + 4 cases, all approved) | |

15 of the 18 scenarios escalate (most are transfers or HIGH), so B costs 5.3x per case and is about 50% slower,
for no gain: A already gets every expected direction. The scenarios are too easy to separate the models; phase 7's
larger evaluation set is where the escalation decision is re-taken. C's early numbers suggest a stronger reviewer
raises fewer immaterial objections; unproven on this sample.

## Also found

- MULTI: twice in live runs a report kept citing a transaction id no tool had returned, through the repair, failing
  the attempt (and a retry re-runs the three specialists). The id was not one of the alert's transactions, so it was
  invented. The writer's repair now names the ids it may cite, and the failure log says which specialist, if any,
  cited the id. Not yet re-measured live (credit ran out).

## Cost optimisations that followed (2026-10-03, after the OpenAI credit ran out)

- **Risk-based review** (`finsentinel.analysis.review-dispositions`, default LIKELY_LEGITIMATE and NEEDS_REVIEW):
  LIKELY_FRAUD reports go to the analyst unreviewed, marked as such (`review.skippedBecause`), so nobody takes them
  for reviewed ones. The reviewer's real catches were on reports that clear a case; its immaterial objections were
  mostly on fraud reports; every case is decided by an analyst anyway. AML operations sample closures for the same
  reason. Saves every review call on fraud reports; to be measured when credit is back.
- **Live suite runs one mode by default** (`-Dlive.modes=SINGLE,MULTI` for a comparison): about $0.10 a run.
- Next candidates: prompt order for provider prompt caching (stable text first; cached input is billed at 10% of
  the input price), and the semantic cache of step 5C.

## GPT-6 Luna and cache-write pricing (2026-10-15)

`gpt-6-luna` costs $0.10 input, $0.01 cached input and $0.50 output per million tokens (gpt-5.4-mini: $0.75, $0.075,
$4.50), supports Chat Completions with tools and structured output (tools only with reasoning effort `none`, which we
already set), and is OpenAI's recommended replacement for `gpt-5.4-nano`, our reranker, which is shut down on
2027-04-01 (developers.openai.com models, pricing and deprecations pages, checked 2026-10-15).

From GPT-5.6 on, OpenAI also bills **cache writes**: prompt tokens written to the prompt cache cost 1.25x input
($0.125 for Luna) instead of the input rate. Luna's first call with a 2,245-token prompt reported
`prompt_tokens_details.cache_write_tokens: 2242`; the same prompt again, `cached_tokens: 2242`. Spring AI 2.0.1 drops
the write count (it passes null as the cache-write tokens) but keeps the SDK's usage, so `LlmUsage` reads it from
there and `LlmPricing` bills written tokens at the model's `cache-write` price (input price when none is set).
Without that, a Luna call's cost comes out low by a fifth of every written token: 0.00132 instead of 0.00147 in
`LlmPricingTest`, which fails when the read is removed. The written tokens are priced but not yet stored per
analysis (only cached tokens are).

Smoke run (`geo-impossible-travel`, every role on Luna, Groq unset so no fallback could answer): passed, LIKELY_FRAUD
96, $0.0008 (gpt-5.4-mini: $0.004-0.007 on the same case), 10.6 s (one run; mini 4-7 s). A decision needs the full
evaluation with cost, not one case.

## Consequences

- Every analysis has a price; routing changes can be judged in dollars and accuracy, not guessed.
- List prices drift and discounts (batch, flex, negotiated) are not modelled; the date the prices were checked is in
  the configuration.
