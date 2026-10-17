import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { api } from '../api'
import { duration, short, when } from '../format'
import { useSession } from '../session'
import { Card, Loading, Problem, SeverityBadge } from '../ui'

export function Alerts() {
  const session = useSession()
  const [limit, setLimit] = useState(50)
  const alerts = useQuery({
    queryKey: ['alerts', session.tenant, limit],
    queryFn: () => api.alerts(session.tenant, limit),
    refetchInterval: 30_000,
  })
  return (
    <>
      <div className="page-head">
        <h1>Alerts</h1>
        <span className="muted">raised by the Flink job, filed under the account's open case</span>
      </div>
      <Card
        title="Most recent first"
        actions={
          <select value={limit} onChange={(e) => setLimit(Number(e.target.value))} aria-label="page size">
            <option value={50}>50 rows</option>
            <option value={200}>200 rows</option>
          </select>
        }
      >
        {alerts.error ? <Problem error={alerts.error} /> : !alerts.data ? <Loading /> : alerts.data.length === 0 ? (
          <div className="empty">No alerts yet: start the Flink job and some traffic (README "Console").</div>
        ) : (
          <table className="table">
            <thead>
              <tr>
                <th>detected</th><th>rule</th><th>severity</th><th>account</th><th>reason</th><th className="num">txns</th>
                <th className="num">detection</th><th className="num">to platform</th><th>case</th>
              </tr>
            </thead>
            <tbody>
              {alerts.data.map(({ alert: a, caseId, receivedAt }) => (
                <tr key={a.alertId}>
                  <td className="nowrap">{when(a.detectedAt)}</td>
                  <td>{a.rule}</td>
                  <td><SeverityBadge severity={a.severity} /></td>
                  <td className="mono">{a.accountId}</td>
                  <td className="reason">{a.reason}</td>
                  <td className="num">{a.txnIds.length}</td>
                  <td className="num" title="event time of the tipping transaction to the alert">
                    {duration(Math.max(0, Date.parse(a.detectedAt) - Date.parse(a.eventTime)))}
                  </td>
                  <td className="num" title="alert raised to alert stored by the platform">
                    {duration(Math.max(0, Date.parse(receivedAt) - Date.parse(a.detectedAt)))}
                  </td>
                  <td><Link to={`/cases/${caseId}`} className="mono">{short(caseId)}</Link></td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
    </>
  )
}
