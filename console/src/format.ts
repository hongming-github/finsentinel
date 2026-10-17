// One locale for every viewer: the console's screenshots and the platform's docs read the same.
const dateTime = new Intl.DateTimeFormat('en-GB', {
  month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
})

export const when = (iso: string | null | undefined) => (iso ? dateTime.format(new Date(iso)) : '—')

export function ago(iso: string, now: number = Date.now()): string {
  const s = Math.round((now - new Date(iso).getTime()) / 1000)
  if (s < 60) return `${s}s ago`
  if (s < 3600) return `${Math.round(s / 60)}m ago`
  if (s < 86400) return `${Math.round(s / 3600)}h ago`
  return `${Math.round(s / 86400)}d ago`
}

export function duration(ms: number): string {
  if (ms < 1000) return `${ms} ms`
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)} s`
  return `${Math.floor(ms / 60_000)}m ${Math.round((ms % 60_000) / 1000)}s`
}

export function usd(v: number | null | undefined): string {
  if (v == null) return '—'
  if (v === 0) return '$0'
  if (v < 0.00001) return '<$0.00001'
  return v < 0.01 ? `$${v.toFixed(5)}` : `$${v.toFixed(3)}`
}

export const count = (v: number | null | undefined) => (v == null ? '—' : v.toLocaleString())

export const pct = (v: number | null | undefined) => (v == null ? '—' : `${Math.round(v * 100)}%`)

export const short = (id: string) => id.slice(0, 8)

export const label = (s: string) => s.replace(/_/g, ' ').toLowerCase()
