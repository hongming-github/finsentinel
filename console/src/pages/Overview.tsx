import { useQueries, useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { api, health, type Health } from '../api'
import { isSupervisor } from '../claims'
import { ago, count, duration, label, pct, short, usd } from '../format'
import { FAKE_LLM, FLINK_UI, GRAFANA, kafkaTopics, keycloakAdmin, pipelineDashboard } from '../links'
import { useSession } from '../session'
import { STAGES } from '../types'
import { Card, External, Loading, Problem, SeverityBadge, Stat } from '../ui'

/** Every process of the local stack, in the order a payment passes them. */
const PROCESSES: { name: string; title: string; port: number; role: string; ui?: string }[] = [
  { name: 'flink', title: 'Flink job', port: 8082, role: 'risk rules on the transaction stream', ui: FLINK_UI },
  { name: 'schema-registry', title: 'Schema registry', port: 8095, role: 'Avro contracts of the v2 topics' },
  { name: 'platform', title: 'Agent platform', port: 8090, role: 'case queue, agents, REST and MCP' },
  { name: 'fake-llm', title: 'fake-llm', port: 8096, role: 'OpenAI-protocol stand-in (profile load)', ui: `${FAKE_LLM}/admin/behaviour` },
  { name: 'mcp-banking', title: 'mcp-banking', port: 8091, role: 'core banking, KYC and card systems' },
  { name: 'mcp-compliance', title: 'mcp-compliance', port: 8092, role: 'sanctions and adverse media' },
  { name: 'keycloak', title: 'Keycloak', port: 8094, role: 'identity: users, roles, tenant claim', ui: keycloakAdmin },
  { name: 'grafana', title: 'Grafana LGTM', port: 3000, role: 'traces, metrics and logs', ui: GRAFANA },
  { name: 'kafka-ui', title: 'Kafka UI', port: 8098, role: 'topics and lag (profile kafka-ui)', ui: kafkaTopics },
]

const PAGE = 200

export function Overview() {
  const session = useSession()
  const healths = useQueries({
    queries: PROCESSES.map((p) => ({
      queryKey: ['health', p.name],
      queryFn: () => health(p.name),
      refetchInterval: 5_000,
    })),
  })
  // Case and alert reads are audited (ADR 0016), so these refresh every 30 s; health checks are not API reads.
  const stages = useQueries({
    queries: STAGES.map((stage) => ({
      queryKey: ['cases', session.tenant, stage, PAGE],
      queryFn: () => api.cases(session.tenant, stage, PAGE),
      refetchInterval: 30_000,
    })),
  })
  const alerts = useQuery({
    queryKey: ['alerts', session.tenant, 8],
    queryFn: () => api.alerts(session.tenant, 8),
    refetchInterval: 30_000,
  })
  const supervisor = isSupervisor(session)
  const usage = useQuery({
    queryKey: ['usage', session.tenant, 1],
    queryFn: () => api.usage(session.tenant, 1),
    enabled: supervisor,
  })

  return (
    <>
      <div className="page-head">
        <h1>Overview</h1>
        <External href={pipelineDashboard()}>Pipeline dashboard</External>
      </div>

      <Card title="Processes" actions={<span className="muted small">health every 5 s, through the console's proxy</span>}>
        <div className="health-grid">
          {PROCESSES.map((p, i) => (
            <HealthTile key={p.name} process={p} health={healths[i].data} />
          ))}
        </div>
      </Card>

      <div className="grid-2">
        <Card title={<>Cases of bank {session.tenant}</>} actions={<Link to="/cases">All cases →</Link>}>
          <div className="stats">
            {STAGES.map((stage, i) => {
              const n = stages[i].data?.length
              return (
                <Link key={stage} to={`/cases?stage=${stage}`} className="stat-link">
                  <Stat label={label(stage)} value={n == null ? '…' : n >= PAGE ? `${PAGE}+` : n} />
                </Link>
              )
            })}
          </div>
          <p className="muted small">The case is the unit of work: alerts on an account join its open case.</p>
        </Card>

        <Card title="LLM cost, last 24 h" actions={supervisor ? <Link to="/usage">Details →</Link> : null}>
          {!supervisor ? (
            <div className="notice">Cost reports are for supervisors (sign in as <code>sam</code>).</div>
          ) : usage.error ? <Problem error={usage.error} /> : !usage.data ? <Loading /> : (
            <div className="stats">
              <Stat label="analyses" value={count(usage.data.costPerCase.analyses)} />
              <Stat label="total" value={usd(usage.data.costPerCase.total)} />
              <Stat label="per case p95" value={usd(usage.data.costPerCase.p95)} />
              <Stat label="search cache hits" value={pct(usage.data.regulationSearchCache.hitRate)} />
            </div>
          )}
        </Card>
      </div>

      <Card title="Latest alerts" actions={<Link to="/alerts">All alerts →</Link>}>
        {alerts.error ? <Problem error={alerts.error} /> : !alerts.data ? <Loading /> : (
          <table className="table">
            <thead><tr><th>detected</th><th>rule</th><th>severity</th><th>reason</th><th>to platform</th><th>case</th></tr></thead>
            <tbody>
              {alerts.data.map((a) => (
                <tr key={a.alert.alertId}>
                  <td className="nowrap">{ago(a.alert.detectedAt)}</td>
                  <td>{a.alert.rule}</td>
                  <td><SeverityBadge severity={a.alert.severity} /></td>
                  <td className="reason">{a.alert.reason}</td>
                  <td className="num">{duration(Math.max(0, Date.parse(a.receivedAt) - Date.parse(a.alert.detectedAt)))}</td>
                  <td><Link to={`/cases/${a.caseId}`} className="mono">{short(a.caseId)}</Link></td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
    </>
  )
}

function HealthTile({ process: p, health: h }: { process: (typeof PROCESSES)[number]; health?: Health }) {
  const state = h?.state ?? 'pending'
  return (
    <div className={`health ${state}`}>
      <div className="health-head">
        <span className="dot" aria-label={state} />
        <b>{p.title}</b>
        <span className="muted small">:{p.port}</span>
      </div>
      <div className="small">{p.role}</div>
      <div className="health-foot">
        <span className="muted small">{h ? h.detail : 'checking…'}</span>
        {p.ui && <External href={p.ui}>open</External>}
      </div>
    </div>
  )
}
