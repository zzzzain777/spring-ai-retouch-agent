import { useMemo, useState } from 'react'
import { STATUS_LABELS } from '../labels'
import type { Run, ToolInfo } from '../types'
import { SchemaForm, type LayerOption, type Params } from './SchemaForm'

/** 手动工具面板：工具列表与表单都来自 GET /api/tools，提交走与 Agent 相同的 POST /api/sessions/{id}/tools。 */
export function ToolPanel({
  tools,
  layers,
  activeLayerId,
  hasSelection,
  busy,
  run,
  onRun,
}: {
  tools: ToolInfo[]
  layers: LayerOption[]
  activeLayerId: string | null
  hasSelection: boolean
  busy: boolean
  run: Run | null
  onRun: (tool: string, params: Params) => void
}) {
  const available = useMemo(() => tools.filter((tool) => tool.session_required && tool.execution !== 'composite'), [tools])
  const [name, setName] = useState('adjust_image')
  const [params, setParams] = useState<Params>({})
  const tool = available.find((item) => item.name === name) ?? available[0]

  if (!tool) return <p className="hint">正在读取工具列表…</p>

  const choose = (next: string) => {
    setName(next)
    setParams({})
  }
  const submit = () => {
    const filled = { ...params }
    if (activeLayerId && tool.parameters.properties?.layer_id && filled.layer_id === undefined) filled.layer_id = activeLayerId
    onRun(tool.name, filled)
  }

  const groups: [string, ToolInfo[]][] = [
    ['图像处理（排队执行）', available.filter((item) => item.execution === 'image')],
    ['画布调整（立即生效）', available.filter((item) => item.execution === 'document')],
  ]

  return (
    <div className="tool-panel">
      <label className="field">
        <span className="field-label">工具</span>
        <select name="tool" value={tool.name} onChange={(e) => choose(e.target.value)} data-testid="tool-select">
          {groups.map(([title, items]) => (
            <optgroup key={title} label={title}>
              {items.map((item) => (
                <option key={item.name} value={item.name}>
                  {item.label}（{item.name}）
                </option>
              ))}
            </optgroup>
          ))}
        </select>
      </label>
      <p className="description">{tool.description}</p>
      {tool.uses_mask && (
        <p className={hasSelection ? 'note ok' : 'note'}>
          {hasSelection ? '会使用画布上的当前选区。' : '当前没有选区：会作用于整个图层。可以先在画布上涂抹选区。'}
        </p>
      )}
      <SchemaForm schema={tool.parameters} value={params} onChange={setParams} layers={layers} />
      {activeLayerId && tool.parameters.properties?.layer_id && params.layer_id === undefined && (
        <p className="hint">未选图层时使用图层面板里选中的「{activeLayerId}」。</p>
      )}
      <button className="primary" onClick={submit} disabled={busy} data-testid="run-tool">
        执行
      </button>
      {run && (
        <div className={`run-status ${run.status}`} data-testid="run-status">
          <div className="row">
            <span>{STATUS_LABELS[run.status] ?? run.status}</span>
            <span>{run.stage}</span>
            <span>{run.progress}%</span>
          </div>
          <progress max={100} value={run.progress} />
          {run.error && <p className="error">{run.error}</p>}
        </div>
      )}
    </div>
  )
}
