import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { api } from '../api'
import { condensedNote, staleNote } from '../assistant'
import { count, duration, short, usd, when } from '../format'
import type { ConversationTurn, ConversationView } from '../types'
import { Badge, Card, Loading, Problem } from '../ui'

// The case assistant (ADR 0028): an analyst's conversation about this case. The platform fixes tenant, case and
// analyst when the conversation starts, reads the case again for every answer and checks every cited id; the panel
// only shows what it stored. Nothing here refetches on a timer: every read is an audit entry.

export function AssistantPanel({ tenant, caseId, caseVersion }: { tenant: string; caseId: string; caseVersion: number }) {
  const client = useQueryClient()
  const list = useQuery({ queryKey: ['conversations', tenant, caseId], queryFn: () => api.conversations(tenant, caseId) })
  const [chosen, setChosen] = useState<string | null>(null)
  const current = chosen ?? list.data?.[0]?.conversationId ?? null
  const start = useMutation({
    mutationFn: () => api.startConversation(tenant, caseId),
    onSuccess: (c) => {
      setChosen(c.conversationId)
      void client.invalidateQueries({ queryKey: ['conversations', tenant, caseId] })
    },
  })

  return (
    <Card
      className="assistant"
      title={<>Ask about this case <span className="muted small">answers from this case's data and MAS regulations only; never a decision</span></>}
      actions={
        <>
          {list.data && list.data.length > 1 && (
            <select value={current ?? ''} onChange={(e) => setChosen(e.target.value)} aria-label="Conversation">
              {list.data.map((c) => (
                <option key={c.conversationId} value={c.conversationId}>{when(c.createdAt)} · {c.turns} turns</option>
              ))}
            </select>
          )}
          <button className="ghost" disabled={start.isPending} onClick={() => start.mutate()}>New conversation</button>
        </>
      }
    >
      {list.error ? <Problem error={list.error} />
        : !list.data ? <Loading />
          : current ? <Conversation key={current} tenant={tenant} caseId={caseId} id={current} caseVersion={caseVersion} />
            : <div className="empty">No conversation on this case yet. Start one to ask why the agents decided what they did, about a transaction, or what a regulation requires.</div>}
      {start.error && <div className="notice bad">{start.error.message}</div>}
    </Card>
  )
}

function Conversation({ tenant, caseId, id, caseVersion }: { tenant: string; caseId: string; id: string; caseVersion: number }) {
  const client = useQueryClient()
  const key = ['conversation', tenant, caseId, id]
  const view = useQuery({ queryKey: key, queryFn: () => api.conversation(tenant, caseId, id) })
  const [question, setQuestion] = useState('')
  const [notice, setNotice] = useState<{ tone: string; text: string } | null>(null)
  const ask = useMutation({
    mutationFn: (q: string) => api.ask(tenant, caseId, id, view.data?.conversation.turns ?? 0, q),
    onSuccess: (outcome, q) => {
      if (outcome.kind === 'answered') {
        client.setQueryData<ConversationView>(key, outcome.view)
        setQuestion('')
        setNotice(null)
      } else if (outcome.kind === 'conflict') {
        if (outcome.view) client.setQueryData<ConversationView>(key, outcome.view)
        else void client.invalidateQueries({ queryKey: key })
        setQuestion(q)
        setNotice({ tone: 'warn', text: 'Not asked: this conversation moved on in another tab. It is reloaded; ask again.' })
      } else {
        setNotice({ tone: outcome.tone, text: outcome.message })
      }
      void client.invalidateQueries({ queryKey: ['conversations', tenant, caseId] })
    },
    onError: (e) => setNotice({ tone: 'bad', text: e instanceof Error ? e.message : String(e) }),
  })

  if (view.error) return <Problem error={view.error} />
  if (!view.data) return <Loading />
  const { conversation, turns } = view.data
  const send = () => {
    const q = question.trim()
    if (q !== '' && !ask.isPending) ask.mutate(q)
  }
  return (
    <div className="chat">
      {conversation.summary && (
        <div className="notice small">
          <b>Turns 1–{conversation.summaryThrough}, summarised</b> (older turns are folded to keep the prompt within its budget): {conversation.summary}
        </div>
      )}
      {turns.length === 0 && !ask.isPending && <div className="empty">Ask the first question.</div>}
      {turns.map((t) => <Turn key={t.seq} turn={t} caseVersion={caseVersion} />)}
      {ask.isPending && (
        <div className="turn">
          <div className="bubble q">{ask.variables}</div>
          <div className="bubble a muted">Reading the case and answering…</div>
        </div>
      )}
      {notice && <div className={`notice ${notice.tone}`}>{notice.text}</div>}
      <div className="chat-input">
        <textarea
          value={question}
          placeholder="Why NEEDS_REVIEW? · And the second transfer? · What does the EUPG say about the cooling-off period?"
          rows={2}
          maxLength={2000}
          onChange={(e) => setQuestion(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault()
              send()
            }
          }}
        />
        <button className="primary" disabled={ask.isPending || question.trim() === ''} onClick={send}>Ask</button>
      </div>
      <div className="muted small">
        Sent with If-Match "{conversation.turns}": refused if another tab added a turn first. Enter sends, Shift+Enter adds a line.
      </div>
    </div>
  )
}

function Turn({ turn: t, caseVersion }: { turn: ConversationTurn; caseVersion: number }) {
  const condensed = condensedNote(t)
  const stale = staleNote(t, caseVersion)
  return (
    <div className="turn">
      <div className="bubble q">
        {t.question}
        {t.untrustedInstructions.length > 0 && (
          <div className="flag">
            <Badge tone="bad" title={t.untrustedInstructions.join('; ')}>pasted instructions flagged</Badge>
            <span className="small"> {t.untrustedInstructions.join('; ')}: sent to the model as data, with the platform's warning</span>
          </div>
        )}
      </div>
      <div className="bubble a">
        <div className="answer">{t.answer}</div>
        {(t.citedTxnIds.length > 0 || t.citedChunkIds.length > 0) && (
          <div className="chips">
            {t.citedTxnIds.map((id) => <code key={id} className="chip" title={`transaction ${id}`}>{short(id)}</code>)}
            {t.citedChunkIds.map((id) => <code key={id} className="chip reg" title="regulation passage">{id}</code>)}
          </div>
        )}
        <div className="meta small muted">
          {condensed && <span title="The question rewritten with the conversation, as retrieval saw it">understood as “{condensed}” · </span>}
          {t.searchQuery && <span title="What the regulation search was asked">searched “{t.searchQuery}” · </span>}
          case v{t.caseVersion} · {t.llmCalls} model calls · {count(t.inputTokens)} in ({count(t.cachedInputTokens)} cached) / {count(t.outputTokens)} out · {usd(t.costUsd)} · {duration(t.latencyMs)}
        </div>
        {stale && <div className="notice warn small">{stale}</div>}
      </div>
    </div>
  )
}
