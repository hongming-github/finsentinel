import type { ConversationTurn, ConversationView } from './types'

// What the case assistant's answers mean for the analyst (ADR 0028), kept apart from the page so it can be tested.

/** Every answer of POST .../turns, as the panel shows it. */
export type AskOutcome =
  | { kind: 'answered'; view: ConversationView }
  | { kind: 'conflict'; view: ConversationView | null }
  | { kind: 'refused'; tone: 'warn' | 'bad'; message: string }

export function askOutcome(status: number, body: unknown): AskOutcome {
  const o = (body ?? {}) as Record<string, unknown>
  const error = typeof o.error === 'string' ? o.error : null
  switch (status) {
    case 200:
      return { kind: 'answered', view: body as ConversationView }
    case 409:
      // Another tab (or another instance) stored a turn first: nothing of this question was stored.
      return { kind: 'conflict', view: 'turns' in o ? (body as ConversationView) : null }
    case 429:
      return { kind: 'refused', tone: 'warn', message: error ?? 'The assistant\'s budget of questions per hour is used up.' }
    case 422:
      return { kind: 'refused', tone: 'warn', message: error ?? 'This conversation is full: start a new one.' }
    case 502: {
      const problems = Array.isArray(o.problems) ? (o.problems as unknown[]).map(String) : []
      return {
        kind: 'refused', tone: 'bad',
        message: `${error ?? 'The assistant could not give a grounded answer.'}${problems.length ? ` (${problems.join('; ')})` : ''}`,
      }
    }
    case 404:
      return { kind: 'refused', tone: 'bad', message: 'This conversation is not yours or not about this case.' }
    default:
      return { kind: 'refused', tone: 'bad', message: `${status} ${error ?? 'the question was not answered'}` }
  }
}

/** Said when the case has changed since the turn was answered: the assistant reads the case again every turn. */
export function staleNote(turn: ConversationTurn, caseVersion: number): string | null {
  return turn.caseVersion === caseVersion ? null
    : `Answered on case version ${turn.caseVersion}; the case is now version ${caseVersion}. Ask again for an answer on the current case.`
}

/** A condensed question worth showing: only when it differs from what the analyst typed. */
export function condensedNote(turn: ConversationTurn): string | null {
  const typed = turn.question.trim().toLowerCase()
  const condensed = turn.standaloneQuestion.trim()
  return condensed === '' || condensed.toLowerCase() === typed ? null : condensed
}
