import type { ReactNode } from 'react'
import { ApiError } from './api'
import { label } from './format'

export function Badge({ tone, children, title }: { tone: string; children: ReactNode; title?: string }) {
  return <span className={`badge ${tone}`} title={title}>{children}</span>
}

const STAGE_TONE: Record<string, string> = {
  ANALYSIS: 'info', AWAITING_DECISION: 'warn', FAILED: 'bad', CLOSED: 'muted',
}
export const StageBadge = ({ stage }: { stage: string }) => <Badge tone={STAGE_TONE[stage] ?? 'muted'}>{label(stage)}</Badge>

const SEVERITY_TONE: Record<string, string> = { HIGH: 'bad', MEDIUM: 'warn', LOW: 'muted' }
export const SeverityBadge = ({ severity }: { severity: string }) => (
  <Badge tone={SEVERITY_TONE[severity] ?? 'muted'}>{severity.toLowerCase()}</Badge>
)

const DISPOSITION_TONE: Record<string, string> = { LIKELY_FRAUD: 'bad', NEEDS_REVIEW: 'warn', LIKELY_LEGITIMATE: 'good' }
export const DispositionBadge = ({ disposition }: { disposition: string }) => (
  <Badge tone={DISPOSITION_TONE[disposition] ?? 'muted'}>{label(disposition)}</Badge>
)

export function Card({ title, actions, children, className }: {
  title?: ReactNode; actions?: ReactNode; children: ReactNode; className?: string
}) {
  return (
    <section className={`card ${className ?? ''}`}>
      {(title || actions) && (
        <header className="card-head">
          {title && <h2>{title}</h2>}
          {actions && <div className="card-actions">{actions}</div>}
        </header>
      )}
      {children}
    </section>
  )
}

export function Stat({ label: l, value, hint }: { label: string; value: ReactNode; hint?: ReactNode }) {
  return (
    <div className="stat">
      <div className="stat-label">{l}</div>
      <div className="stat-value">{value}</div>
      {hint && <div className="stat-hint">{hint}</div>}
    </div>
  )
}

export function Problem({ error }: { error: unknown }) {
  if (error instanceof ApiError && error.status === 403) {
    return <div className="notice">Your role does not include this view. Supervisors (e.g. <code>sam</code>) see it.</div>
  }
  return <div className="notice bad">Could not load: {error instanceof Error ? error.message : String(error)}</div>
}

export const Loading = () => <div className="loading">Loading…</div>

export function External({ href, children }: { href: string; children: ReactNode }) {
  return <a className="ext" href={href} target="_blank" rel="noreferrer">{children}<span aria-hidden> ↗</span></a>
}
