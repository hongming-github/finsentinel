# 0006. Agent tools scoped by construction; schema-constrained output, validated and repaired

- Status: accepted
- Date: 2026-10-02

## Context

The investigator agent reads customer data through tools and returns a report that analysts act on. Two risks:
the model reaching data it should not see, and a report that looks right but is not.

## Decision

**Tools.** A new `InvestigationTools` object is built per case by `InvestigationToolsFactory`, with tenant and
account fixed in its fields. No tool has a tenant or account parameter. Spring AI's `ToolContext` could carry
them instead, but it is an untyped `Map<String, Object>` read at runtime; constructor fields cannot be forgotten
and also hold the per-case call log and seen-id set. Look-back and row limits come from configuration and are
clamped in code. Tool results are compact views (`TxnView`): fields that repeat on every row are dropped,
which cut input tokens by about 17%.

**Output.** `CaseReport` is sent as a JSON Schema with OpenAI structured outputs in strict mode, so the shape is
enforced by constrained decoding and the prompt does not describe the format. Strict mode cannot guarantee
content, so `CaseReportValidator` checks score range, disposition/score consistency, non-empty sections, and that
every cited transaction id was returned by a tool. A rejected draft gets up to `report.max-repairs` (default 1)
extra calls that list the problems; if it is still invalid the investigation fails and the queue retries or
marks it FAILED for a human. An invalid report is never stored.

## Consequences

- Cross-customer access is impossible by construction, not by instruction.
- Every repair is a bounded extra model call, counted in `llmCalls` and token totals.
- The repair call re-sends the alert and the rejected report but not earlier tool results, so the model may
  call tools again. Acceptable while repairs are rare; revisit if `repaired_issues` shows otherwise.
- Providers without strict schema support (a future fallback) need prompt-described format plus the same
  validator.
