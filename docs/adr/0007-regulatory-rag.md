# 0007. Regulatory RAG: structure-aware chunks, hybrid retrieval with rerank, per-rule playbooks

- Status: accepted
- Date: 2026-10-02

## Context

Analysts act on the agent's recommended actions, so obligations it mentions (cooling-off periods, who bears a
scam loss, suspicious transaction reporting) must come from the actual MAS texts, with a citation. Corpus: four
public MAS documents (EUPG, Shared Responsibility Framework, Notice 626, TRM Guidelines), ~160 pages.

## Decision

**Ingestion.** `knowledge/sources.json` lists the documents; `scripts/fetch-knowledge.sh` downloads them (not
committed: MAS copyright). On startup `KnowledgeSync` indexes new or changed PDFs by SHA-256, removes documents no
longer listed, refuses runs above `max-chunks`, and skips documents that produce no chunks instead of indexing them
empty. Embeddings (`text-embedding-3-small`) are cached on disk by model and text, so re-indexing is free.

**Chunking.** One chunk per numbered paragraph (`EUPG §4.7`), never fixed-size windows: citations must point to a
paragraph a lawyer can find. Rules learned from the documents: section numbers must ascend from 1 (dates and
footnotes look like headings); paragraph numbers must ascend and start with a capital (cross-references wrap onto
line starts); numbered sub-section titles and wrapped headings become context, not chunks; definitions split per
term; text under 10pt is dropped (footnotes and markers); running footers are matched ignoring page numbers.

**Retrieval.** pgvector (HNSW, cosine) and Postgres full-text (`ts_rank`, OR-ed terms) fused with reciprocal rank
fusion, then a listwise rerank by `gpt-5.4-nano`. A failed rerank falls back to fused order.

| Mode | paraphrased (22) | term queries (10) | all (32) | ms/query |
|---|---|---|---|---|
| vector | 0.82 / 0.67 | 0.90 / 0.78 | 0.84 / 0.70 | ~1 |
| keyword | 0.45 / 0.39 | 0.90 / 0.75 | 0.59 / 0.50 | ~1 |
| hybrid (RRF) | 0.73 / 0.58 | 0.90 / 0.78 | 0.78 / 0.64 | ~2 |
| hybrid + rerank | 0.91 / 0.86 | 1.00 / 0.88 | 0.94 / 0.87 | ~2,800 |

Hit@5 / MRR, `RetrievalEvalTest`. Query embeddings were cached, so ms excludes the embedding call. Hybrid without
rerank does not beat vector alone here; rerank is what pays. Tuning stopped at this point: 32 self-authored
questions are a regression baseline, and further tuning would fit the set rather than the corpus.

**Agent use.** `searchRegulations` returns 3 passages trimmed to 900 characters (five full passages diluted the case
evidence enough to flip an impossible-travel fraud to "legitimate"), at most 3 searches per case. Regulatory
statements go in `regulatoryNotes` and must cite chunk ids returned by a search (validated like transaction
evidence). A suspicious case involving a transfer must carry notes; the model skipped the search too often to leave
that to the prompt.

**Playbooks.** Rule-specific guidance lives in `prompts/playbooks/<RULE>.md` and only the alert's playbook is sent.
With everything in one system prompt, transfer-scam guidance talked the model out of a card fraud twice.

## Consequences

- Every regulatory statement in a stored report is traceable to a paragraph and page.
- Rerank adds ~3 s per search; acceptable for asynchronous investigations.
- `gpt-5.4-mini` (non-reasoning) still misjudges the authorised-push-payment case about one time in three;
  `gpt-5.5` was 9/9 in the same test. Phase 5 routes transfer and HIGH-severity cases to the stronger model.
- The corpus is global (public regulation), the one deliberate exception to tenant-scoped reads.
