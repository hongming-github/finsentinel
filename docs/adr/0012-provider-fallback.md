# 0012. One gateway for every model call, with provider fallback and circuit breaking

- Status: accepted
- Date: 2026-10-03

## Context

The OpenAI credit ran out in the middle of a measurement (ADR 0011): every analysis failed. The roadmap's phase 5 asks
for exactly this case: "fallback works with the primary provider down". A second provider was available: Groq, an
OpenAI-compatible API, with `openai/gpt-oss-120b` at $0.15 / $0.60 per million tokens (gpt-5.4-mini: $0.75 / $4.50).

## Decision

- **`LlmGateway`: every model call goes through one place**: the agents (via `AgentModels`) and the reranker. It
  picks the provider's chat client, sets only the model per call, enforces the JSON Schema where the provider can,
  prices the answer, and falls back.
- **Providers are configuration** (`finsentinel.llm.providers`): `openai` is Spring AI's auto-configured client;
  others are OpenAI-compatible endpoints with their own base URL, key and extra request fields. Models are written
  `provider:model` (`groq:openai/gpt-oss-120b`); a bare name is OpenAI. A provider without a key is unavailable.
- **Fallback on provider failure only.** Any OpenAI SDK error in the chain (HTTP error status, rate limit, out of
  credit, network) sends the call to `finsentinel.llm.fallback.model` at another provider. A bad answer from a healthy
  provider is not a provider failure: validation and repair handle it (a scripted test proves the fallback is not
  asked).
- **Circuit breaker per provider and instance**: after `failure-threshold` consecutive failures the provider is
  skipped for `open-for`, then one trial call decides. In memory on purpose: it only saves each instance the wait on a
  provider it has seen fail and guards no shared state; claims and budgets stay in Postgres.
- **Capability differences are explicit.** Groq enforces a JSON Schema only in requests without tools
  (`structured-output-with-tools: false`): tool-using agents get the format in the prompt and the report validator
  checks the result; tool-less calls (writer, reviewer, rerank) still get the schema enforced.
- **Search degrades instead of failing**: if the query cannot be embedded (embedding provider down, question not in
  the embedding cache), regulation search uses keyword ranking alone, as it already did when reranking failed.

## Found on the way

- Groq rejects `reasoning_content` in assistant messages (400), and Spring AI 2.0.1 echoes the reasoning gpt-oss
  returns into the tool-call turns. Groq is asked not to return reasoning (`extra-body: include_reasoning: false`),
  so there is nothing to echo.
- The Groq account here is limited to 8,000 tokens per minute (response headers); an analysis request is 10-20k
  tokens, so every call waits for the window. Latency in the fallback run (55-166 s per case) is that limit, not Groq:
  a small call took 0.55 s.

## Measurement (live, OpenAI out of credit, everything on groq:openai/gpt-oss-120b, SINGLE mode, one run)

| | gpt-5.4-mini (ADR 0011, config A) | fallback: gpt-oss-120b on Groq |
|---|---|---|
| Expected direction | 18/18 | 6/6 |
| Cost per case | $0.0147 | $0.0023-0.0050 (about $0.003) |
| Thoroughness | | skipped the security-events and payee-screening tools in the account-takeover case (still LIKELY_FRAUD 90): 5/6 tests |
| Latency | 9.9 s | 55-166 s (account rate limit) |

## Consequences

- The platform keeps analysing through a provider outage, at a lower cost, with somewhat less thorough
  investigations; the stored cost by model shows which cases were answered by the fallback.
- gpt-oss-120b is a candidate for cheaper roles (writer, reviewer, rerank) once a paid Groq tier removes the rate limit
  and a comparison on the larger evaluation set (phase 7) shows quality holds.
- One more dependency to watch: OpenAI-compatible is not identical (reasoning fields, structured-output limits).
