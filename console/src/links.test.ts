import { describe, expect, it } from 'vitest'
import { caseWindow, lokiCaseLogs, lokiTraceLogs, pipelineDashboard, tempoTrace } from './links'

const TRACE = '4bf92f3577b34da6a3ce929d0e0e4736'
const CASE = '03de386e-ad6c-4339-ae50-a45b0ffef7e2'

function panes(url: string) {
  const u = new URL(url)
  expect(u.origin).toBe('http://localhost:3000')
  expect(u.pathname).toBe('/explore')
  return JSON.parse(u.searchParams.get('panes')!).fs
}

describe('links into Grafana', () => {
  it('opens a trace in Tempo by id over the case window', () => {
    const window = caseWindow('2026-10-16T16:32:01Z', '2026-10-16T18:16:23Z', new Date('2026-10-17T00:00:00Z'))
    const pane = panes(tempoTrace(TRACE, window))
    expect(pane.datasource).toBe('tempo')
    expect(pane.queries[0]).toMatchObject({ queryType: 'traceql', query: TRACE, datasource: { uid: 'tempo' } })
    expect(pane.range).toEqual({ from: String(Date.parse('2026-10-16T16:22:01Z')),
      to: String(Date.parse('2026-10-16T18:26:23Z')) })
  })

  it('filters Loki by trace id and by case id, on the platform only', () => {
    expect(panes(lokiTraceLogs(TRACE)).queries[0].expr)
      .toBe('{service_name="agent-platform"} | trace_id = `' + TRACE + '`')
    expect(panes(lokiCaseLogs(CASE)).queries[0].expr).toBe('{service_name="agent-platform"} |= `' + CASE + '`')
  })

  it('never puts anything but an id into a query', () => {
    expect(() => tempoTrace('x" or true')).toThrow()
    expect(() => lokiTraceLogs('`} |= ""')).toThrow()
    expect(() => lokiCaseLogs('straits-acc-0350')).toThrow()
  })

  it('ends the window now for a case still open, and keeps it non-empty', () => {
    const now = new Date('2026-10-17T10:00:00Z')
    const w = caseWindow('2026-10-17T09:59:00Z', '2026-10-17T09:59:30Z', now)
    expect(w.to).toEqual(now)
    expect(w.to.getTime()).toBeGreaterThan(w.from.getTime())
  })

  it('opens the pipeline dashboard over a window', () => {
    const url = new URL(pipelineDashboard({ from: new Date(1000), to: new Date(2000) }))
    expect(url.pathname).toBe('/d/finsentinel-pipeline/finsentinel-pipeline')
    expect(url.searchParams.get('from')).toBe('1000')
    expect(url.searchParams.get('to')).toBe('2000')
  })
})
