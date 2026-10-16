# 0013. Caching: exact-match search results in Redis, no semantic cache, static prompt text first

- Status: accepted
- Date: 2026-10-09

## Context

The roadmap's phase 5 called for a Redis **semantic cache**: reuse a regulation search result when a new question
embeds close to one already answered. Every search costs a rerank call on gpt-5.4-nano (about $0.0008 and 1-2 s) plus
an embedding, and the investigator's questions repeat across cases: of the 18 searches in the dev database, six are
variations of "MAS Notice 626 suspicious transaction report when to file", but only two are word-for-word repeats.

Separately, OpenAI caches the longest prompt prefix it has recently seen and bills cached input at a tenth of the
price (gpt-5.4-mini: $0.075 instead of $0.75 per million). The agents' system prompt, tools and response schema are
identical for every case, so cases should have been reading part of their prompts from that cache.

## Measurement 1: is a semantic cache safe for regulation search?

`SemanticCacheEvalTest` (live, embeddings only, under $0.001): 71 questions in 24 groups, labelled by the passages
that answer them (the ids of `retrieval-eval.json`). Two questions may share a cached result only if their relevant
passages overlap. Groups include deliberate near misses: filing an STR vs documenting why one was not filed; who bears
a loss under EUPG §5.2 vs SRF §6.2 / §6.4 / §6.7; an authorised payment, which is not an unauthorised transaction at
all (EUPG §5.3). Questions arrive in 50 random orders; each is answered by the nearest cached question above the
threshold, as a semantic cache would.

| text-embedding-3-small | cosine |
|---|---|
| same-answer pairs: min / median / max | 0.399 / 0.555 / 0.875 |
| closest different-answer pair ("STR when to file" vs "record reasons for not filing an STR") | 0.840 |
| agent-written: "SRF who bears loss for an **authorised** transfer scam" vs "... **unauthorized** transfer scam" (different answers) | **0.947** |
| agent-written: "STR obligation when to file" vs "STR when to file" (same answer) | 0.960 |

| threshold | hits per lookup | wrong hits |
|---|---|---|
| 0.70 | 16.1% | 71.6% of hits |
| 0.75 | 10.7% | 60.7% |
| 0.80 | 3.3% | 22.0% |
| 0.85 | 1.4% | 0% |
| 0.90+ | 0% | 0% |

No threshold works: below 0.85 a large share of hits hand over the wrong passages, and the agent's own
authorised/unauthorized pair (0.947) sits above most true paraphrases. Embeddings encode the topic, not the polarity
or the party; telling those apart is exactly what the reranker is for, so reusing its output by embedding similarity
brings back the error it removes.

A verifier was tried as well: similarity nominates, gpt-5.4-nano judges "would the same passages answer both?"
(about 200 tokens per check, a twentieth of a rerank). On the near pairs it accepted 0 of 6 true paraphrases and
accepted the authorised/unauthorized pair: worse than no cache. A stronger judge might do better, but the most it can
save is one nano call per search, about $0.0024 per case at three searches.

## Measurement 2: why did no case reuse the provider's prompt cache?

Per-request usage from the live suite (`OPENAI_LOG=debug`) showed `cached_tokens = 0` on the first model request of
every case, although system prompt, 9 tool definitions and response schema were byte-identical across cases (checked
on the logged request bodies). The case brief put the alerts (unique ids, tenant, timestamps) before the rule's
playbook. Replaying two logged first requests of different cases, 4 trials each:

| brief order | second case: cached / prompt tokens |
|---|---|
| alerts, then playbook (as built) | 0 / 2047 (4 of 4 trials) |
| playbook, then alerts | 1664 / 2048 (4 of 4 trials) |

The mechanism behind the 0 is not fully explained by the documented "longest shared prefix" rule (the identical
system prompt, tools and schema alone exceed 1,024 tokens); the measured behaviour is what the design relies on, and a
test guards it.

## Decision

- **Search results are cached by exact question only.** `SearchCache` keys Redis entries by the question after
  folding Unicode compatibility forms, case, whitespace and trailing punctuation. Spelling variants stay distinct
  ("unauthorised"/"unauthorized"): a miss costs one rerank, a wrong hit costs a wrong citation in a fraud report.
- **The key carries the corpus version and the search settings** (embedding model, candidates, top-k, RRF k, rerank
  on/off, model, passage length). The corpus version is a hash of every document's id, checksum, chunk count and
  ingest time, read with each search, so a re-ingest on any instance misses instead of serving stale passages. Entries
  expire after `ttl` (7 days) in any case.
- **Only complete rankings are stored**: a result produced while the embedding or rerank provider was down (keyword
  only, or fused order) is served but not cached, or the outage would outlive itself by a week.
- **The value is chunk ids plus what the search cost**, never passage text: passages are read fresh from Postgres,
  and a hit whose ids are no longer all present is treated as a miss. The question is hashed, not stored. The corpus
  is public and global, so one entry serves every tenant (the knowledge base is the one global read, ADR 0015).
- **Redis is disposable.** Any Redis error is a miss (250 ms timeouts), its health indicator is off so an outage does
  not mark the platform DOWN, and two instances missing at once both search and both write the same value. Postgres
  stays the place for shared state that must be right (claims, budgets); Redis holds what is only worth money.
- **Reporting**: each `searchRegulations` tool call records `cacheHit` and, on a hit, `savedUsd` (the cost of the
  search that produced the entry). `GET .../llm-usage` reports searches, hits, hit rate and saved dollars per tenant;
  Micrometer counts `finsentinel.knowledge.search.cache` by result (hit, miss, error) across all callers, MCP included.
- **Prompts put text shared by cases first and case data last.** `ReportWriting.caseBrief` (used by every agent)
  writes playbooks, then alerts, then the analyst's note.

## Result (live suite, SINGLE, gpt-5.4-mini; 1 run before, 3 after)

| | before | after: run 1 / 2 / 3 |
|---|---|---|
| first investigator request of a case: cached tokens | 0 in every case | 1664 of ~2050 from the 2nd or 3rd case on (run 3: every case, the cache was still warm from run 2) |
| share of mini input tokens served from cache | 44% | 51% / 53% / 57% |
| expected directions | 6/6 | 18/18 |
| regulation search cache hits | | 0 of 12 (each case words its questions differently) |

The prompt order saves roughly $0.001 per case (about 10%) for no cost at all. The search cache's hit rate is low in
a six-case suite; on the dev data 2 of 18 searches were exact repeats. It matters more for the MCP server, whose
external callers repeat questions verbatim.

## Consequences

- The phase-5 "semantic cache" item is closed as measured and rejected, with the evaluation kept as a live test to
  re-run if the embedding model changes (a model that separates polarity could reopen it).
- A spelling or word-order variant of a cached question misses. Raising the exact-hit rate would mean making the
  agent ask canonical questions (e.g. playbooks naming the searches to run), which is a prompt change with its own
  live comparison.
- Any new prompt must keep static text ahead of per-case data; `casesOfTheSameRuleShareThePlaybookAsAPromptPrefix`
  fails if the brief puts case data first. Cached-token share is the number to watch, not cost per case (which swings
  with the number of tool rounds).
- `prompt_cache_key` (supported by Spring AI 2.0.1) was not set: it is a routing hint for traffic where many requests
  share a prefix, and the reorder alone already produced hits at this platform's rate. Not measured.
