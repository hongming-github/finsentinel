-- Phase 8C (ADR 0020): W3C trace context (traceparent) kept across the queue, so an analysis continues the trace of
-- whatever started its run. Null when the work was not traced.

-- The trace the alert arrived in (the stream job's detection span); the analysis links to every alert of the case.
alter table alert add column trace_parent text;

-- The trace of whatever started the current analysis run: the alert that opened or reopened the case, or the
-- analyst's request for another analysis. The analysis span is its child.
alter table fraud_case add column trace_parent text;
