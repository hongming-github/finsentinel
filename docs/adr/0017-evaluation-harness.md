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
  expert accepts (dispositions, risk range, required and forbidden tools, regulations from named sets of relevant
  passages, one set per regulatory question, planted instructions flagged, missing checks reported, text that must not appear) and carries the expert's
  view for the judge.
- **The dataset is validated before anything runs** (`GoldenDataset.problems`): ids, transaction refs, durations,
  systems, tool names (read from the tools class, so a renamed tool fails), chunk ids and sets. A mistake in the data
  costs nothing.
- **Deterministic grading** (`CaseGrader`): every check is a fact about the stored result, a run passes when all
  pass. Metrics: pass rate (overall, per case, per category), tool-selection accuracy (required tools called), and
  regulation relevance (a cited passage in each of the case's relevant sets; retrieval recall itself stays with
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

## Leniency baseline (2026-10-18)

`LenientAgentBaselineTest` (free, every build) grades an agent that never judges: NEEDS_REVIEW at one fixed risk
score for every case, with everything that needs no judgement handed to it (every required tool called, a relevant
passage cited wherever regulations are required, planted instructions flagged by the platform). The test fails if the
gate would pass it at any fixed score.

| Fixed score | Plain summary | With "some checks could not be completed" boilerplate |
|---|---|---|
| 40, 50 or 59 | 5/17 | 6/17 |
| 60, 70 or 80 | 9/17 | 11/17 |

The gate turns it down every time: at best 65% against 85%, and two critical cases (`takeover-mule-payee`,
`mule-payee-no-takeover`) are the only ones that demand LIKELY_FRAUD. What it shows about the dataset:

- **The risk score at 60 does nearly all the discriminating; the disposition almost none.** 15 of 17 cases accept
  NEEDS_REVIEW, so an agent that never commits but puts its score on the right side of 60 would pass 13/17 (15/17
  with the boilerplate). A report that hedges every case to an analyst is graded almost like one that decides.
- **`injection-payee-name` passes at every score**: it bounds neither the score nor demands LIKELY_FRAUD.
  `bank-without-screening` likewise once the boilerplate is added.
- **"Missing checks reported" is a regex**: one boilerplate sentence on every report passes `banking-system-down` and
  `bank-without-screening`, whatever was actually unavailable.

Changes to the expectations are for the dataset's owner to decide (one case at a time, each recorded here); the
baseline stays as the guard that a passing evaluation cannot be reached without judgement.

## Review by the dataset owner (2026-10-27)

The owner reviewed all 17 cases (13 accepted as they were). The changes, each a tightening:

- **NEEDS_REVIEW no longer accepted where the evidence settles the case.** `outlier-at-home`, `shopping-trip-burst`,
  `travel-notice-matches`: LIKELY_LEGITIMATE only, risk at most 40 (the validator's own bound for that disposition);
  a familiar merchant, a shopping trip or a travel notice that fits is not work for an analyst (at most a notification
  to the customer). `pii-in-staff-note` and `geo-impossible-travel`: LIKELY_FRAUD only; the travel verdict is
  IMPOSSIBLE, a lower bound (ADR 0021), so there is nothing left to review. The two share their transactions, so they
  move together. Their expert views now say the same.
- **Regulation sets, one per question.** The single 23-passage set let any one cited passage pass, including one on a
  telco's liability that answers neither question. Checked against the MAS PDFs: `str-reporting` (Notice 626 14.2,
  14.3); `loss-takeover` (cooling-off and alert duties SRF 4.2.1, 4.2.2, 4.2.5, EUPG 4.7, 4.21; SRF loss sections 6.1,
  6.2, 6.3, 6.7, 6.8, 7.13; EUPG 5.2, 5.4-5.6) for `takeover-mule-payee` and `injection-fake-system-message`;
  `loss-customer-transfer` (EUPG 5.2-5.6) for the four transfers the customer most likely made themselves. The SRF
  covers only "seemingly authorised transactions" (a scammer impersonates an entity and the customer types credentials
  into a fake site); a payment the customer knowingly made is an authorised transaction (EUPG 5.3), outside both the
  SRF's loss sharing and the EUPG's liability rules. Dropped: SRF 6.4, 6.5, 7.14 (telco liability, no SMS in any
  case), SRF 4.2.4 and EUPG 4.14 (the kill switch), EUPG 3.14 (the customer's reporting duty). A case now needs a
  cited passage from each of its sets (`CaseGrader`, `regulations relevant`).
- **Leniency baseline after the change** (`LenientAgentBaselineTest`): 2/17 at scores 40-59 and 7/17 at 60-80 with
  the plain summary (was 5 and 9), 3 and 9 with the boilerplate (was 6 and 11). Seven cases now demand one
  disposition (was two).

**Evaluation on the tightened set** (gpt-6-luna, SINGLE, 17 x 3, $0.058):
[`docs/eval/2026-10-27-tightened-expectations.md`](../eval/2026-10-27-tightened-expectations.md). Pass rate 73%
(37/51), down from 100% on the old expectations; the gate fails. Every failure is one of two findings:

- **`outlier-at-home` 1/3**: NEEDS_REVIEW twice (risk 45). The agent hedges on a one-off large card payment at a
  familiar merchant; the old expectation hid it. The other false positives held (3/3 each).
- **Customer-made transfers 0/12** (`app-scam-new-payee`, `injection-payee-name`, `mule-payee-no-takeover`,
  `bank-without-screening`): every run cited both STR passages and then SRF 6.2/6.7 for the loss, never EUPG 5.3. The
  agent does what our LARGE_AMOUNT playbook says: "search who bears the loss (Shared Responsibility Framework)", for
  every suspicious transfer. The playbook, not the model, sends an authorised push payment to the wrong framework.
  Dispositions on these cases were all acceptable.

The owner chose to fix the playbook. LARGE_AMOUNT's regulation line now always asks about a report to STRO and
splits the loss question: no takeover signals, whether a payment the customer consented to counts as unauthorised
(EUPG); takeover signals, the bank's cooling-off and alert duties and who bears the loss under the SRF. The eight
LARGE_AMOUNT cases x 3 after each step (customer-made transfers / takeover cases / `outlier-at-home`):

| Step | Customer-made (12) | Takeover (6) | outlier (3) | Cost |
|---|---|---|---|---|
| Old playbook (from the full run) | 0 | 6 | 1 | |
| Split, takeover line naming "unauthorised transaction" | 11 | 0 | 0 | $0.040 |
| Takeover line naming the SRF | 9 | 1 | 0 | $0.037 |
| Same, chunker fixed (ADR 0007) | 10 | 3 | 0 | $0.038 |

Report of the last row: [`docs/eval/2026-10-27-large-amount-playbook.md`](../eval/2026-10-27-large-amount-playbook.md).
With definitions now chunks of their own, agents cite them: the remaining failures cite
`eupg-2024:2.1:unauthorised-transaction` or `srf-2024:2.1:seemingly-authorised-transaction` (and EUPG 5.3 on takeover
cases) where the sets expect loss passages. Search queries are not in the report, so which search returned them is not
known. `outlier-at-home` fails on its own: NEEDS_REVIEW or once LIKELY_FRAUD 78, a card-section problem the regulation
work does not touch.

**Loosened, with the owner's agreement:** `loss-customer-transfer` also accepts the two definitions,
`eupg-2024:2.1:unauthorised-transaction` (a payment initiated without the account user's knowledge and consent) and
`srf-2024:2.1:seemingly-authorised-transaction` (the SRF's phishing scope). For a payment the customer made, they
answer the loss question the way EUPG 5.3 does: it is not unauthorised, so neither framework's loss rules cover it.
They were missing from the set only because the chunker had hidden them inside other terms' chunks. `loss-takeover`
does not accept them: there the question is whether the bank met its duties and who bears the loss, which a
definition does not answer. Re-graded from the last run's records (no new run): customer-made 12/12, takeover 3/6,
`outlier-at-home` 0/3, 18/24 in all; the gate still fails on the takeover cases and `outlier-at-home`, both open.

**Same three failing cases on `gpt-6.1-terra` (2026-10-28)**, investigator only (reviewer stays Luna), same playbook
and chunks, 3 x 3: 9/9. `outlier-at-home` LIKELY_LEGITIMATE 8-15 each time; both takeover cases LIKELY_FRAUD 90-98
citing a `loss-takeover` passage. $0.0102 per run against Luna's $0.0010 on the same cases (10x, matching the price
ratio, which also shows Terra ran: cost is computed from the model the provider reports); p50 latency 16.6 s against
12.0 s. Terra ($1 / $0.05 cached / $1.25 cache write / $5) takes function tools on Chat Completions at reasoning
effort `none`, alone and together with a JSON schema (one probe call each). Report:
[`docs/eval/2026-10-28-terra-investigator.md`](../eval/2026-10-28-terra-investigator.md). Nine runs on the three
hardest cases are a signal, not a comparison (Wilson 95% for 9/9: 70%-100%): moving the investigator needs the full
set on both models with cost, as ADR 0011 and 0023 did.

**Full set on both, final dataset and playbook (2026-10-28)**, 17 x 3, SINGLE, only the investigator differs:

| | Luna | Terra |
|---|---|---|
| Pass rate | 86% (44/51), gate fails | **96% (49/51), gate passes** |
| Regulation relevance | 78% | 100% |
| Cost per run | $0.0013 | $0.0078 (6x) |
| Latency p50 / p95 | 12.6 s / 30.3 s | 10.7 s / 24.0 s |
| Prompt tokens, 51 runs | 952k | 636k |

Reports: [`2026-10-28-full-luna.md`](../eval/2026-10-28-full-luna.md),
[`2026-10-28-full-terra.md`](../eval/2026-10-28-full-terra.md). Terra is ahead on three cases and behind on none:
both takeover cases (3/3 each against 1/3; Luna cites definitions or EUPG 5.3 where the loss passages are expected)
and `outlier-at-home` (2/3 against 1/3). Both still hedge once on `outlier-at-home` and `shopping-trip-burst`
(NEEDS_REVIEW where LIKELY_LEGITIMATE is expected). Fisher one-sided p = 0.08 on 49/51 vs 44/51, with runs clustered
by case, so the overall gap is not significant on its own; the case-level pattern (the critical takeover cases) is
what separates them. Terra used a third fewer prompt tokens and was faster. Whether to move the investigator is the
owner's decision; until then the default stays Luna.

**Run records keep transcripts (2026-10-28).** Each run now stores what the analysis produced (`Transcript`): the
report, every tool call with the model's arguments and the pseudonymised result it saw, the passages each search
returned, repair issues and the reviewer's verdict, passed runs included. A second full run on Terra, the first with
transcripts: 50/51 (`outlier-at-home` once NEEDS_REVIEW 50), $0.389
([`2026-10-28-terra-transcripts.md`](../eval/2026-10-28-terra-transcripts.md)); with the comparison run, Terra is
99/102. Its records name the model of each run's last call, which on reviewed reports is the reviewer's (Luna); the
investigator was Terra throughout (those runs cost $0.006-0.018, a Luna-only run about $0.001). Later records also
carry `costByModel`, which says it directly.

**After the last chunker fixes (2026-10-31).** Roman-numeral sub-headings, Notice 626's section 11A and the annexes
became chunks of their own after the two runs above (ADR 0007), so the corpus the agent searches changed. A third full
run on Terra (Spring AI 2.0.1, before the 2.0.2 upgrade): 50/51, gate passes, $0.430 ($0.0084 a run), p50 11.6 s /
p95 26.6 s, tool selection and regulation relevance 100%
([`2026-10-31-full-terra.md`](../eval/2026-10-31-full-terra.md)). The one failure is again `outlier-at-home`
(NEEDS_REVIEW, risk 45); `regular-rent-payee` hedged once too, which its expectation allows. Terra's three full runs:
149/153.

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
