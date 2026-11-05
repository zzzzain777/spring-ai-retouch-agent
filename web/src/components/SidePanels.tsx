import { ASSET_KINDS, time } from '../labels'
import type { Asset, HistoryEntry, Layer, ToolInfo } from '../types'

/** 图层面板：显隐与顺序也是通过工具接口改（set_layer_visible、reorder_layer），和对话、工具面板同一条路径。 */
export function LayersPanel({
  layers,
  urls,
  activeLayerId,
  busy,
  onSelect,
  onTool,
}: {
  layers: Layer[]
  urls: Record<string, string>
  activeLayerId: string | null
  busy: boolean
  onSelect: (id: string | null) => void
  onTool: (tool: string, params: Record<string, unknown>) => void
}) {
  const topFirst = [...layers].reverse()
  return (
    <ul className="layers" data-testid="layers">
      {topFirst.map((layer) => (
        <li
          key={layer.id}
          className={layer.id === activeLayerId ? 'active' : ''}
          onClick={() => onSelect(layer.id === activeLayerId ? null : layer.id)}
          data-testid="layer-row"
          data-layer={layer.id}
        >
          <span className="thumb">
            {layer.kind === 'text' ? 'T' : layer.asset_id && urls[layer.asset_id] ? <img src={urls[layer.asset_id]} alt="" /> : null}
          </span>
          <span className="layer-text">
            <strong>{layer.name}</strong>
            <small>
              {layer.id} · {Math.round(layer.opacity * 100)}%{layer.locked ? ' · 锁定' : ''}
            </small>
          </span>
          <span className="layer-actions" onClick={(e) => e.stopPropagation()}>
            <button
              className="icon"
              title={layer.visible ? '隐藏' : '显示'}
              disabled={busy}
              onClick={() => onTool('set_layer_visible', { layer_id: layer.id, visible: !layer.visible })}
            >
              {layer.visible ? '👁' : '—'}
            </button>
            <button className="icon" title="上移" disabled={busy} onClick={() => onTool('reorder_layer', { layer_id: layer.id, place: 'up' })}>
              ↑
            </button>
            <button className="icon" title="下移" disabled={busy} onClick={() => onTool('reorder_layer', { layer_id: layer.id, place: 'down' })}>
              ↓
            </button>
          </span>
        </li>
      ))}
    </ul>
  )
}

/** 图片墙：会话里产出的所有图（候选、投放尺寸、营销图等），可以设为当前图或下载。 */
export function WallPanel({
  assets,
  currentAssetId,
  busy,
  onUse,
}: {
  assets: Asset[]
  currentAssetId: string
  busy: boolean
  onUse: (asset: Asset) => void
}) {
  if (assets.length === 0) return <p className="hint">还没有产出。</p>
  return (
    <div className="wall" data-testid="wall">
      {[...assets].reverse().map((asset) => (
        <figure key={asset.id} className={asset.id === currentAssetId ? 'current' : ''} data-kind={asset.kind}>
          <img src={asset.url} alt="" loading="lazy" />
          <figcaption>
            <span className="chip">{ASSET_KINDS[asset.kind] ?? asset.kind}</span>
            <span>
              {asset.width}×{asset.height}
            </span>
          </figcaption>
          <div className="figure-actions">
            {asset.id === currentAssetId ? (
              <span className="hint">当前图</span>
            ) : (
              <button className="small" disabled={busy} onClick={() => onUse(asset)}>
                设为当前图
              </button>
            )}
            <a className="small" href={asset.url} target="_blank" rel="noreferrer">
              下载
            </a>
          </div>
        </figure>
      ))}
    </div>
  )
}

export function HistoryPanel({ entries, currentSeq, tools }: { entries: HistoryEntry[]; currentSeq: number; tools: ToolInfo[] }) {
  const labels: Record<string, string> = {
    ...Object.fromEntries(tools.map((tool) => [tool.name, tool.label])),
    create_session: '新建会话',
    switch_current: '切换当前图',
  }
  if (entries.length === 0) return <p className="hint">还没有历史。</p>
  return (
    <ol className="history" data-testid="history">
      {entries.map((entry) => (
        <li key={entry.seq} className={entry.seq === currentSeq ? 'current' : entry.seq > currentSeq ? 'undone' : ''}>
          <span>#{entry.seq}</span>
          <span>{labels[entry.action] ?? entry.action}</span>
          <small>{time(entry.created_at)}</small>
        </li>
      ))}
    </ol>
  )
}
