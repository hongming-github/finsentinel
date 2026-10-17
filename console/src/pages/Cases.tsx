import { useQuery } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router'
import { api } from '../api'
import { ago, label, short, when } from '../format'
import { useSession } from '../session'
import { STAGES, type CaseStage } from '../types'
import { Badge, Card, Loading, Problem, SeverityBadge, StageBadge } from '../ui'

const LIMITS = [50, 200]

export function Cases() {
  const session = useSession()
  const [params, setParams] = useSearchParams()
  const stage = (STAGES as string[]).includes(params.get('stage') ?? '') ? (params.get('stage') as CaseStage) : null
  const limit = LIMITS.includes(Number(params.get('limit'))) ? Number(params.get('limit')) : LIMITS[0]
  const cases = useQuery({
    queryKey: ['cases', session.tenant, stage, limit],
    queryFn: () => api.cases(session.tenant, stage, limit),
    refetchInterval: 30_000,
  })
  const set = (key: string, value: string | null) => {
    const next = new URLSearchParams(params)
    if (value == null) next.delete(key)
    else next.set(key, value)
    setParams(next)
  }

  return (
    <>
      <div className="page-head">
        <h1>Cases</h1>
        <span className="muted">highest priority first, then most recently updated</span>
      </div>
      <Card
        title={
          <div className="tabs">
            <button className={stage == null ? 'tab active' : 'tab'} onClick={() => set('stage', null)}>all</button>
            {STAGES.map((s) => (
              <button key={s} className={stage === s ? 'tab active' : 'tab'} onClick={() => set('stage', s)}>{label(s)}</button>
            ))}
          </div>
        }
        actions={
          <select value={limit} onChange={(e) => set('limit', e.target.value)} aria-label="page size">
            {LIMITS.map((l) => <option key={l} value={l}>{l} rows</option>)}
          </select>
        }
      >
        {cases.error ? <Problem error={cases.error} /> : !cases.data ? <Loading /> : cases.data.length === 0 ? (
          <div className="empty">No cases{stage ? ` in ${label(stage)}` : ''}.</div>
        ) : (
          <table className="table">
            <thead>
              <tr>
                <th>case</th><th>priority</th><th>stage</th><th>account</th><th className="num">run</th>
                <th className="num">version</th><th>opened</th><th>updated</th><th>decision</th>
              </tr>
            </thead>
            <tbody>
              {cases.data.map((c) => (
                <tr key={c.caseId}>
                  <td><Link to={`/cases/${c.caseId}`} className="mono">{short(c.caseId)}</Link></td>
                  <td><SeverityBadge severity={c.priority} /></td>
                  <td><StageBadge stage={c.stage} />{c.attempts > 0 && <span className="muted small"> · attempt {c.attempts}</span>}</td>
                  <td className="mono">{c.accountId}</td>
                  <td className="num">{c.analysisRun}</td>
                  <td className="num">{c.version}</td>
                  <td className="nowrap">{when(c.openedAt)}</td>
                  <td className="nowrap" title={when(c.updatedAt)}>{ago(c.updatedAt)}</td>
                  <td>{c.decision ? <Badge tone="muted">{label(c.decision)} · {c.decidedBy}</Badge> : ''}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
    </>
  )
}
