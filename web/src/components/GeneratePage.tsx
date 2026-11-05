import { useState } from 'react'
import { api, message, untilDone } from '../api'
import { STATUS_LABELS } from '../labels'
import { navigate } from '../nav'
import type { Asset, Run } from '../types'

const RATIOS = ['1:1', '4:5', '3:4', '9:16', '16:9']

/** 文生图：POST /api/generations 建一个 generate_image 任务，候选图在任务结果里，选一张开始修图。 */
export function GeneratePage() {
  const [prompt, setPrompt] = useState('')
  const [negative, setNegative] = useState('')
  const [ratio, setRatio] = useState('1:1')
  const [count, setCount] = useState(4)
  const [run, setRun] = useState<Run | null>(null)
  const [candidates, setCandidates] = useState<Asset[]>([])
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit() {
    setBusy(true)
    setError(null)
    setCandidates([])
    try {
      const body: Record<string, unknown> = { prompt, ratio, count }
      if (negative.trim()) body.negative_prompt = negative
      const started = await api.generate(body)
      setRun(started)
      const done = await untilDone(started.id, setRun)
      setRun(done)
      setCandidates(done.candidates ?? [])
      if (done.status === 'failed') setError(done.error)
    } catch (e) {
      setError(message(e))
    } finally {
      setBusy(false)
    }
  }

  async function start(asset: Asset) {
    try {
      const session = await api.createSession(asset.id, prompt.slice(0, 30))
      navigate(`/s/${session.id}`)
    } catch (e) {
      setError(message(e))
    }
  }

  return (
    <div className="page">
      <div className="card form-card">
        <h2>文生图</h2>
        <p className="hint">需要图像模型（DashScope）。没有配置 DASHSCOPE_API_KEY 时任务会直接失败并说明原因。</p>
        <label className="field">
          <span className="field-label">
            描述<em>*</em>
          </span>
          <textarea name="prompt" rows={4} maxLength={1500} value={prompt} onChange={(e) => setPrompt(e.target.value)} />
        </label>
        <label className="field">
          <span className="field-label">不要出现</span>
          <input name="negative_prompt" maxLength={1500} value={negative} onChange={(e) => setNegative(e.target.value)} />
        </label>
        <div className="inline">
          <label className="field">
            <span className="field-label">比例</span>
            <select name="ratio" value={ratio} onChange={(e) => setRatio(e.target.value)}>
              {RATIOS.map((value) => (
                <option key={value}>{value}</option>
              ))}
            </select>
          </label>
          <label className="field">
            <span className="field-label">张数</span>
            <input name="count" type="number" min={1} max={6} value={count} onChange={(e) => setCount(Number(e.target.value))} />
          </label>
        </div>
        <button className="primary" onClick={submit} disabled={busy || !prompt.trim()} data-testid="generate">
          生成
        </button>
        {run && (
          <div className={`run-status ${run.status}`} data-testid="run-status">
            <div className="row">
              <span>{STATUS_LABELS[run.status] ?? run.status}</span>
              <span>{run.stage}</span>
              <span>{run.progress}%</span>
            </div>
            <progress max={100} value={run.progress} />
          </div>
        )}
        {error && <p className="error">{error}</p>}
      </div>
      {candidates.length > 0 && (
        <div className="wall wide" data-testid="candidates">
          {candidates.map((asset) => (
            <figure key={asset.id}>
              <img src={asset.url} alt="" />
              <figcaption>
                {asset.width}×{asset.height}
              </figcaption>
              <div className="figure-actions">
                <button className="small primary" onClick={() => start(asset)}>
                  用这张开始修图
                </button>
              </div>
            </figure>
          ))}
        </div>
      )}
    </div>
  )
}
