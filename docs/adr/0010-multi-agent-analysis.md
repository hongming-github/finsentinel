# 0010. Multi-agent analysis (Plan / Execute / Express / Review), measured against a single agent

- Status: accepted
- Date: 2026-10-03

## Context

Phase 4 planned a multi-agent analysis inside the claimed ANALYSIS stage (ADR 0008): triage, parallel specialists,
an investigator and a reviewer that can send the report back. Ant Group's agentUniverse frames the same shape as
PEER (Plan, Execute, Express, Review). The single investigator from phase 2 already got 18/18 directions right on
the live scenarios, so a split had to earn its cost.

## Decision

Both shapes run on the same graph runtime, saver and reviewer; `finsentinel.analysis.mode` picks one.

```
SINGLE: START → investigate → review ─┬→ END
                     ↑─── revise ─────┘
MULTI:  START → plan → transactions ┐
                     → customer     ├→ write → review ─┬→ END
                     → regulations  ┘    ↑─── revise ──┘
```

- **Plan is deterministic.** Code picks the specialists from the data: transactions and customer always, regulations
  only when a transfer is involved. A model-based triage that ends cases early was rejected: a missed fraud costs far
  more than the calls it would save, and the routing needs no judgement.
- **Execute: specialists in parallel, each with only its own tools** (a subset of the case-scoped tools) and its own
  instructions. They report facts with evidence ids; they do not decide. Each writes its own state key, so parallel
  branches need no reducer. The library treats the parallel group as one node: one checkpoint after all of them, and
  a failure in one re-runs all three on retry.
- **Express: a report writer without tools**, validated and repaired like the single agent, grounded in the union of
  ids the specialists' tools returned and in the regulations specialist's passages.
- **Review in both modes: a second model, the four-eyes check**, sends a report back at most
  `max-revisions` (default 1) times; the last verdict is stored with the analysis (`case_analysis.review`, V7) so an
  analyst sees an unresolved objection. The reviewer sees exactly the evidence the author had: the raw tool results
  for the single agent (captured by wrapping each tool callback), the specialist reports for the writer.
- **Budget.** Every call happens inside a claimed analysis, so the hourly analysis budget covers them; per analysis
  the calls are bounded by configuration: SINGLE `(1 + max-repairs) * (1 + max-revisions) + (1 + max-revisions)`,
  MULTI the same plus three specialists. Usage is summed across agents and revisions and survives a resume.
- **Resume keeps the mode the run started in**, read from the checkpoint, even if the configured mode changed.
- **Default: SINGLE**, from the measurement below. MULTI stays available.

## Measurement (live, gpt-5.4-mini, 6 scenarios x 3 runs per mode, after the fixes below)

| | SINGLE | MULTI |
|---|---|---|
| Expected direction | 18/18 | 18/18 |
| Model calls per case (avg) | 2.7 | 4.9 |
| Input / output tokens (avg) | 16,965 / 1,052 | 17,971 / 2,526 |
| Latency avg / max | 8.8 s / 15.5 s | 12.9 s / 18.2 s |
| Reviewer approved first time / in the end | 11 / 15 of 18 | 12 / 17 of 18 |

Parallel specialists do not pay for the extra sequential writer step on these cases. MULTI would be worth
re-measuring when tool calls are slow (real core banking over MCP), when specialists get different models (phase 5
routing), or on the larger evaluation set of phase 7.

## What the measurement found on the way

- **A reviewer without the author's evidence is harmful.** First run: it saw only the tool call names, flagged
  supported claims (takeover signals, profile facts, baselines) as unsupported and told the writer to delete them, and
  took a playbook example (Malaysia) for a fact of the case. Final verdict "rejected" in 4/6 single-agent cases.
  Passing the exact evidence and limiting it to material problems (at most three, citing the contradicting
  evidence, approve when unsure) fixed most of it.
- **The reviewer catches real mistakes.** It sent back a first draft that cleared an authorised-push-payment transfer
  because no takeover signals were found; the revision was NEEDS_REVIEW 71.
- **Mixed playbook guidance gets misapplied.** The reviewer applied the LARGE_AMOUNT transfer rule ("at least
  NEEDS_REVIEW") to a card payment at a familiar merchant and pushed a correct LIKELY_LEGITIMATE to NEEDS_REVIEW.
  The playbook now has separate card and transfer sections; the honest outlier stayed LEGITIMATE in 6/6 runs after.
- **Still open:** the reviewer raises immaterial objections in a minority of cases (3/18 single, 1/18 multi end
  "rejected"), e.g. confusing the analyst's re-analysis note with CRM staff notes.

## Consequences

- A real multi-agent graph with planning, parallel specialists, bounded review and node-level resume, kept behind a
  flag because the data does not justify it as the default yet.
- Every analysis records its mode and reviewer verdict: the comparison can be repeated from stored data.
- Packaged-jar check: MULTI, `kill -9` after the specialists' checkpoint, restart: resumed after the parallel node,
  WireMock saw no new requests for the customer (specialists not re-run), the stored analysis counts the specialists'
  three calls carried over in the checkpoint.
