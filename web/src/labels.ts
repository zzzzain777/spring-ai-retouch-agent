// 界面文案。工具名、参数名与后端一致，这里只负责显示成中文。

export const PARAM_LABELS: Record<string, string> = {
  layer_id: '图层',
  prompt: '描述',
  negative_prompt: '不要出现',
  count: '张数',
  ratio: '比例',
  ratios: '比例',
  scale: '放大倍数',
  direction: '方向',
  opacity: '不透明度',
  visible: '显示',
  text: '文字',
  font_size: '字号',
  fill: '颜色',
  place: '位置',
  factor: '等比缩放',
  scale_x: '横向缩放',
  scale_y: '纵向缩放',
  angle: '再旋转（度）',
  rotation: '旋转到（度）',
  x: 'X',
  y: 'Y',
  dx: '水平移动',
  dy: '垂直移动',
  kind: '类型',
  caption: '标题或要求',
  include_text: '同时拆出文字层',
  name: '新图层名',
  rect: '裁剪框（占画布的比例）',
  width: '宽',
  height: '高',
  brightness: '亮度',
  contrast: '对比度',
  highlights: '高光',
  shadows: '阴影',
  temperature: '色温',
  tint: '色调',
  saturation: '饱和度',
  vibrance: '自然饱和度',
  sharpness: '锐度',
  clarity: '清晰度',
  vignette: '暗角',
}

export const ENUM_LABELS: Record<string, string> = {
  horizontal: '水平',
  vertical: '垂直',
  top: '置顶',
  bottom: '置底',
  up: '上移一层',
  down: '下移一层',
  product: '白底主图',
  scene: '场景图',
  model: '模特图',
  poster: '促销海报',
  png: 'PNG',
  jpg: 'JPG',
}

export const ASSET_KINDS: Record<string, string> = {
  original: '原图',
  generated: '生成',
  subject: '主体',
  background: '背景',
  mask: '遮罩',
  marketing: '营销图',
  export: '导出',
}

export const STATUS_LABELS: Record<string, string> = {
  pending: '待执行',
  waiting: '待确认',
  queued: '排队中',
  running: '执行中',
  succeeded: '已完成',
  failed: '失败',
  canceled: '已取消',
}

export function time(iso: string): string {
  const date = new Date(iso)
  return date.toLocaleString('zh-CN', { hour12: false, month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}
