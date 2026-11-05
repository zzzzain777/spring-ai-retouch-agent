import { useCallback, useEffect, useMemo, useRef, useState, type ChangeEvent } from 'react'
import { ApiError, TERMINAL, api, message, untilDone } from '../api'
import { time } from '../labels'
import { navigate } from '../nav'
import type { Asset, HistoryEntry, Run, SessionDetail, SessionSummary, Selection, ToolInfo, Turn } from '../types'
import { Canvas, type Point, type SelectMode } from './Canvas'
import { ChatPanel } from './ChatPanel'
import type { Params } from './SchemaForm'
import { HistoryPanel, LayersPanel, WallPanel } from './SidePanels'
import { ToolPanel } from './ToolPanel'

type Tab = 'chat' | 'tools' | 'layers' | 'wall' | 'history'

const TABS: [Tab, string][] = [
  ['chat', '对话'],
  ['tools', '工具'],
  ['layers', '图层'],
  ['wall', '图片墙'],
  ['history', '历史'],
]

export function Workspace({ sessionId, tools }: { sessionId: string | null; tools: ToolInfo[] }) {
  const [sessions, setSessions] = useState<SessionSummary[]>([])
  const [uploads, setUploads] = useState<Asset[]>([])
  const [detail, setDetail] = useState<SessionDetail | null>(null)
  const [selection, setSelection] = useState<Selection | null>(null)
  const [turns, setTurns] = useState<Turn[]>([])
  const [history, setHistory] = useState<HistoryEntry[]>([])
  const [layerAssets, setLayerAssets] = useState<Record<string, string>>({})
  const [tab, setTab] = useState<Tab>('chat')
  const [mode, setMode] = useState<SelectMode>('off')
  const [brush, setBrush] = useState(0.04)
  const [activeLayerId, setActiveLayerId] = useState<string | null>(null)
  const [run, setRun] = useState<Run | null>(null)
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  const fileInput = useRef<HTMLInputElement>(null)

  const loadLists = useCallback(() => {
    api.sessions().then(setSessions, () => undefined)
    api.recentAssets().then((assets) => setUploads(assets.filter((asset) => asset.source === 'upload')), () => undefined)
  }, [])

  const refresh = useCallback(async () => {
    if (!sessionId) return
    try {
      const [next, sel, hist] = await Promise.all([api.session(sessionId), api.selection(sessionId), api.history(sessionId)])
      setDetail(next)
      setSelection(sel)
      setHistory(hist)
    } catch (e) {
      if (e instanceof ApiError && e.status === 404) {
        setNotice('会话不存在')
        navigate('/')
      } else setNotice(message(e))
    }
  }, [sessionId])

  const refreshTurns = useCallback(async () => {
    if (!sessionId) return
    setTurns(await api.turns(sessionId).catch(() => []))
  }, [sessionId])

  useEffect(loadLists, [loadLists])

  useEffect(() => {
    setDetail(null)
    setSelection(null)
    setTurns([])
    setRun(null)
    setActiveLayerId(null)
    setNotice(null)
    refresh()
    refreshTurns()
  }, [sessionId, refresh, refreshTurns])

  // 图层引用的素材不一定在图片墙里（例如抠出的主体层），缺的单独取一次签名 URL
  const urls = useMemo(() => {
    const map: Record<string, string> = { ...layerAssets }
    detail?.assets.forEach((asset) => (map[asset.id] = asset.url))
    return map
  }, [detail, layerAssets])
  useEffect(() => {
    const missing = (detail?.document.layers ?? []).map((layer) => layer.asset_id).filter((id): id is string => !!id && !urls[id])
    if (missing.length === 0) return
    Promise.all(missing.map((id) => api.asset(id).catch(() => null))).then((found) => {
      setLayerAssets((current) => {
        const next = { ...current }
        found.forEach((asset) => asset && (next[asset.id] = asset.url))
        return next
      })
    })
  }, [detail, urls])

  // 对话里有在执行的轮次时轮询：步骤状态以服务端为准，有步骤完成就刷新画布
  const active = turns.some((turn) => turn.status === 'running' || turn.steps.some((step) => step.status === 'queued' || step.status === 'running'))
  const finishedSteps = turns.flatMap((turn) => turn.steps).filter((step) => step.status === 'succeeded').length
  useEffect(() => {
    if (!active) return
    const timer = window.setInterval(refreshTurns, 1000)
    return () => window.clearInterval(timer)
  }, [active, refreshTurns])
  useEffect(() => {
    refresh()
  }, [finishedSteps, active, refresh])

  async function upload(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file) return
    setBusy(true)
    setNotice(null)
    try {
      const asset = await api.upload(file)
      const created = await api.createSession(asset.id, file.name.replace(/\.[^.]+$/, ''))
      loadLists()
      navigate(`/s/${created.id}`)
    } catch (e) {
      setNotice(message(e))
    } finally {
      setBusy(false)
    }
  }

  async function startFrom(asset: Asset) {
    try {
      const created = await api.createSession(asset.id)
      loadLists()
      navigate(`/s/${created.id}`)
    } catch (e) {
      setNotice(message(e))
    }
  }

  const invoke = useCallback(
    async (tool: string, params: Params) => {
      if (!sessionId) return
      setBusy(true)
      setNotice(null)
      setRun(null)
      try {
        const { run: started, session } = await api.invoke(sessionId, tool, params)
        setDetail(session)
        setRun(started)
        if (!TERMINAL.includes(started.status)) {
          const done = await untilDone(started.id, setRun)
          setRun(done)
          if (done.status === 'failed') setNotice(done.error)
        } else if (started.status === 'failed') {
          setNotice(started.error)
        }
        await refresh()
      } catch (e) {
        setNotice(message(e))
      } finally {
        setBusy(false)
      }
    },
    [sessionId, refresh],
  )

  async function edit(action: () => Promise<SessionDetail>) {
    setBusy(true)
    setNotice(null)
    try {
      setDetail(await action())
      await refresh()
    } catch (e) {
      setNotice(message(e))
    } finally {
      setBusy(false)
    }
  }

  async function select(body: Record<string, unknown>) {
    if (!sessionId || !detail) return
    try {
      setSelection(await api.select(sessionId, { revision: detail.revision, ...body }))
    } catch (e) {
      setNotice(message(e))
      refresh()
    }
  }

  async function clearSelection() {
    if (!sessionId) return
    await api.clearSelection(sessionId).catch(() => undefined)
    setSelection(null)
  }

  const layerOptions = (detail?.document.layers ?? []).filter((layer) => layer.kind === 'image' || layer.kind === 'text')
    .map((layer) => ({ id: layer.id, name: layer.name }))

  return (
    <div className="workspace">
      <aside className="sidebar">
        <input ref={fileInput} type="file" accept="image/png,image/jpeg,image/webp" hidden onChange={upload} data-testid="upload-input" />
        <button className="primary block" onClick={() => fileInput.current?.click()} disabled={busy}>
          上传图片，新建会话
        </button>
        <h3>会话</h3>
        <ul className="sessions" data-testid="sessions">
          {sessions.map((item) => (
            <li key={item.id} className={item.id === sessionId ? 'active' : ''}>
              <a href={`#/s/${item.id}`}>
                <span>{item.title}</span>
                <small>{time(item.updated_at)}</small>
              </a>
            </li>
          ))}
          {sessions.length === 0 && <li className="hint">还没有会话</li>}
        </ul>
        {uploads.length > 0 && (
          <>
            <h3>最近上传</h3>
            <div className="uploads">
              {uploads.slice(0, 12).map((asset) => (
                <button key={asset.id} className="thumb-button" title="用这张图新建会话" onClick={() => startFrom(asset)}>
                  <img src={asset.url} alt="" loading="lazy" />
                </button>
              ))}
            </div>
          </>
        )}
      </aside>

      <main className="main">
        {!sessionId && (
          <div className="empty">
            <h2>上传一张商品图开始</h2>
            <p>可以在右侧用一句话让 Agent 规划修图步骤，也可以直接用工具面板。</p>
          </div>
        )}
        {sessionId && !detail && <div className="empty">加载中…</div>}
        {detail && (
          <>
            <div className="toolbar">
              <button onClick={() => edit(() => api.undo(detail.id))} disabled={busy || !detail.can_undo} data-testid="undo">
                撤销
              </button>
              <button onClick={() => edit(() => api.redo(detail.id))} disabled={busy || !detail.can_redo} data-testid="redo">
                重做
              </button>
              <span className="divider" />
              <span>选区：</span>
              {(['off', 'brush', 'point'] as SelectMode[]).map((value) => (
                <button key={value} className={mode === value ? 'toggle on' : 'toggle'} onClick={() => setMode(value)} data-testid={`mode-${value}`}>
                  {value === 'off' ? '不选' : value === 'brush' ? '笔刷' : '点选'}
                </button>
              ))}
              {mode === 'brush' && (
                <label className="inline-label">
                  粗细
                  <input type="range" min={0.01} max={0.12} step={0.005} value={brush} onChange={(e) => setBrush(Number(e.target.value))} />
                </label>
              )}
              <button onClick={clearSelection} disabled={!selection} data-testid="clear-selection">
                清除选区
              </button>
              <span className="spacer" />
              <span className="meta" data-testid="revision">
                修订 {detail.revision}
              </span>
            </div>
            <Canvas
              document={detail.document}
              urls={urls}
              selection={selection}
              mode={mode}
              brush={brush}
              activeLayerId={activeLayerId}
              onStroke={(points: Point[]) => select({ strokes: [points], radius: brush })}
              onPoint={(point: Point) => select({ points: [point], append: true })}
            />
          </>
        )}
        {notice && (
          <div className="notice" data-testid="notice">
            {notice}
            <button className="link" onClick={() => setNotice(null)}>
              知道了
            </button>
          </div>
        )}
      </main>

      {detail && sessionId && (
        <section className="panel">
          <div className="tabs">
            {TABS.map(([value, label]) => (
              <button key={value} className={tab === value ? 'active' : ''} onClick={() => setTab(value)} data-testid={`tab-${value}`}>
                {label}
                {value === 'wall' && detail.assets.length > 0 && <span className="count">{detail.assets.length}</span>}
              </button>
            ))}
          </div>
          <div className="panel-body">
            {tab === 'chat' && <ChatPanel sessionId={sessionId} turns={turns} tools={tools} hasSelection={!!selection} onChanged={refreshTurns} />}
            {tab === 'tools' && (
              <ToolPanel tools={tools} layers={layerOptions} activeLayerId={activeLayerId} hasSelection={!!selection} busy={busy} run={run} onRun={invoke} />
            )}
            {tab === 'layers' && (
              <LayersPanel layers={detail.document.layers} urls={urls} activeLayerId={activeLayerId} busy={busy} onSelect={setActiveLayerId} onTool={invoke} />
            )}
            {tab === 'wall' && (
              <WallPanel
                assets={detail.assets}
                currentAssetId={detail.current_asset_id}
                busy={busy}
                onUse={(asset) => edit(() => api.patchSession(detail.id, { current_asset_id: asset.id }))}
              />
            )}
            {tab === 'history' && <HistoryPanel entries={history} currentSeq={detail.history_seq} tools={tools} />}
          </div>
        </section>
      )}
    </div>
  )
}
