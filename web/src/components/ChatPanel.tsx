import { useEffect, useRef, useState, type KeyboardEvent } from 'react'
import { api, message } from '../api'
import { STATUS_LABELS } from '../labels'
import type { PlannerTrace, ToolInfo, Turn } from '../types'

/**
 * 对话：用户说一句话，Java 让规划模型给出工具调用提议并独立校验；单步直接执行，多步先展示计划等用户确认。
 * 这里只负责展示与发起 confirm / cancel / retry，是否允许由服务端按状态机判断。
 */
export function ChatPanel({
  sessionId,
  turns,
  tools,
  hasSelection,
  onChanged,
}: {
  sessionId: string
  turns: Turn[]
  tools: ToolInfo[]
  hasSelection: boolean
  onChanged: () => void
}) {
  const [text, setText] = useState('')
  const [sending, setSending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const list = useRef<HTMLDivElement>(null)
  const labels = Object.fromEntries(tools.map((tool) => [tool.name, tool.label]))

  useEffect(() => {
    list.current?.scrollTo({ top: list.current.scrollHeight })
  }, [turns.length])

  async function send() {
    const goal = text.trim()
    if (!goal || sending) return
    setSending(true)
    setError(null)
    try {
      await api.send(sessionId, goal, crypto.randomUUID())
      setText('')
      onChanged()
    } catch (e) {
      setError(message(e))
    } finally {
      setSending(false)
    }
  }

  async function act(action: 'confirm' | 'cancel' | 'retry', turn: Turn) {
    setError(null)
    try {
      await api[action](sessionId, turn.id)
      onChanged()
    } catch (e) {
      setError(message(e))
    }
  }

  const onKey = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault()
      send()
    }
  }

  return (
    <div className="chat">
      <div className="turns" ref={list} data-testid="turns">
        {turns.length === 0 && <p className="hint">用一句话描述要怎么修，例如“先水平翻转，再把亮度调高一点”。多步操作会先给出计划，确认后才执行。</p>}
        {turns.map((turn) => (
          <TurnView key={turn.id} sessionId={sessionId} turn={turn} labels={labels} onAct={act} />
        ))}
      </div>
      {error && <p className="error">{error}</p>}
      <div className="composer">
        <textarea
          name="message"
          rows={2}
          placeholder={hasSelection ? '已有选区，可以说“把选中的东西去掉”' : '描述你想怎么修这张图'}
          value={text}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={onKey}
          data-testid="chat-input"
        />
        <button className="primary" onClick={send} disabled={sending || !text.trim()} data-testid="chat-send">
          {sending ? '规划中…' : '发送'}
        </button>
      </div>
    </div>
  )
}

function TurnView({
  sessionId,
  turn,
  labels,
  onAct,
}: {
  sessionId: string
  turn: Turn
  labels: Record<string, string>
  onAct: (action: 'confirm' | 'cancel' | 'retry', turn: Turn) => void
}) {
  const [trace, setTrace] = useState<PlannerTrace | null>(null)
  const awaiting = turn.status === 'queued' && turn.steps.some((step) => step.status === 'pending' || step.status === 'waiting')
  const statusText = awaiting ? '等待确认' : (STATUS_LABELS[turn.status] ?? turn.status)

  const toggleTrace = async () => {
    if (trace) {
      setTrace(null)
      return
    }
    setTrace(await api.trace(sessionId, turn.id).catch(() => ({ failure: '读取失败' })))
  }

  return (
    <div className="turn" data-testid="turn" data-status={turn.status}>
      <div className="bubble user">{turn.goal}</div>
      <div className="bubble assistant">
        {turn.reply && <p>{turn.reply}</p>}
        {turn.steps.length > 0 && (
          <ol className="steps">
            {turn.steps.map((step, index) => (
              <li key={step.id} className={`step ${step.status}`} data-testid="step">
                <span className="step-index">{index + 1}.</span>
                <span className="step-label">{step.label || labels[step.tool] || step.tool}</span>
                <code>{step.tool}</code>
                <span className={`chip ${step.status}`}>{STATUS_LABELS[step.status] ?? step.status}</span>
              </li>
            ))}
          </ol>
        )}
        <div className="turn-footer">
          <span className={`chip ${awaiting ? 'waiting' : turn.status}`} data-testid="turn-status">
            {statusText}
          </span>
          {turn.error && <span className="error">{turn.error}</span>}
          <span className="spacer" />
          {awaiting && (
            <button className="primary small" onClick={() => onAct('confirm', turn)} data-testid="confirm">
              确认执行
            </button>
          )}
          {(awaiting || turn.status === 'running') && (
            <button className="small" onClick={() => onAct('cancel', turn)} data-testid="cancel">
              取消
            </button>
          )}
          {turn.status === 'failed' && turn.steps.length > 0 && (
            <button className="small" onClick={() => onAct('retry', turn)} data-testid="retry">
              重试失败的步骤
            </button>
          )}
          <button className="link" onClick={toggleTrace}>
            {trace ? '收起' : '规划详情'}
          </button>
        </div>
        {trace && (
          <dl className="trace" data-testid="trace">
            <dt>模型</dt>
            <dd>{trace.model ?? '—'}</dd>
            <dt>耗时</dt>
            <dd>{trace.elapsed_ms ?? '—'} ms</dd>
            <dt>token</dt>
            <dd>
              {trace.prompt_tokens ?? '—'} / {trace.completion_tokens ?? '—'}
            </dd>
            <dt>被拒的提议</dt>
            <dd>{trace.rejected && trace.rejected.length ? JSON.stringify(trace.rejected) : '无'}</dd>
            {trace.failure && (
              <>
                <dt>失败</dt>
                <dd>{trace.failure}</dd>
              </>
            )}
          </dl>
        )}
      </div>
    </div>
  )
}
