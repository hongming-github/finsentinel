# 0023. GPT-6 Luna as the default model, and prompts shaped for message-boundary caching

- Status: accepted
- Date: 2026-10-15
- Amends: ADR 0011 (model per role), ADR 0007 (reranker), ADR 0013 (prompt order for the provider's cache)

## Context

Every agent role ran on `gpt-5.4-mini` and the regulation reranker on `gpt-5.4-nano`. OpenAI's GPT-6 family arrived
with `gpt-6-luna` at $0.10 / $0.01 / $0.50 per million input / cached / output tokens (mini: $0.75 / $0.075 /
$4.50), and `gpt-5.4-nano` is shut down on 2027-04-01 with Luna as the named replacement (developers.openai.com
models, pricing and deprecations pages, checked 2026-10-15). Luna supports Chat Completions with function tools only
at reasoning effort `none`, which the platform already uses, and structured output.

GPT-5.6 and later models also cache differently: cache writes are billed (1.25x input, see ADR 0011, where the
cost accounting was fixed for it), and the cache is written and looked up at message boundaries (implicitly at the
end of the latest user message and of the initial system/developer block) instead of on any matching prefix.

## Decision

1. **Every agent role and the reranker on `gpt-6-luna`.** The fallback stays on Groq (it guards against an OpenAI
   outage, so it must be another provider); escalation stays configured on `gpt-5.5` and off, as ADR 0011 decided;
   `gpt-6.1-sol` ($2 / $10) is the candidate there once escalation is measured again. Embeddings are unchanged.
2. **Shared guidance in the system message, case data in the user message.** The role prompt, the playbooks of the
   rules that fired and the bank's unconnected-systems note now form the system message
   (`ReportWriting.systemPrompt`); the user message holds only the case (`ReportWriting.caseData`). Before, the
   playbooks headed the user message, ahead of the alerts: right for prefix caching (ADR 0013), wrong for
   boundary caching.
3. **Tool definitions sorted by name** (`InvestigationTools.allCallbacks`). They open every prompt, and reflection
   promised no order; without the sort the test sees them unsorted.

## Evidence

**Caching experiment** (raw Chat Completions, Luna, the real investigator prompt, GEO_JUMP playbook, 8 tool
definitions, three different cases in a row, run twice with identical results; about $0.006):

| Structure | case 1 | cases 2 and 3 |
|---|---|---|
| playbook at the head of the user message (old) | 2,607 written | **0 cached**, ~2,600 written again |
| playbook as its own developer message | 2,611 written | 1,493 cached, ~1,110 written |
| playbook appended to the system message (chosen) | 2,607 written | 1,489 cached, ~1,110 written |

With the old structure Luna would have written the whole prompt at 1.25x for every case and read nothing back. A
developer message works as well; the system message was chosen because Spring AI's `ChatClient` has no developer
role and older prefix-caching models are indifferent to it.

**Evaluation** (`docs/eval/2026-10-15-luna.md`: 17 golden cases x 3, SINGLE, every role on Luna, Groq unset so no
fallback could answer; reranker still on nano):

| | gpt-5.4-mini (baseline, 16 cases, before the GEO_JUMP fix) | gpt-6-luna (17 cases) |
|---|---|---|
| Pass rate | 92% (44/48) | **100% (51/51)** |
| Tool selection / regulation relevance | 98% / 100% | 100% / 100% |
| Cost per run / total | $0.0104 / $0.501 | **$0.0012 / $0.060** |
| Latency p50 / p95 | 7.0 s / 18.3 s | 11.6 s / 22.9 s |

Luna is more conservative where the expectations allow it: `app-scam-new-payee`, `injection-payee-name` and
`bank-without-screening` came back NEEDS_REVIEW 3/3 (mini: some or all LIKELY_FRAUD), `outlier-at-home` once
NEEDS_REVIEW. All critical cases 3/3.

**Reranker** (`RetrievalEvalTest`, 32 questions, hit@5 / MRR): Luna 0.94 / 0.91 against nano's 0.94 / 0.87 in ADR
0007 (paraphrased 0.91 / 0.89, terms 1.00 / 0.95), about 4.0 s per query against 2.8 s.

**Smoke** before the evaluation: one case, Groq unset, $0.0008 against mini's $0.004-0.007.

## Consequences

- About a ninth of the model cost per analysis, and a better-ranked regulation search, for slower analyses (p50 up
  4.6 s, rerank up 1.2 s a search). Analysis runs off a queue after authorization, so seconds matter less than cost;
  a latency target for the analyst queue would reopen this.
- The comparison is not clean: the mini baseline predates the GEO_JUMP playbook fix, geo-velocity (ADR 0021) and this
  prompt restructuring, and has 16 cases. Re-running mini was skipped on purpose (the platform moves to the GPT-6
  family); the conclusion rests on Luna passing every case at a ninth of the cost, not on a precise delta.
- Cache-write tokens are priced but not stored per analysis; the cached-token share of Luna runs is not yet measured
  end to end (the evaluation report does not carry token counts).
- `prompt_cache_key` is not used: OpenAI documents it as unnecessary for caching on GPT-5.6 and later. Explicit cache
  breakpoints are documented for the Responses API only; Spring AI 2.0 uses Chat Completions.
