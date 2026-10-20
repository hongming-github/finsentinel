// The platform's API as the console reads it (agent-platform: CaseController, AlertController, UsageController,
// AuditController). Field names follow the Java records.

export type CaseStage = 'ANALYSIS' | 'AWAITING_DECISION' | 'FAILED' | 'CLOSED'
export type Severity = 'HIGH' | 'MEDIUM' | 'LOW'
export type Decision = 'CONFIRMED_FRAUD' | 'FALSE_POSITIVE' | 'REQUEST_REANALYSIS'
export type Disposition = 'LIKELY_FRAUD' | 'NEEDS_REVIEW' | 'LIKELY_LEGITIMATE'

export const STAGES: CaseStage[] = ['ANALYSIS', 'AWAITING_DECISION', 'FAILED', 'CLOSED']

export interface FraudCase {
  caseId: string
  tenantId: string
  accountId: string
  customerId: string
  stage: CaseStage
  priority: Severity
  attempts: number
  analysisRun: number
  analystNote: string | null
  version: number
  openedAt: string
  updatedAt: string
  closedAt: string | null
  decision: Decision | null
  decidedBy: string | null
}

export interface Alert {
  alertId: string
  tenantId: string
  accountId: string
  customerId: string
  rule: string
  severity: Severity
  reason: string
  txnIds: string[]
  eventTime: string
  detectedAt: string
}

export interface StoredAlert {
  alert: Alert
  caseId: string
  receivedAt: string
}

export interface CaseReport {
  disposition: Disposition
  riskScore: number
  summary: string
  findings: { statement: string; evidenceTxnIds: string[] }[]
  recommendedActions: string[]
  regulatoryNotes: { statement: string; sourceChunkIds: string[] }[]
}

export interface ToolCall {
  tool: string
  arguments: string
  resultCount: number
  durationMs: number
  cacheHit: boolean | null
  savedUsd: number | null
}

export interface LlmUsage {
  model: string | null
  inputTokens: number | null
  outputTokens: number | null
  cachedInputTokens: number | null
  cacheWriteTokens: number | null
  calls: number
  latencyMs: number
  costUsd: number
  costByModel: Record<string, number>
}

export interface Review {
  approved: boolean
  issues: string[]
  usage: LlmUsage | null
  skippedBecause: string | null
}

export interface CaseAnalysis {
  run: number
  report: CaseReport
  toolCalls: ToolCall[]
  repairedIssues: string[]
  model: string | null
  inputTokens: number | null
  outputTokens: number | null
  cachedInputTokens: number | null
  cacheWriteTokens: number | null
  llmCalls: number
  latencyMs: number
  createdAt: string
  mode: string
  review: Review | null
  costUsd: number | null
}

export interface CaseEvent {
  eventId: number
  at: string
  type: string
  fromStage: CaseStage | null
  toStage: CaseStage | null
  actor: string
  detail: Record<string, unknown> | null
}

export interface CaseDetail {
  fraudCase: FraudCase
  alerts: StoredAlert[]
  analyses: CaseAnalysis[]
  events: CaseEvent[]
  traces: { currentRun: string | null; alerts: string[] }
}

export interface UsageReport {
  days: number
  costPerCase: { analyses: number; total: number; average: number | null; p95: number | null; max: number | null }
  costByModel: Record<string, number>
  byMode: { mode: string; analyses: number; averageCost: number; averageLatencyMs: number; averageCalls: number }[]
  regulationSearchCache: { searches: number; hits: number; hitRate: number | null; savedUsd: number }
}

export interface AuditEntry {
  id: number
  at: string
  subject: string | null
  username: string | null
  clientId: string | null
  action: string
  resource: string | null
  outcome: string
  detail: Record<string, string>
}

export interface AuditVerification {
  chain: string
  entries: number
  firstBrokenId: number | null
  intact: boolean
}

// The case assistant (agent-platform: ConversationController, ADR 0028).

export interface Conversation {
  conversationId: string
  tenantId: string
  caseId: string
  analystSubject: string
  analystUsername: string
  turns: number
  summary: string | null
  summaryThrough: number
  createdAt: string
  updatedAt: string
}

export interface ConversationTurn {
  seq: number
  question: string
  standaloneQuestion: string
  searchQuery: string | null
  answer: string
  citedTxnIds: string[]
  citedChunkIds: string[]
  untrustedInstructions: string[]
  caseVersion: number
  model: string | null
  inputTokens: number | null
  outputTokens: number | null
  cachedInputTokens: number | null
  cacheWriteTokens: number | null
  llmCalls: number
  costUsd: number
  latencyMs: number
  createdAt: string
}

export interface ConversationView {
  conversation: Conversation
  turns: ConversationTurn[]
}
