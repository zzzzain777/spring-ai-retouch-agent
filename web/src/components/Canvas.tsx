import { useEffect, useLayoutEffect, useRef, useState, type PointerEvent } from 'react'
import type { Layer, LayerDocument, Selection } from '../types'

export type SelectMode = 'off' | 'brush' | 'point'
export type Point = { x: number; y: number }

/**
 * 按画布文档渲染图层（与图像服务的拼平规则一致：先绕图层框中心缩放/镜像，再顺时针旋转，再乘不透明度；文字居中），
 * 上面叠一层选区：已提交的遮罩图，和正在涂的笔刷。坐标统一用 0–1 的画布比例提交给后端。
 */
export function Canvas({
  document: doc,
  urls,
  selection,
  mode,
  brush,
  activeLayerId,
  onStroke,
  onPoint,
}: {
  document: LayerDocument
  urls: Record<string, string>
  selection: Selection | null
  mode: SelectMode
  brush: number
  activeLayerId: string | null
  onStroke: (points: Point[]) => void
  onPoint: (point: Point) => void
}) {
  const frame = useRef<HTMLDivElement>(null)
  const overlay = useRef<HTMLCanvasElement>(null)
  const stroke = useRef<Point[] | null>(null)
  const [scale, setScale] = useState(1)

  useLayoutEffect(() => {
    const element = frame.current
    if (!element) return
    const fit = () => {
      const { width, height } = element.getBoundingClientRect()
      const next = Math.min((width - 32) / doc.width, (height - 32) / doc.height)
      setScale(Math.max(0.05, Math.min(next, 4)))
    }
    fit()
    const observer = new ResizeObserver(fit)
    observer.observe(element)
    return () => observer.disconnect()
  }, [doc.width, doc.height])

  useEffect(() => {
    const context = overlay.current?.getContext('2d')
    context?.clearRect(0, 0, doc.width, doc.height)
  }, [selection, doc.width, doc.height])

  const toPoint = (event: PointerEvent<HTMLCanvasElement>): Point => {
    const rect = event.currentTarget.getBoundingClientRect()
    const clamp = (v: number) => Math.min(1, Math.max(0, v))
    return { x: clamp((event.clientX - rect.left) / rect.width), y: clamp((event.clientY - rect.top) / rect.height) }
  }

  const draw = (points: Point[]) => {
    const context = overlay.current?.getContext('2d')
    if (!context || points.length === 0) return
    context.strokeStyle = 'rgba(37, 99, 235, 0.55)'
    context.fillStyle = 'rgba(37, 99, 235, 0.55)'
    context.lineWidth = brush * Math.min(doc.width, doc.height)
    context.lineCap = 'round'
    context.lineJoin = 'round'
    context.beginPath()
    points.forEach((p, i) => (i === 0 ? context.moveTo(p.x * doc.width, p.y * doc.height) : context.lineTo(p.x * doc.width, p.y * doc.height)))
    if (points.length === 1) {
      context.arc(points[0].x * doc.width, points[0].y * doc.height, context.lineWidth / 2, 0, Math.PI * 2)
      context.fill()
    } else {
      context.stroke()
    }
  }

  const down = (event: PointerEvent<HTMLCanvasElement>) => {
    if (mode === 'point') {
      onPoint(toPoint(event))
      return
    }
    if (mode !== 'brush') return
    event.currentTarget.setPointerCapture(event.pointerId)
    stroke.current = [toPoint(event)]
    draw(stroke.current)
  }
  const move = (event: PointerEvent<HTMLCanvasElement>) => {
    const points = stroke.current
    if (!points) return
    const p = toPoint(event)
    const last = points[points.length - 1]
    if (Math.hypot(p.x - last.x, p.y - last.y) < 0.004) return
    points.push(p)
    draw(points.slice(-2))
  }
  const up = () => {
    const points = stroke.current
    stroke.current = null
    if (points && points.length) onStroke(points.slice(0, 200))
  }

  return (
    <div className="canvas-frame" ref={frame}>
      <div className="stage-box" style={{ width: doc.width * scale, height: doc.height * scale }}>
        <div className="stage" data-testid="stage" style={{ width: doc.width, height: doc.height, transform: `scale(${scale})` }}>
          {doc.layers.filter((layer) => layer.visible).map((layer) => (
            <LayerView key={layer.id} layer={layer} url={layer.asset_id ? urls[layer.asset_id] : undefined} active={layer.id === activeLayerId} />
          ))}
          {selection && <img className="selection-mask" src={selection.mask.url} alt="" draggable={false} data-testid="selection-mask" />}
          <canvas
            ref={overlay}
            className={`overlay mode-${mode}`}
            width={doc.width}
            height={doc.height}
            onPointerDown={down}
            onPointerMove={move}
            onPointerUp={up}
            onPointerCancel={up}
            data-testid="selection-overlay"
          />
        </div>
      </div>
      <div className="canvas-meta">
        {doc.width} × {doc.height}，显示 {Math.round(scale * 100)}%
      </div>
    </div>
  )
}

function LayerView({ layer, url, active }: { layer: Layer; url?: string; active: boolean }) {
  const t = layer.transform
  const style = {
    left: t.x,
    top: t.y,
    width: layer.width,
    height: layer.height,
    opacity: layer.opacity,
    transform: `rotate(${t.rotation}deg) scale(${t.scale_x}, ${t.scale_y})`,
  }
  const className = `layer${active ? ' active' : ''}`
  if (layer.kind === 'text') {
    return (
      <div className={`${className} text-layer`} style={{ ...style, fontSize: layer.font_size, color: layer.fill ?? '#111' }} data-layer={layer.id}>
        {layer.text}
      </div>
    )
  }
  if (!url) return <div className={`${className} loading`} style={style} data-layer={layer.id} />
  return <img className={className} style={style} src={url} alt={layer.name} draggable={false} data-layer={layer.id} />
}
