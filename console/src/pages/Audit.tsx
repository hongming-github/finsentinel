import { useQuery } from '@tanstack/react-query'
import { api } from '../api'
import { when } from '../format'
import { useSession } from '../session'
import { Badge, Card, Loading, Problem } from '../ui'

export function Audit() {
  const session = useSession()
  const verify = useQuery({ queryKey: ['audit-verify', session.tenant], queryFn: () => api.auditVerify(session.tenant) })
  const entries = useQuery({ queryKey: ['audit', session.tenant], queryFn: () => api.audit(session.tenant, 100) })
  return (
    <>
      <div className="page-head">
        <h1>Audit log</h1>
        <span className="muted">append-only, hash-chained per bank; reading it is recorded too</span>
      </div>
      <Card title="Hash chain">
        {verify.error ? <Problem error={verify.error} /> : !verify.data ? <Loading /> : (
          <div className="row gap">
            {verify.data.intact
              ? <Badge tone="good">intact</Badge>
              : <Badge tone="bad">broken at entry {verify.data.firstBrokenId}</Badge>}
            <span>chain <b>{verify.data.chain}</b>, {verify.data.entries.toLocaleString()} entries recomputed</span>
          </div>
        )}
      </Card>
      <Card title="Latest 100 entries">
        {entries.error ? <Problem error={entries.error} /> : !entries.data ? <Loading /> : (
          <table className="table">
            <thead><tr><th>at</th><th>who</th><th>client</th><th>action</th><th>resource</th><th>outcome</th></tr></thead>
            <tbody>
              {entries.data.map((e) => (
                <tr key={e.id}>
                  <td className="nowrap">{when(e.at)}</td>
                  <td>{e.username ?? <span className="muted">{e.subject ?? 'anonymous'}</span>}</td>
                  <td className="mono small">{e.clientId ?? ''}</td>
                  <td className="mono">{e.action}</td>
                  <td className="mono small reason">{e.resource ?? ''}</td>
                  <td><Badge tone={/^(2\d\d|OK)$/.test(e.outcome) ? 'good' : /^(4\d\d|NOT_FOUND)$/.test(e.outcome) ? 'warn' : 'bad'}>{e.outcome}</Badge></td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
    </>
  )
}
