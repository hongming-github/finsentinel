# 0029. GPT-6.1 Terra writes the reports; the other roles stay on Luna

- Status: accepted
- Date: 2026-10-28
- Amends: ADR 0023 (every role on Luna), ADR 0011 (model per role)

## Context

The dataset owner's review of the golden cases (ADR 0017, 2026-10-27) tightened five cases to one disposition and
split the regulation sets by question. On the tightened set, with the LARGE_AMOUNT playbook fixed and the chunker's
definition bug fixed (ADR 0007), the default `gpt-6-luna` passed 44/51 and failed the gate: both account-takeover
cases 1/3 (the report cites definitions or EUPG 5.3 where the bank's duties and the loss passages answer the
question) and `outlier-at-home` 1/3 (NEEDS_REVIEW on a familiar-merchant card payment). Playbook wording moved the
takeover cases only between 0/6 and 3/6 over three attempts.

OpenAI's GPT-6 family (developers.openai.com models pages, checked 2026-10-28): Luna $0.10 / $0.50, `gpt-6.1-terra`
$1 / $5 (cached $0.05, cache write $1.25), `gpt-6.1-sol` $2 / $10, `gpt-6-astra` $10 / $50 per million input /
output tokens. The platform calls Chat Completions with function tools at reasoning effort `none` (Spring AI 2.0).
Sol and Astra offer no `none`, so they cannot run a tool-calling role here without a move to the Responses API. Terra
lists `none`; its page says tool calling needs the Responses API, but two probe calls on Chat Completions (a function
tool alone, and a function tool with a strict JSON schema, the investigator's shape) both worked.

## Decision

The investigator (single-agent mode, the default since ADR 0010) runs on `gpt-6.1-terra`. Specialists, writer,
reviewer, case assistant and reranker stay on Luna: they were not compared, and the evidence is about the role that
calls tools and writes the report. The fallback stays on Groq, escalation stays off.

## Evidence

17 cases x 3, SINGLE, final dataset, playbook and chunks; only the investigator differs (ADR 0017 has the table):

| | Luna | Terra |
|---|---|---|
| Pass rate | 44/51 (86%), gate fails | 49/51 (96%), gate passes |
| Regulation relevance | 78% | 100% |
| Takeover cases (critical) | 1/3, 1/3 | 3/3, 3/3 |
| `outlier-at-home` | 1/3 | 2/3 |
| Cost per run | $0.0013 | $0.0078 |
| Latency p50 / p95 | 12.6 s / 30.3 s | 10.7 s / 24.0 s |

Reports: [`docs/eval/2026-10-28-full-luna.md`](../eval/2026-10-28-full-luna.md),
[`docs/eval/2026-10-28-full-terra.md`](../eval/2026-10-28-full-terra.md). Terra is ahead on three cases and behind on
none. The overall gap alone is not significant (Fisher one-sided p = 0.08, runs clustered by case); the decision rests
on the two critical takeover cases, which Luna fails the gate on and Terra passes every time. An earlier 3 x 3 on the
three failing cases only was 9/9 on Terra ([report](../eval/2026-10-28-terra-investigator.md)).

## Consequences

- An analysis costs about six times as much: $0.0078 against $0.0013 per golden run, still under a cent. The hourly
  analysis budget (`max-per-hour`) is a count of analyses, not dollars, so it needs no change; cost per case in the
  load-test and live figures of ADR 0025 is now out of date for real-model runs.
- Terra used a third fewer prompt tokens and was faster; whether from fewer tool rounds or fewer repairs was not
  measured.
- Both models still hedge once in three on `outlier-at-home` and `shopping-trip-burst` (NEEDS_REVIEW where the
  evidence settles the case); that is the card playbook's to fix, not the model's.
- Terra's page says tool calling needs the Responses API. Chat Completions works today; if OpenAI enforces the page,
  the investigator fails on every call and the gateway falls back to Groq (ADR 0012), which is the signal to move.
- MULTI mode, the reviewer on Terra and Sol as reviewer are untested; each is a comparison of its own.
