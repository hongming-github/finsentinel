import { describe, expect, it } from 'vitest'
import { askOutcome, condensedNote, staleNote } from './assistant'
import type { ConversationTurn, ConversationView } from './types'

const turn: ConversationTurn = {
  seq: 2, question: 'And the second one?', standaloneQuestion: 'Where was the second card payment (1290.00 SGD) made?',
  searchQuery: null, answer: 'In London.', citedTxnIds: ['t-2'], citedChunkIds: [], untrustedInstructions: [],
  caseVersion: 3, model: 'gpt-6-luna', inputTokens: 1800, outputTokens: 60, cachedInputTokens: 1536,
  cacheWriteTokens: 0, llmCalls: 2, costUsd: 0.0004, latencyMs: 2100, createdAt: '2026-10-19T10:00:00Z',
}

describe('the case assistant panel', () => {
  it('reads every answer of the turn endpoint as an outcome', () => {
    const view = { conversation: { turns: 2 }, turns: [turn] } as unknown as ConversationView
    expect(askOutcome(200, view)).toEqual({ kind: 'answered', view })
    expect(askOutcome(409, view)).toEqual({ kind: 'conflict', view })
    expect(askOutcome(429, { error: 'budget used up' })).toEqual({ kind: 'refused', tone: 'warn', message: 'budget used up' })
    expect(askOutcome(502, { error: 'not grounded', problems: ['cites x'] }))
      .toEqual({ kind: 'refused', tone: 'bad', message: 'not grounded (cites x)' })
    expect(askOutcome(500, null)).toMatchObject({ kind: 'refused', tone: 'bad' })
  })

  it('says when the case changed after an answer', () => {
    expect(staleNote(turn, 3)).toBeNull()
    expect(staleNote(turn, 4)).toContain('now version 4')
  })

  it('shows the condensed question only when it says more than what was typed', () => {
    expect(condensedNote(turn)).toBe('Where was the second card payment (1290.00 SGD) made?')
    expect(condensedNote({ ...turn, standaloneQuestion: ' and the second one? ' })).toBeNull()
  })
})
