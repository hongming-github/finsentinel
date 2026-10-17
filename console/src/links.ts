// Deep links from the console into the tools of the obs profile (ADR 0027): Grafana's Explore for Tempo and Loki,
// the pipeline dashboard, and the UIs of Flink, Kafka and Keycloak. Grafana's datasource uids are those of the
// grafana/otel-lgtm image.

export const GRAFANA = 'http://localhost:3000'
export const FLINK_UI = 'http://localhost:8082'
export const KAFKA_UI = 'http://localhost:8098'
export const KEYCLOAK = 'http://localhost:8094'
export const FAKE_LLM = 'http://localhost:8096'

/** The platform's service name in traces and logs (spring.application.name). */
export const PLATFORM_SERVICE = 'agent-platform'

export interface TimeWindow {
  from: Date
  to: Date
}

/** A window around a case's life, with some margin: the trace starts before the case opens (the payment, Flink). */
export function caseWindow(openedAt: string, lastAt: string, now: Date = new Date()): TimeWindow {
  const margin = 10 * 60 * 1000
  const from = new Date(new Date(openedAt).getTime() - margin)
  const to = new Date(Math.min(new Date(lastAt).getTime() + margin, now.getTime()))
  return { from, to: to < from ? new Date(from.getTime() + margin) : to }
}

function explore(datasource: 'tempo' | 'loki', query: Record<string, unknown>, window?: TimeWindow): string {
  const range = window
    ? { from: String(window.from.getTime()), to: String(window.to.getTime()) }
    : { from: 'now-1h', to: 'now' }
  const panes = {
    fs: { datasource, queries: [{ refId: 'A', datasource: { type: datasource, uid: datasource }, ...query }], range },
  }
  return `${GRAFANA}/explore?schemaVersion=1&orgId=1&panes=${encodeURIComponent(JSON.stringify(panes))}`
}

/** One trace in Tempo, by id. */
export function tempoTrace(traceId: string, window?: TimeWindow): string {
  if (!/^[0-9a-f]{32}$/.test(traceId)) {
    throw new Error(`not a trace id: ${traceId}`)
  }
  return explore('tempo', { queryType: 'traceql', query: traceId }, window)
}

/** The platform's log lines written inside one trace. */
export function lokiTraceLogs(traceId: string, window?: TimeWindow): string {
  if (!/^[0-9a-f]{32}$/.test(traceId)) {
    throw new Error(`not a trace id: ${traceId}`)
  }
  return explore('loki', { expr: `{service_name="${PLATFORM_SERVICE}"} | trace_id = \`${traceId}\`` }, window)
}

/** The platform's log lines that name a case: every run, the worker's summary and the review. */
export function lokiCaseLogs(caseId: string, window?: TimeWindow): string {
  if (!/^[0-9a-f-]{36}$/.test(caseId)) {
    throw new Error(`not a case id: ${caseId}`)
  }
  return explore('loki', { expr: `{service_name="${PLATFORM_SERVICE}"} |= \`${caseId}\`` }, window)
}

/** The "FinSentinel pipeline" dashboard (deploy/grafana/dashboards), optionally over a window. */
export function pipelineDashboard(window?: TimeWindow): string {
  const range = window ? `&from=${window.from.getTime()}&to=${window.to.getTime()}` : '&from=now-30m&to=now'
  return `${GRAFANA}/d/finsentinel-pipeline/finsentinel-pipeline?orgId=1${range}`
}

export const kafkaTopics = `${KAFKA_UI}/ui/clusters/finsentinel/all-topics`
export const keycloakAdmin = `${KEYCLOAK}/admin/master/console/#/finsentinel`
