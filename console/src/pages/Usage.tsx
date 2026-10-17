import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { api } from '../api'
import { count, duration, pct, usd } from '../format'
import { useSession } from '../session'
import { Card, Loading, Problem, Stat } from '../ui'

const DAYS = [1, 7, 30]

export function Usage() {
  const session = useSession()
  const [days, setDays] = useState(7)
  const usage = useQuery({
    queryKey: ['usage', session.tenant, days],
    queryFn: () => api.usage(session.tenant, days),
  })
  return (
    <>
      <div className="page-head">
        <h1>LLM usage</h1>
        <div className="tabs">
          {DAYS.map((d) => (
            <button key={d} className={d === days ? 'tab active' : 'tab'} onClick={() => setDays(d)}>{d === 1 ? '24 h' : `${d} days`}</button>
          ))}
        </div>
      </div>
      {usage.error ? <Problem error={usage.error} /> : !usage.data ? <Loading /> : <Report report={usage.data} />}
    </>
  )
}

function Report({ report: r }: { report: NonNullable<Awaited<ReturnType<typeof api.usage>>> }) {
  const byModel = Object.entries(r.costByModel).sort((a, b) => b[1] - a[1])
  const top = Math.max(...byModel.map(([, v]) => v), 0)
  const cache = r.regulationSearchCache
  return (
    <>
      <Card title={<>Cost per analysed case <span className="muted small">at list prices (finsentinel.llm.prices)</span></>}>
        <div className="stats">
          <Stat label="analyses" value={count(r.costPerCase.analyses)} />
          <Stat label="total" value={usd(r.costPerCase.total)} />
          <Stat label="average" value={usd(r.costPerCase.average)} />
          <Stat label="p95" value={usd(r.costPerCase.p95)} />
          <Stat label="max" value={usd(r.costPerCase.max)} />
        </div>
      </Card>
      <div className="grid-2">
        <Card title="By model">
          {byModel.length === 0 ? <div className="empty">No model calls in this window.</div> : (
            <div className="bars">
              {byModel.map(([model, cost]) => (
                <div key={model} className="bar-row">
                  <span className="mono small">{model}</span>
                  <div className="bar"><div className="bar-fill" style={{ width: top > 0 ? `${(cost / top) * 100}%` : '2px' }} /></div>
                  <span className="num">{usd(cost)}</span>
                </div>
              ))}
            </div>
          )}
          <p className="muted small">fake-llm costs nothing: in profile load every call goes to it.</p>
        </Card>
        <Card title="Regulation search cache">
          <div className="stats">
            <Stat label="searches" value={count(cache.searches)} />
            <Stat label="hits" value={count(cache.hits)} hint={pct(cache.hitRate)} />
            <Stat label="saved" value={usd(cache.savedUsd)} hint="rerank calls avoided" />
          </div>
        </Card>
      </div>
      <Card title="By analysis mode">
        <table className="table">
          <thead><tr><th>mode</th><th className="num">analyses</th><th className="num">avg cost</th><th className="num">avg latency</th><th className="num">avg model calls</th></tr></thead>
          <tbody>
            {r.byMode.map((m) => (
              <tr key={m.mode}>
                <td>{m.mode.toLowerCase()}</td>
                <td className="num">{count(m.analyses)}</td>
                <td className="num">{usd(m.averageCost)}</td>
                <td className="num">{duration(m.averageLatencyMs)}</td>
                <td className="num">{m.averageCalls.toFixed(1)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>
    </>
  )
}
