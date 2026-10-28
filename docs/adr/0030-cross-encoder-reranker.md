# 0030. A cross-encoder reranker, measured against the LLM reranker; production stays on the LLM

- Status: accepted
- Date: 2026-10-28
- Relates to: ADR 0007 (retrieval), ADR 0023 (Luna as the reranker)

## Context

Regulation search reranks the 20 fused candidates with a listwise LLM grade (`LlmReranker`, gpt-6-luna, 0-3 per
passage, 700 characters each). It has scored hit@3 0.97 since the chunker fixes (ADR 0007), but:

- it takes about 4 s per search, most of a search's time;
- its ranking moves between runs (MRR 0.92-0.94 on identical chunks);
- its answer is a model's output: the format can be wrong (the grades are then ignored) and the passages are text a
  model reads, so the "passages are data" line in its prompt is all that stands between a passage and the grader;
- every search sends the question and 20 passages to an outside provider. The passages are public MAS text, but the
  question is written by an agent working on a case, and a bank may not allow it to leave the premises.

A cross-encoder scores each (question, passage) pair with a model that runs where the platform runs: no per-call
cost, the same score for the same input, no output format, and nothing leaves the building. Whether it ranks as well
on this corpus is a measurement, not a given.

## Decision

- A reranker service in `reranker/`: Python, FastAPI, FlagEmbedding 1.4.2 with `BAAI/bge-reranker-v2-m3`
  (Apache-2.0, checked on its model page; pinned to commit `953dc6f6`). `POST /rerank` returns one score in [0, 1] per
  passage in request order; `GET /health` answers once the model is loaded. Python and FlagEmbedding rather than an
  in-JVM ONNX runtime or Hugging Face TEI because the same library fine-tunes the reranker later, on this corpus.
- The request names the model the platform expects; the service refuses another (409), so a swapped model cannot
  change rankings unnoticed, and the search cache key includes it. Errors never echo the request (FastAPI's default
  422 does), so neither the question nor a passage reaches the platform's logs.
- `CrossEncoderReranker` calls it with a connect timeout and a total timeout from
  `finsentinel.knowledge.search.rerank.cross-encoder`. Any failure (down, slow, 4xx/5xx, a body that does not fit) is
  thrown, and `KnowledgeSearch` keeps the fused order and does not cache it, as for a failed LLM call. Its span names
  the reranker, the model and the candidate count, never the text.
- `finsentinel.knowledge.search.rerank.using` chooses the production reranker: `llm` (default) or `cross-encoder`.
  **It stays `llm`** on the evidence below. `HYBRID_CROSS_ENCODER` is an evaluation mode that uses the cross-encoder
  whatever is configured.
- Compose profile `reranker` (host port 8099, model in volume `reranker-models`). The image is CPU-only: the
  dependencies come from the PyTorch CPU index, so Linux x86_64 does not pull about 3 GB of CUDA libraries.

## Evidence

`RetrievalEvalTest`, 32 questions (22 paraphrased, 10 term queries), same index, same 20 fused candidates; hit@3 /
hit@5 / MRR over all 32 questions. Each run evaluates both rerankers on the same index. The cross-encoder ran natively
on the M1 Pro (run 1 on the CPU in fp32, run 2 on the GPU through MPS in fp16):

| | run 1 | run 2 |
|---|---|---|
| hybrid + LLM rerank (Luna) | 0.97 / 0.97 / 0.92 | 0.97 / 0.97 / 0.92 |
| hybrid + cross-encoder | 0.94 / 0.94 / 0.84 | 0.94 / 0.94 / 0.84 |
| vector + LLM rerank | 0.97 / 0.97 / 0.94 | 0.97 / 0.97 / 0.94 |
| hybrid, no rerank | 0.78 / 0.81 / 0.68 | 0.78 / 0.81 / 0.68 |

| Per search | LLM rerank | Cross-encoder |
|---|---|---|
| Time, eval average | 4.0 s, 3.8 s | 5.1 s native CPU (fp32), 1.6 s native MPS GPU (fp16) |
| Time in the compose container (Docker Desktop, 4 vCPU) | | about 19 s for 20 passages of 1,000 characters, 50 s for 20 at 512 tokens |
| Model cost | $0.00048 and $0.00021 (prompt cache warm in run 2) | none; a host with about 2.5 GB of memory |
| Same input, same ranking | no: paraphrase MRR 0.91 then 0.89 | yes: CPU fp32 and MPS fp16 ranked identically |

Where the two disagree (rank of the first relevant passage, LLM / cross-encoder; 0 = not in the top five):

| Question | LLM | Cross-encoder |
|---|---|---|
| What if carrying out due diligence would alert the customer that they are suspected of laundering money? (626 14.4, tipping-off) | 1 | 0 |
| If neither the bank nor the telco breached any duty, who absorbs the money lost to a phishing scam? | 2 | 3 |
| When and how quickly must the bank alert customers about money leaving their account? | 1 | 2 |
| Must customers be able to freeze their own account access immediately without calling anyone? | 1 | 2 |
| enhanced CDD politically exposed person | 1 | 2 |

The cross-encoder is never ahead. The question both rerankers miss ("Who pays back a phishing victim...", SRF 6.2) is
unchanged. The hit@3 gap is the tipping-off question: the passage says "tipping-off", the question describes it, and
the general-purpose cross-encoder does not make that step while the LLM does. The other four are one place lower, which
is where the MRR gap comes from. 32 questions are few: one question is 0.03 of hit@3. But the direction holds on all
five disagreements and in both runs.

Latency depends on the hardware more than on the model. On the laptop's own CPU it is slower than the LLM call. On
its GPU it is 2.4 times faster. In the compose container it is too slow to be useful: Docker Desktop's VM has 4
vCPUs, and Linux aarch64 PyTorch uses OpenBLAS where macOS uses Accelerate. With the 10 s timeout, every search would
fall back to the fused order there. A Linux x86_64 server with more cores or a GPU was not measured.

## Cross-references for the reranker: built, measured, off (2026-10-28)

Notice 626 14.4, the tipping-off question's answer, never says "due diligence": it says "the measures as required by
paragraphs 6, 7 or 8". The cross-encoder scored it 0.17 against the question, and 0.80 once "(customer due diligence)"
was written after the paragraph numbers (one hand-built check). Done generally:

- `CrossReferences` turns a passage's references into a line from the documents' own headings: "Refers to: §6
  (CUSTOMER DUE DILIGENCE (“CDD”)); §7 (SIMPLIFIED CUSTOMER DUE DILIGENCE); §8 (ENHANCED ...)". Resolved: paragraph
  and section numbers, lists and ranges, "Annex A", and references into another document of the corpus by its short
  name ("paragraph 4.7 of the EUPG"). Left out: other instruments ("of MAS Notice TCA-N03"), numbers that name no
  section, and the passage's own section title. 66 of 658 chunks get a line; whole entries past
  `finsentinel.knowledge.context-chars` are dropped (EUPG 4.29's full line runs to 1,000 characters).
- It is made at sync, stored in `kb_chunk.context` (V17) and part of the index version. The reranker reads it between
  the passage's header and its text. The agent never gets it: what a report quotes stays the regulation's own words.

Measured with `RetrievalEvalTest` (all 32 questions, hit@3 / hit@5 / MRR):

| | No context | Context embedded and reranked | Context reranked only |
|---|---|---|---|
| vector | 0.84 / 0.84 / 0.74 | 0.75-0.78 / 0.78-0.81 / 0.66-0.69 (two runs) | 0.84 / 0.84 / 0.74 |
| hybrid + LLM rerank | 0.97 / 0.97 / 0.92 (two runs) | 0.97 / 0.97 / 0.94 | 0.97 / 0.97 / 0.94 |
| hybrid + cross-encoder | 0.94 / 0.94 / 0.84 (two runs) | 0.94 / 0.97 / 0.86 | 0.91 / 0.97 / 0.86 |

- Embedded, it hurt vector search: the PEP question's own passages (section 8, enhanced CDD) dropped out of the top
  five. About ten passages cite "§8 (ENHANCED CUSTOMER DUE DILIGENCE)", and with that line their vectors moved
  towards every enhanced-CDD question. A vector compresses the whole passage into one point, and a heading that many
  passages cite moves all of them. So the line is not embedded.
- Reranked only, the cross-encoder ranks the tipping-off passage first (it was not in the top five) and loses two
  others to rank 4. On "alert customers about money leaving their account", SRF 4.2.5's line ("real-time detection
  and blocking") matched "how quickly". On "when is the mobile operator responsible", SRF 6.4 and 7.7.1 carry the
  same line, so it separated nothing. Net: hit@3 one question lower, hit@5 one higher, MRR +0.02.
- The LLM reranker's MRR of 0.94 is inside its usual 0.92-0.94.

Decision: the code stays, `context-chars` defaults to 0 (off). On 32 questions the result cannot be told from noise,
and tuning the rules against the same 32 questions would fit them rather than the corpus. A larger evaluation set
with more cross-reference questions comes first; then measure again with
`-Dfinsentinel.knowledge.context-chars=300`. `-Dretrieval.modes=VECTOR,KEYWORD,HYBRID` measures the stages without
a reranker for nothing once the embeddings are cached, and `-Dretrieval.ranks=true` prints every question's rank.

## Consequences

- Production keeps the LLM reranker: it ranks better on this corpus, and the cross-encoder's advantages (no data
  leaving, determinism, no format or injection surface, no per-call cost) do not outweigh a lower hit@3 for this
  project. A bank that cannot send questions out switches with one setting and accepts the measured cost: hit@3 0.94
  instead of 0.97, MRR 0.84 instead of 0.92, and a GPU host to stay under the LLM's latency.
- The cost argument is small either way: $0.0002-0.0005 per uncached search, with exact-question caching on top
  (ADR 0013).
- The disagreements above are fine-tuning material: regulatory paraphrases ("alert the customer" for tipping-off) are
  where a general cross-encoder falls short. Fine-tuning `bge-reranker-v2-m3` on question/passage pairs from this
  corpus, with the evaluation set held out, is the follow-up the Python service was chosen for. A larger set of
  evaluation questions should come first, so the result can be told apart from noise.
- `cross-encoder.timeout` (10 s) covers the measured native CPU and GPU averages. A deployment has to measure its own
  host. A timed-out search costs the timeout, then returns the fused order.
- The service is another component to run and patch: a 2.3 GB image, a 2.3 GB model download on first start (in a
  volume), Dependabot for its lock file and base images. The lock file waits two weeks for new releases
  (`exclude-newer`), except security fixes: certifi 2026.10.23 (GHSA-6q48-6c7w-9gw2) and filelock 4.2.4
  (CVE-2026-112940), both younger than that. Trivy finds nothing HIGH or CRITICAL in the lock file or the image
  (2026-10-28).
- CI does not download the model. The Java client is tested against a fake HTTP server, the Python service with a
  fake scorer. The live comparison stays in `RetrievalEvalTest`: its cross-encoder mode runs when the service answers
  `/health`, or fails with `-Dcross-encoder=true` when it does not. A ranking that fell back to the fused order fails
  the run, so a down service can never pass as a cross-encoder score.
