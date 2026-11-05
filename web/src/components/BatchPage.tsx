import { useEffect, useMemo, useState } from 'react'
import { api, message, untilDone } from '../api'
import { STATUS_LABELS, time } from '../labels'
import type { Asset, Batch, Run, ToolInfo } from '../types'
import { SchemaForm, type Params } from './SchemaForm'

const OPERATIONS = ['remove_background', 'adjust_image', 'prepare_delivery_sizes', 'replace_background', 'upscale_image', 'expand_canvas']

type Op = { tool: string; params: Params }

/**
 * 批量处理：选多张图、排一组操作（与单张工具同一份参数规则），按格式导出并打包。
 * 每个操作的参数表单也来自 GET /api/tools；整批参数由 Java 在建任务前逐项校验。
 */
export function BatchPage({ tools }: { tools: ToolInfo[] }) {
  const [assets, setAssets] = useState<Asset[]>([])
  const [picked, setPicked] = useState<string[]>([])
  const [ops, setOps] = useState<Op[]>([{ tool: 'remove_background', params: {} }])
  const [formats, setFormats] = useState<string[]>(['png'])
  const [run, setRun] = useState<Run | null>(null)
  const [batches, setBatches] = useState<Batch[]>([])
  const [shown, setShown] = useState<Batch | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const byName = useMemo(() => Object.fromEntries(tools.map((tool) => [tool.name, tool])), [tools])

  const loadBatches = () => api.batches().then(setBatches, () => undefined)
  useEffect(() => {
    // 上传的原图排在前面，其余是各会话产出的图（遮罩不参与）
    api.recentAssets(100).then((list) => {
      const usable = list.filter((asset) => asset.kind !== 'mask')
      setAssets([...usable.filter((asset) => asset.source === 'upload'), ...usable.filter((asset) => asset.source !== 'upload')])
    }, () => undefined)
    loadBatches()
  }, [])

  const toggle = (id: string) => setPicked((current) => (current.includes(id) ? current.filter((item) => item !== id) : [...current, id].slice(0, 20)))
  const setOp = (index: number, op: Op) => setOps((current) => current.map((item, i) => (i === index ? op : item)))

  async function submit() {
    setBusy(true)
    setError(null)
    setShown(null)
    try {
      const started = await api.createBatch({ asset_ids: picked, operations: ops, formats })
      setRun(started)
      const done = await untilDone(started.id, setRun)
      setRun(done)
      if (done.status === 'failed') setError(done.error)
      setShown(await api.batch(started.id))
      loadBatches()
    } catch (e) {
      setError(message(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="page batch">
      <div className="card form-card">
        <h2>批量处理</h2>
        <h4>1. 选图（{picked.length} / 20）</h4>
        <div className="picker" data-testid="batch-picker">
          {assets.map((asset) => (
            <button key={asset.id} className={picked.includes(asset.id) ? 'thumb-button picked' : 'thumb-button'} onClick={() => toggle(asset.id)} data-asset={asset.id}>
              <img src={asset.url} alt="" loading="lazy" />
            </button>
          ))}
          {assets.length === 0 && <p className="hint">先在“修图”页上传几张图。</p>}
        </div>
        <h4>2. 操作（按顺序执行，最多 6 个）</h4>
        {ops.map((op, index) => (
          <div className="op" key={index} data-testid="batch-op">
            <div className="field-row">
              <span>{index + 1}.</span>
              <select value={op.tool} onChange={(e) => setOp(index, { tool: e.target.value, params: {} })}>
                {OPERATIONS.map((name) => (
                  <option key={name} value={name}>
                    {byName[name]?.label ?? name}
                  </option>
                ))}
              </select>
              <button className="link" onClick={() => setOps((current) => current.filter((_, i) => i !== index))} disabled={ops.length === 1}>
                删除
              </button>
            </div>
            {byName[op.tool] && (
              <SchemaForm schema={byName[op.tool].parameters} value={op.params} onChange={(params) => setOp(index, { ...op, params })} omit={['layer_id']} />
            )}
          </div>
        ))}
        <button className="small" onClick={() => setOps((current) => [...current, { tool: 'prepare_delivery_sizes', params: {} }])} disabled={ops.length >= 6}>
          添加操作
        </button>
        <h4>3. 导出格式</h4>
        <div className="choices" data-testid="formats">
          {['png', 'jpg'].map((format) => (
            <label key={format} className="choice">
              <input
                type="checkbox"
                checked={formats.includes(format)}
                onChange={() => setFormats((current) => (current.includes(format) ? current.filter((f) => f !== format) : [...current, format]))}
              />
              {format.toUpperCase()}
            </label>
          ))}
        </div>
        <button className="primary" onClick={submit} disabled={busy || picked.length === 0 || formats.length === 0} data-testid="batch-submit">
          开始批量处理
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

      <div className="card">
        <h3>结果</h3>
        {shown ? <BatchResult batch={shown} /> : <p className="hint">选一条历史记录或开始一个新任务。</p>}
        <h3>最近的批量任务</h3>
        <ul className="batches">
          {batches.map((batch) => (
            <li key={batch.run.id}>
              <button className="link" onClick={() => setShown(batch)}>
                {time(batch.created_at)} · {batch.items.length} 张 · {STATUS_LABELS[batch.run.status] ?? batch.run.status}
              </button>
            </li>
          ))}
        </ul>
      </div>
    </div>
  )
}

function BatchResult({ batch }: { batch: Batch }) {
  const outputs = batch.items.reduce((sum, item) => sum + item.outputs.length, 0)
  return (
    <div data-testid="batch-result">
      <p>
        {batch.items.filter((item) => item.status === 'succeeded').length} / {batch.items.length} 张成功，共 {outputs} 个文件。
        {outputs > 0 && (
          <a className="button primary small" href={`/api/batches/${batch.run.id}/export`} download data-testid="batch-export">
            下载 zip
          </a>
        )}
      </p>
      <table className="items">
        <tbody>
          {batch.items.map((item, index) => (
            <tr key={index}>
              <td>{item.source && <img className="mini-thumb" src={item.source.url} alt="" />}</td>
              <td>{STATUS_LABELS[item.status] ?? item.status}</td>
              <td>
                {item.error && <span className="error">{item.error}</span>}
                <div className="outputs">
                  {item.outputs.map((output) => (
                    <a key={output.id} href={output.url} target="_blank" rel="noreferrer" title={`${output.width}×${output.height} ${output.image_format}`}>
                      <img className="mini-thumb" src={output.url} alt="" />
                    </a>
                  ))}
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
