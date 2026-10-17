import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { api } from '../api'
import { isAnalyst } from '../claims'
import { count, duration, label, pct, short, usd, when } from '../format'
import { caseWindow, lokiCaseLogs, lokiTraceLogs, pipelineDashboard, tempoTrace } from '../links'
import { useSession } from '../session'
import type { CaseAnalysis, CaseDetail, CaseEvent, Decision } from '../types'
import { Badge, Card, DispositionBadge, External, Loading, Problem, SeverityBadge, Stat, StageBadge } from '../ui'

export function CaseDetailPage() {
  const session = useSession()
  const { caseId = '' } = useParams()
  const query = useQuery({
    queryKey: ['case', session.tenant, caseId],
    queryFn: () => api.caseDetail(session.tenant, caseId),
    // Every API read is an audit entry (ADR 0016): refresh only while the agents are still at work.
    refetchInterval: (q) => (q.state.data?.detail.fraudCase.stage === 'ANALYSIS' ? 5_000 : false),
  })
  const [run, setRun] = useState<number | null>(null)
  // Here, not in the decision panel: the refetch after a decision closes the case, and the panel goes with it.
  const [outcome, setOutcome] = useState<Outcome | null>(null)

  if (query.error) return <Problem error={query.error} />
  if (!query.data) return <Loading />
  const { detail, etag } = query.data
  const c = detail.fraudCase
  const analyses = [...detail.analyses].sort((a, b) => b.run - a.run)
  const shown = analyses.find((a) => a.run === run) ?? analyses[0]
  const lastAt = detail.events.at(-1)?.at ?? c.updatedAt
  const window = caseWindow(c.openedAt, lastAt)
  // The current run started when the case last entered ANALYSIS: its trace and log lines lie in that window.
  const runStart = detail.events.findLast((e) => e.toStage === 'ANALYSIS')?.at ?? c.openedAt
  const runWindow = caseWindow(runStart, lastAt)
  const untrusted = detail.events.some((e) => e.detail?.untrustedInstructions === true)

  return (
    <>
      <div className="page-head">
        <div>
          <div className="crumbs"><Link to="/cases">Cases</Link> / <span className="mono">{short(c.caseId)}</span></div>
          <h1 className="case-title">
            Case <span className="mono">{c.caseId}</span>
          </h1>
          <div className="row gap">
            <StageBadge stage={c.stage} />
            <SeverityBadge severity={c.priority} />
            <span className="muted">account <span className="mono">{c.accountId}</span> · customer <span className="mono">{c.customerId}</span></span>
            <span className="muted" title="Optimistic-locking version: the ETag a decision must send back in If-Match">version {c.version} · ETag {etag}</span>
            {untrusted && (
              <Badge tone="bad" title="Case data contained instructions aimed at the agents (ADR 0016): the case can never be cleared as legitimate">
                untrusted instructions in case data
              </Badge>
            )}
          </div>
        </div>
      </div>

      <Card title="Observability" className="links-card">
        <div className="row gap wrap">
          {detail.traces.currentRun ? (
            <>
              <External href={tempoTrace(detail.traces.currentRun, runWindow)}>Trace of the current run (Tempo)</External>
              <External href={lokiTraceLogs(detail.traces.currentRun, runWindow)}>Logs of that trace (Loki)</External>
            </>
          ) : <span className="muted">The current run was not traced.</span>}
          <External href={lokiCaseLogs(c.caseId, window)}>Every log line naming this case (Loki)</External>
          <External href={pipelineDashboard(window)}>Pipeline dashboard over the case's life</External>
        </div>
        {detail.traces.alerts.length > 0 && (
          <div className="trace-list">
            <span className="muted small">Traces the alerts arrived in:</span>
            {detail.traces.alerts.map((t) => (
              <a key={t} className="mono small" href={tempoTrace(t, window)} target="_blank" rel="noreferrer">{t.slice(0, 12)}…</a>
            ))}
          </div>
        )}
      </Card>

      {isAnalyst(session) && (c.stage === 'AWAITING_DECISION' || c.stage === 'FAILED') && etag && (
        <DecisionPanel tenant={session.tenant} caseId={c.caseId} etag={etag} onOutcome={setOutcome} />
      )}
      {outcome && <div className={`notice ${outcome.tone}`}>{outcome.text}</div>}
      {c.decision && (
        <div className="notice">
          Decided <b>{label(c.decision)}</b> by {c.decidedBy} {c.closedAt ? `on ${when(c.closedAt)}` : ''}
          {c.analystNote ? <> — “{c.analystNote}”</> : null}
        </div>
      )}

      {shown ? (
        <>
          {analyses.length > 1 && (
            <div className="tabs runs">
              <span className="muted small">analysis run</span>
              {analyses.map((a) => (
                <button key={a.run} className={a.run === shown.run ? 'tab active' : 'tab'} onClick={() => setRun(a.run)}>
                  {a.run}
                </button>
              ))}
            </div>
          )}
          <Analysis analysis={shown} current={shown.run === c.analysisRun} />
        </>
      ) : (
        <div className="notice">{c.stage === 'FAILED' ? 'The analysis gave up; no report was stored (only validated reports are).' : 'No analysis yet: the case is waiting in the queue.'}</div>
      )}

      <Alerts detail={detail} />
      <Events events={detail.events} />
    </>
  )
}

function Analysis({ analysis: a, current }: { analysis: CaseAnalysis; current: boolean }) {
  const r = a.report
  const review = a.review
  const cachedShare = a.inputTokens ? (a.cachedInputTokens ?? 0) / a.inputTokens : null
  return (
    <>
      <div className="grid-report">
        <Card title={<>Report <span className="muted small">run {a.run}{current ? '' : ' (superseded)'} · {when(a.createdAt)}</span></>}>
          <div className="row gap">
            <DispositionBadge disposition={r.disposition} />
            <RiskMeter score={r.riskScore} />
          </div>
          <p className="summary">{r.summary}</p>
          <h3>Findings</h3>
          <ul className="findings">
            {r.findings.map((f, i) => (
              <li key={i}>
                {f.statement}
                <div className="chips">{f.evidenceTxnIds.map((t) => <code key={t} className="chip" title={t}>{short(t)}</code>)}</div>
              </li>
            ))}
          </ul>
          {r.recommendedActions.length > 0 && (
            <>
              <h3>Recommended actions</h3>
              <ol>{r.recommendedActions.map((x, i) => <li key={i}>{x}</li>)}</ol>
            </>
          )}
          {r.regulatoryNotes.length > 0 && (
            <>
              <h3>Regulatory notes</h3>
              <ul className="findings">
                {r.regulatoryNotes.map((n, i) => (
                  <li key={i}>
                    {n.statement}
                    <div className="chips">{n.sourceChunkIds.map((id) => <code key={id} className="chip">{id}</code>)}</div>
                  </li>
                ))}
              </ul>
            </>
          )}
          {a.repairedIssues.length > 0 && (
            <div className="notice small">Repaired before it was stored: {a.repairedIssues.join('; ')}</div>
          )}
        </Card>

        <div className="stack">
          <Card title="Review">
            {!review ? <span className="muted">No review recorded.</span> : review.skippedBecause ? (
              <><Badge tone="muted">skipped</Badge> <span className="small">{review.skippedBecause}</span>
                <p className="muted small">Risk-based review: a skipped review is recorded as skipped, never as an approval.</p></>
            ) : review.approved ? <Badge tone="good">approved by the reviewer</Badge> : (
              <><Badge tone="warn">sent back</Badge><ul>{review.issues.map((x, i) => <li key={i}>{x}</li>)}</ul></>
            )}
          </Card>
          <Card title="Cost and tokens">
            <div className="stats compact">
              <Stat label="cost" value={usd(a.costUsd)} />
              <Stat label="model calls" value={a.llmCalls} />
              <Stat label="latency" value={duration(a.latencyMs)} />
              <Stat label="mode" value={a.mode.toLowerCase()} />
            </div>
            <table className="kv">
              <tbody>
                <tr><th>model</th><td className="mono">{a.model ?? '—'}</td></tr>
                <tr><th>input tokens</th><td>{count(a.inputTokens)}</td></tr>
                <tr><th>from prompt cache</th><td>{count(a.cachedInputTokens)} ({pct(cachedShare)})</td></tr>
                <tr><th>written to cache</th><td>{count(a.cacheWriteTokens)}</td></tr>
                <tr><th>output tokens</th><td>{count(a.outputTokens)}</td></tr>
                {review?.usage?.model && <tr><th>reviewer</th><td className="mono">{review.usage.model} · {usd(review.usage.costUsd)}</td></tr>}
              </tbody>
            </table>
          </Card>
        </div>
      </div>

      <Card title={<>Tool calls <span className="muted small">the evidence the agent gathered, as recorded</span></>}>
        <table className="table">
          <thead><tr><th>tool</th><th>arguments</th><th className="num">results</th><th className="num">time</th><th>cache</th></tr></thead>
          <tbody>
            {a.toolCalls.map((t, i) => (
              <tr key={i}>
                <td className="mono">{t.tool}</td>
                <td className="reason">{t.arguments || <span className="muted">case-scoped, no arguments</span>}</td>
                <td className="num">{t.resultCount}</td>
                <td className="num">{duration(t.durationMs)}</td>
                <td>{t.cacheHit == null ? '' : t.cacheHit ? <Badge tone="good">hit</Badge> : <Badge tone="muted">miss</Badge>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>
    </>
  )
}

function RiskMeter({ score }: { score: number }) {
  const tone = score >= 70 ? 'bad' : score >= 40 ? 'warn' : 'good'
  return (
    <div className="risk" title="0 = certainly legitimate, 100 = certainly fraud">
      <span className="small muted">risk</span>
      <div className="meter"><div className={`fill ${tone}`} style={{ width: `${score}%` }} /></div>
      <b>{score}</b>
    </div>
  )
}

function Alerts({ detail }: { detail: CaseDetail }) {
  return (
    <Card title={`Alerts (${detail.alerts.length})`}>
      <table className="table">
        <thead><tr><th>rule</th><th>severity</th><th>reason</th><th className="num">txns</th><th>event time</th><th>detected after</th><th>to platform</th></tr></thead>
        <tbody>
          {detail.alerts.map(({ alert: a, receivedAt }) => (
            <tr key={a.alertId}>
              <td>{a.rule}</td>
              <td><SeverityBadge severity={a.severity} /></td>
              <td className="reason">{a.reason}</td>
              <td className="num">{a.txnIds.length}</td>
              <td className="nowrap">{when(a.eventTime)}</td>
              <td className="num">{duration(Math.max(0, Date.parse(a.detectedAt) - Date.parse(a.eventTime)))}</td>
              <td className="num">{duration(Math.max(0, Date.parse(receivedAt) - Date.parse(a.detectedAt)))}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </Card>
  )
}

function Events({ events }: { events: CaseEvent[] }) {
  return (
    <Card title={<>Case events <span className="muted small">every state change, written in the same transaction</span></>}>
      <ol className="timeline">
        {events.map((e) => (
          <li key={e.eventId}>
            <div className="when nowrap">{when(e.at)}</div>
            <div>
              <b>{label(e.type)}</b>
              {e.toStage && <span className="muted"> {e.fromStage ? `${label(e.fromStage)} → ` : ''}{label(e.toStage)}</span>}
              <span className="muted small"> · {e.actor}</span>
              {e.detail && Object.keys(e.detail).length > 0 && (
                <div className="detail">
                  {Object.entries(e.detail).map(([k, v]) => (
                    <span key={k} className="kvchip"><span className="muted">{k}</span> {typeof v === 'object' ? JSON.stringify(v) : String(v)}</span>
                  ))}
                </div>
              )}
            </div>
          </li>
        ))}
      </ol>
    </Card>
  )
}

const DECISIONS: { value: Decision; text: string }[] = [
  { value: 'CONFIRMED_FRAUD', text: 'Confirmed fraud' },
  { value: 'FALSE_POSITIVE', text: 'False positive' },
  { value: 'REQUEST_REANALYSIS', text: 'Send back to the agents' },
]

interface Outcome {
  tone: string
  text: string
}

function DecisionPanel({ tenant, caseId, etag, onOutcome }: {
  tenant: string; caseId: string; etag: string; onOutcome: (o: Outcome) => void
}) {
  const client = useQueryClient()
  const [decision, setDecision] = useState<Decision>('FALSE_POSITIVE')
  const [note, setNote] = useState('')
  const decide = useMutation({
    mutationFn: () => api.decide(tenant, caseId, etag, decision, note),
    onSuccess: ({ conflict, fraudCase }) => {
      onOutcome(conflict
        ? { tone: 'warn', text: `Not recorded: the case changed since you loaded it (now version ${fraudCase.version}, ${label(fraudCase.stage)}). Read it again before deciding.` }
        : { tone: 'good', text: `Recorded: ${label(decision)}. The case is now ${label(fraudCase.stage)}, version ${fraudCase.version}.` })
      void client.invalidateQueries({ queryKey: ['case', tenant, caseId] })
      void client.invalidateQueries({ queryKey: ['cases', tenant] })
    },
    onError: (e) => onOutcome({ tone: 'bad', text: e instanceof Error ? e.message : String(e) }),
  })
  const needsNote = decision === 'REQUEST_REANALYSIS' && note.trim() === ''
  return (
    <Card title={<>Decision <span className="muted small">sent with If-Match {etag}: refused if the case changed since you read it</span></>}>
      <div className="decision">
        {DECISIONS.map((d) => (
          <label key={d.value} className="radio">
            <input type="radio" name="decision" checked={decision === d.value} onChange={() => setDecision(d.value)} /> {d.text}
          </label>
        ))}
        <textarea
          placeholder={decision === 'REQUEST_REANALYSIS' ? 'What should the agents look at? (required)' : 'Note (optional)'}
          value={note}
          onChange={(e) => setNote(e.target.value)}
          rows={2}
        />
        <button className="primary" disabled={decide.isPending || needsNote} onClick={() => decide.mutate()}>Record decision</button>
      </div>
    </Card>
  )
}
