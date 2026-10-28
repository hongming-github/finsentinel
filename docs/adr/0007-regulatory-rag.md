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

## Definitions fix (2026-10-27)

A defined term was split off only in the form `“term” means`. MAS documents also write `“term” include`, `“term”,
in relation to X, means`, `“term” or “other term” means` and `“term” –`; each of those stayed inside the previous
term's chunk. Ten chunks held two definitions, among them the three that matter most here: "unauthorised transaction"
was retrieved as `eupg-2024:2.1:unique-identifier`, "seemingly authorised transaction" (the SRF's scope) as
`srf-2024:2.1:responsible-telco`, "high-risk activities" as `eupg-2024:2.1:finance-company`, and agents cited them
under those ids. Found by dumping every chunk for review (no tool showed them before). `DEFINITION` now accepts each
form; a quoted term followed by anything else still continues the definition before it (tested). Result: 625 -> 636
chunks, 11 new, 11 changed, no other chunk changed. `RetrievalEvalTest` unchanged in every mode (hybrid + rerank 0.94 /
0.91): none of its 32 questions asks for a definition, which is why it did not catch this. Definition questions belong
in the set.

## Notice 626 sub-headings (2026-10-28)

Notice 626 numbers its sub-headings "(I) Identification of Customer", "(II) ...". Starting with a bracket, they did
not count as headings: each stayed at the end of the paragraph before it, and every paragraph after it kept the
previous sub-heading. So 6.4 to 6.26 were filed under "Where There Are Reasonable Grounds for Suspicion ..." (6.13,
on beneficial owners, among them), and the wire-transfer paragraphs likewise. The section path is part of the embedded
text and of what the model reads. Found by the dataset owner reading chunks. A roman-numbered line now starts a
sub-heading, its own level under an unnumbered one ("Responsibility of the Ordering Institution / (I) ...").
30 section paths and 9 paragraph tails changed, all in Notice 626; the other documents have none.
`RetrievalEvalTest` after the fix, every mode up (before: ADR 0023's numbers, unchanged by the definitions fix):

| Mode | paraphrased (22) | term queries (10) | all (32) |
|---|---|---|---|
| vector | 0.82 / 0.69 | 0.90 / 0.85 | 0.84 / 0.74 (was 0.84 / 0.70) |
| keyword | 0.50 / 0.39 | 0.90 / 0.75 | 0.63 / 0.51 (was 0.59 / 0.50) |
| hybrid (RRF) | 0.77 / 0.58 | 0.90 / 0.85 | 0.81 / 0.66 (was 0.78 / 0.64) |
| hybrid + rerank | 0.95 / 0.91 | 1.00 / 1.00 | 0.97 / 0.94 (was 0.94 / 0.91) |

No question asks about Notice 626's customer due diligence, so the gain is most likely fewer wrong candidates: 23
chunks no longer carry "Where There Are Reasonable Grounds for Suspicion" in their embedded text, a phrase close to
the suspicious-transaction questions (a likely reason, not traced question by question). One miss left (the phishing-refund question). The vector,
keyword and hybrid rows are deterministic; the rerank row has the reranker's run-to-run noise.

Lettered numbers (also 2026-10-28, Notice 626 only): an amendment inserted section 11A (value transfers) between 11
and 12. Its heading "11A VALUE TRANSFERS" was not a section (numbers had to be integers ascending by one), so all of it
sat inside `notice-626-2025:11.16:value-transfer-originator-p1` to `-p4`, an id that named the wrong paragraph. A
lettered section now follows its number (11, 11A, 12) and its paragraphs (11A.1 to 11A.21) are chunks of their own:
636 -> 654 chunks, 9 removed and 27 added, nothing else changed. Lettered paragraphs inside a section (6.8A, 6.14A-D,
15.7A) still continue the paragraph before them, which reads naturally (6.14A to D elaborate 6.14) and keeps their
number in the text; the dataset owner agreed. `Grounding` and `GoldenDataset` now accept `11A.3` in chunk ids, and
`Grounding` also reads a split paragraph's `-p2` whole (it cut it to `6.14`, so a correct answer would have been refused,
and an `11A` id was not seen at all, so an answer naming one it was not given would have passed).
`RetrievalEvalTest` after it: hybrid + rerank unchanged at 0.97 / 0.94 (paraphrased 0.95 / 0.93, terms 1.00 / 0.95);
vector 0.84 / 0.74 -> 0.81 / 0.71, all of it in the term queries (0.90 / 0.85 -> 0.80 / 0.75); keyword unchanged; hybrid
0.81 / 0.66 -> 0.81 / 0.65. The likely cause (not traced per question) is that 11A's value-transfer paragraphs are near
copies of the wire-transfer ones the set asks about ("cross-border wire transfer exceeds S$1,500 originator
information" expects 11.5; 11A has the same rule for value transfers), real text that was hidden before. The
pipeline the agents use, hybrid + rerank, does not lose anything.
