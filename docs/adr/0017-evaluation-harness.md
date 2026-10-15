# 0017. Evaluation: a golden dataset, pass rates over repeated runs, deterministic grading, a judge, and a gate

- Status: accepted (phase 7). Built and tested without OpenAI; exercised end to end on Groq; baseline on the
  production models made 2026-10-15 (below).
- Date: 2026-10-10

## Context

Until now quality was measured by `LiveInvestigationTest`: eight scenarios in Java code, each asserting one direction
("not called legitimate"), run three times after a prompt change and read by eye. That found real problems (the
payee-name injection, the writer's invented ids) but it has no pass rates, no metrics beyond the verdict, nothing a
build can fail on, and adding a case means writing Java. Several decisions are waiting on a better measurement:
model routing (ADR 0011), SINGLE vs MULTI (ADR 0010), the reviewer on gpt-5.5 (config C of phase 5).

## Decision

- **The golden dataset is data** (`src/test/resources/eval/golden-cases.json`): 16 cases (17 since ADR 0021 added `travel-notice-too-fast`) in eight categories (card
  fraud, false positives, transfer scams, account takeover, injection, resilience, tenant permissions, privacy), the
  eight live scenarios plus eight harder ones chosen to separate a careful investigation from a lucky one: card testing
  vs a shopping trip at familiar merchants, a travel notice that matches vs one for another country, a recurring rent
  transfer vs a first-time one, a mule payee without takeover signals, a bank without the screening vendor, personal
  data in a staff note. Times are durations relative to the run, so a case never goes stale. Each case says what an
  expert accepts (dispositions, risk range, required and forbidden tools, regulations from a named set of relevant
  passages, planted instructions flagged, missing checks reported, text that must not appear) and carries the expert's
  view for the judge.
- **The dataset is validated before anything runs** (`GoldenDataset.problems`): ids, transaction refs, durations,
  systems, tool names (read from the tools class, so a renamed tool fails), chunk ids and sets. A mistake in the data
  costs nothing.
- **Deterministic grading** (`CaseGrader`): every check is a fact about the stored result, a run passes when all
  pass. Metrics: pass rate (overall, per case, per category), tool-selection accuracy (required tools called), and
  regulation relevance (a cited passage is in the case's relevant set; retrieval recall itself stays with
  `RetrievalEvalTest`, ADR 0007).
- **Repeated runs**: each case runs `eval.repeats` times (default 3) per mode; per-case pass rates make variance
  visible instead of hiding it in one pass/fail.
- **LLM-as-judge, optional** (`EvalJudge`, `eval/judge-system.md`): scores evidence support, conclusion and actions
  from 1 to 5 and quotes unsupported claims, given the case, the expert's view, the report and the pseudonymised
  evidence the investigator saw. Through `LlmGateway`, so priced and inside the budget. Not calibrated against human
  labels, so it is reported but not gated (`judgeMeanScore: null`) until it is.
- **A budget the run cannot pass**: before each analysis the runner checks spent plus the dearest run so far against
  `eval.maxCostUsd` (default $1.00) and skips the rest; repeats are the outer loop, so a cut-short run still covers
  every case. Skipped runs fail the gate: an evaluation that did not run a case says nothing about it.
- **The gate** (`EvalGate`, `eval/thresholds.json`): overall pass rate, each critical case, every other case, tool
  selection, regulation relevance, mean cost per run. Thresholds are provisional, from the phase 3 to 6 live runs, to
  be recalibrated from the first baseline.
- **Free dry run in every build** (`GoldenEvalDryRunTest`): every case through the same harness with the stub
  agents: every scenario builds, claims and grades, and the gate fails on the stub's answers (fails closed).
- **CI** (`.github/workflows`): `ci.yml` builds and runs every test that calls no model on each push; `eval.yml`
  runs the evaluation only when started by hand, with inputs for repeats, cases, modes, budget and judge, and uploads
  the report. No schedule, because a schedule spends money unattended.

## First real runs: Groq, OpenAI blocked (2026-10-10)

OpenAI was off limits for this step, so the harness was exercised on Groq (`groq:openai/gpt-oss-120b` for every role
and the judge, gpt-oss-20b for reranking) with OpenAI unreachable (`OPENAI_API_KEY` unset, base URLs pointed at
`localhost:1`, so query embeddings failed and search fell back to keywords). Not a baseline: different models, no
vector search.

- The first run failed every analysis and showed two platform bugs in the Groq fallback of ADR 0012 (a schema
  keyword Groq rejects, and gpt-oss calling a non-existent `json` tool); both fixed in the gateway, see ADR 0012.
- It also showed a harness bug: a failed analysis reported $0, so the budget would have under-counted. Failed
  analyses are now charged at the dearest run so far and counted in the report.
- After the fixes, three cases: `outlier-at-home` PASSED (LIKELY_LEGITIMATE 20), `card-testing-burst` PASSED
  (LIKELY_FRAUD 85), `travel-notice-matches` failed when the Groq account hit its 200k tokens/day limit. The judge
  scored both passing reports 5.0 with no unsupported claims; the report and the gate worked as designed (the gate
  failed on the 2 of 3 pass rate). Groq spend for all probes about $0.02; the 8,000 tokens/minute limit made one
  analysis take 1.5 to 6 minutes.

## Baseline (2026-10-15)

Production models (`gpt-5.4-mini` for every role), SINGLE mode, 16 cases x 3 repeats, no judge. Report:
[`docs/eval/2026-10-15-baseline.md`](../eval/2026-10-15-baseline.md) (and `.json`).

- Pass rate 92% (44/48); tool selection 98%; regulation relevance 100%; cost $0.501 ($0.0104 per run; one failed
  analysis unpriced; estimated $0.60-0.90); latency p50 7.0 s, p95 18.3 s; 7.6 minutes in all.
- Every critical case 3/3 (card fraud, transfer scams, takeover, all three injections). The payee-name injection was
  never cleared: NEEDS_REVIEW twice, LIKELY_FRAUD once (the guard of ADR 0016).
- The gate failed, correctly, on one case: `travel-notice-matches` 0/3, LIKELY_FRAUD 87-90 each time although the
  customer's travel notice covers Japan and seven hours fits the flight. Suspected cause, in our GEO_JUMP playbook:
  "if the customer is abroad, the payment at home is the suspicious one" ignores order and timing; the notice starts
  the day before, so the Changi payment before the flight reads as "at home while abroad". Not confirmed yet
  (reports are not kept in the run records); the fix is to be measured on the GEO_JUMP cases.
- Resolved the same day, in our GEO_JUMP playbook, not the model. A failed run now records the report's summary and
  findings (pseudonymised), which showed the reasoning: "7 hours apart; that timing is too short", the GEO_JUMP rule
  "strongly supported", a new Japanese merchant and no earlier Japan activity counted as strengthening. Three
  causes, all in the playbook: its first line said the rule fires on payments "closer together in time than travel
  allows" (our rule is only a time window, it knows no distances), it gave no flight times beyond Malaysia and
  Europe, and it listed a new foreign merchant as strengthening even when a travel notice covers the country; plus
  the travel-notice line that ignored order and timing. Re-measured on the six GEO_JUMP cases x 3: the notice line
  alone took the case to 1/3 ($0.083); all four changes to 3/3 (LIKELY_LEGITIMATE 10-15) with every other GEO_JUMP
  case still 3/3, impossible travel and the wrong-country notice still LIKELY_FRAUD 96-97 ($0.115). Report:
  [`docs/eval/2026-10-15-geo-jump-fix.md`](../eval/2026-10-15-geo-jump-fix.md). The flight times are general domain
  knowledge, but they were added because of this case; a deterministic feasibility check (country pair to minimum
  travel time) would not depend on the model's geography at all.
- `shopping-trip-burst` 2/3: one report cited a transaction id no tool had returned; the repair did not fix it, so
  the report was rejected and not stored (ADR 0006 working as designed; the cost is a failed analysis).
- Thresholds are unchanged: the overall rate (92% against 85%) and every other check passed, and the one failure is
  a real defect, not a threshold set too high.

## Not done yet

- A golden case for the other branch of the travel-notice rule (abroad first, then a payment at home during the
  trip), and a deterministic travel-feasibility check instead of flight times in the playbook.
- MULTI doubles the baseline's cost; the judge adds about $0.003 per run; in CI the corpus is embedded again (about
  $0.01, no local embedding cache).
- The decisions it unblocks: the reviewer on gpt-5.5 (`-Dfinsentinel.llm.models.reviewer=gpt-5.5`), MULTI vs SINGLE,
  escalation, each as two evaluation runs compared on pass rate and cost.
- Judge calibration: label a sample of reports by hand, compare with the judge's scores, then set `judgeMeanScore`.
- `LiveInvestigationTest` overlaps the dataset; once the baseline exists it can shrink to a quick smoke test.

## Consequences

- A new case is a JSON object, checked for free on the next build.
- Quality claims in ADRs can cite pass rates with run counts instead of "18/18 in three runs".
- Grading is only as good as the expectations: a check like "missing checks reported" matches the model's wording
  with a pattern and can miss an unusual phrasing; the judge and the failure list in the report are where that shows.
