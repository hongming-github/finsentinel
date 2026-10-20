import { accessToken } from './auth'
import { type AskOutcome, askOutcome } from './assistant'
import type {
  AuditEntry, AuditVerification, CaseDetail, CaseStage, Conversation, ConversationView, Decision, FraudCase,
  StoredAlert, UsageReport,
} from './types'

// The platform's tenant-scoped API, called through the console's own origin (vite.config.ts). The tenant in the path is
// the token's (the platform answers 404 for any other, TenantPathFilter); the console never lets anyone pick one.

export class ApiError extends Error {
  readonly status: number
  readonly body: unknown

  constructor(status: number, message: string, body: unknown) {
    super(message)
    this.status = status
    this.body = body
  }
}

async function request(path: string, init: RequestInit = {}): Promise<Response> {
  const token = await accessToken()
  const response = await fetch(path, {
    ...init,
    headers: { ...init.headers, Authorization: `Bearer ${token}`, Accept: 'application/json' },
  })
  if (!response.ok && response.status !== 409) {
    const body = await response.json().catch(() => null)
    const message = body && typeof body === 'object' && 'error' in body ? String(body.error) : response.statusText
    throw new ApiError(response.status, `${response.status} ${message}`, body)
  }
  return response
}

async function get<T>(path: string): Promise<T> {
  return (await request(path)).json() as Promise<T>
}

const base = (tenant: string) => `/api/tenants/${encodeURIComponent(tenant)}`
const conversationPath = (tenant: string, caseId: string) =>
  `${base(tenant)}/cases/${encodeURIComponent(caseId)}/conversations`

export const api = {
  cases: (tenant: string, stage: CaseStage | null, limit: number) =>
    get<FraudCase[]>(`${base(tenant)}/cases?limit=${limit}${stage ? `&stage=${stage}` : ''}`),

  /** The case with its version, from the ETag: what a decision must send back in If-Match. */
  caseDetail: async (tenant: string, caseId: string) => {
    const response = await request(`${base(tenant)}/cases/${encodeURIComponent(caseId)}`)
    return { detail: (await response.json()) as CaseDetail, etag: response.headers.get('ETag') }
  },

  /** 409 is an answer, not an error: someone or something changed the case since it was read. */
  decide: async (tenant: string, caseId: string, etag: string, decision: Decision, note: string) => {
    const response = await request(`${base(tenant)}/cases/${encodeURIComponent(caseId)}/decision`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'If-Match': etag },
      body: JSON.stringify({ decision, note: note.trim() === '' ? null : note }),
    })
    return { conflict: response.status === 409, fraudCase: (await response.json()) as FraudCase }
  },

  // The case assistant: the analyst's own conversations on one case. Every call is an audited model call or read.
  conversations: (tenant: string, caseId: string) =>
    get<Conversation[]>(`${base(tenant)}/cases/${encodeURIComponent(caseId)}/conversations`),

  conversation: (tenant: string, caseId: string, id: string) =>
    get<ConversationView>(`${conversationPath(tenant, caseId)}/${encodeURIComponent(id)}`),

  startConversation: async (tenant: string, caseId: string) =>
    (await (await request(conversationPath(tenant, caseId), { method: 'POST' })).json()) as Conversation,

  /** A question on top of the turns the analyst has seen (If-Match); every answer of the platform is an outcome. */
  ask: async (tenant: string, caseId: string, id: string, seenTurns: number, question: string): Promise<AskOutcome> => {
    const token = await accessToken()
    const response = await fetch(`${conversationPath(tenant, caseId)}/${encodeURIComponent(id)}/turns`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${token}`, Accept: 'application/json', 'Content-Type': 'application/json',
        'If-Match': `"${seenTurns}"`,
      },
      body: JSON.stringify({ question }),
    })
    return askOutcome(response.status, await response.json().catch(() => null))
  },

  alerts: (tenant: string, limit: number) => get<StoredAlert[]>(`${base(tenant)}/alerts?limit=${limit}`),
  usage: (tenant: string, days: number) => get<UsageReport>(`${base(tenant)}/llm-usage?days=${days}`),
  audit: (tenant: string, limit: number) => get<AuditEntry[]>(`${base(tenant)}/audit?limit=${limit}`),
  auditVerify: (tenant: string) => get<AuditVerification>(`${base(tenant)}/audit/verify`),
}

export type HealthState = 'up' | 'down' | 'degraded'

export interface Health {
  state: HealthState
  detail: string
}

/** One process's health through the console's proxy (no token: the health endpoints are open). */
export async function health(name: string): Promise<Health> {
  try {
    const response = await fetch(`/health/${name}`, { signal: AbortSignal.timeout(3000) })
    if (!response.ok) {
      // The proxy answers 502 or 504 when nothing listens on the process's port.
      return { state: 'down', detail: response.status === 502 || response.status === 504 ? 'not running' : `HTTP ${response.status}` }
    }
    const body: unknown = await response.json().catch(() => null)
    return interpret(name, body)
  } catch (e) {
    return { state: 'down', detail: e instanceof Error && e.name === 'TimeoutError' ? 'no answer in 3 s' : 'unreachable' }
  }
}

export function interpret(name: string, body: unknown): Health {
  const o = (body ?? {}) as Record<string, unknown>
  switch (name) {
    case 'flink': {
      const jobs = Array.isArray(o.jobs) ? (o.jobs as { name: string; state: string }[]) : []
      const running = jobs.filter((j) => j.state === 'RUNNING')
      return running.length > 0
        ? { state: 'up', detail: `${running.map((j) => j.name).join(', ')} RUNNING` }
        : { state: 'degraded', detail: jobs.length ? `job ${jobs[0].state}` : 'cluster up, no job' }
    }
    case 'keycloak':
      return { state: 'up', detail: 'realm finsentinel' }
    case 'schema-registry':
      return { state: 'up', detail: `${Array.isArray(body) ? body.length : 0} subjects` }
    case 'grafana':
      return o.database === 'ok' ? { state: 'up', detail: `v${String(o.version ?? '')}` } : { state: 'degraded', detail: 'database not ok' }
    default: {
      const status = typeof o.status === 'string' ? o.status : 'UP'
      return { state: status === 'UP' ? 'up' : 'degraded', detail: status }
    }
  }
}
