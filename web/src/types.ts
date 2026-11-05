// 与 Java 服务的响应体一一对应（JSON 字段为 snake_case）

export type RunStatus = 'queued' | 'running' | 'succeeded' | 'failed' | 'canceled'
export type StepStatus = 'pending' | 'waiting' | RunStatus

export interface User {
  id: string
  username: string
}

export interface Asset {
  id: string
  kind: 'original' | 'generated' | 'subject' | 'background' | 'mask' | 'marketing' | 'export'
  source: 'upload' | 'generate' | 'tool'
  image_format: string
  width: number
  height: number
  size_bytes: number
  has_alpha: boolean
  created_at: string
  url: string
}

export interface Transform {
  x: number
  y: number
  scale_x: number
  scale_y: number
  rotation: number
}

export interface Layer {
  id: string
  kind: 'image' | 'text' | 'shape'
  name: string
  width: number
  height: number
  asset_id: string | null
  transform: Transform
  opacity: number
  visible: boolean
  locked: boolean
  source_hash: string | null
  text: string | null
  font_size: number
  fill: string | null
}

export interface LayerDocument {
  width: number
  height: number
  layers: Layer[]
}

export interface SessionSummary {
  id: string
  title: string
  revision: number
  history_seq: number
  original_asset_id: string
  current_asset_id: string
  created_at: string
  updated_at: string
}

export interface SessionDetail extends SessionSummary {
  document: LayerDocument
  previous_document: LayerDocument | null
  assets: Asset[]
  can_undo: boolean
  can_redo: boolean
}

export interface Run {
  id: string
  tool: string
  status: RunStatus
  progress: number
  stage: string | null
  error: string | null
  prompt?: string | null
  candidates?: Asset[]
  result?: Record<string, unknown> | null
}

export interface Step {
  id: string
  tool: string
  label: string
  depends_on: string[]
  run_id: string | null
  status: StepStatus
}

export interface Turn {
  id: string
  revision: number
  goal: string
  reply: string | null
  status: RunStatus
  error: string | null
  created_at: string
  steps: Step[]
}

export interface JsonSchema {
  type?: string
  description?: string
  enum?: string[]
  minimum?: number
  maximum?: number
  exclusiveMinimum?: number
  minLength?: number
  maxLength?: number
  minItems?: number
  maxItems?: number
  pattern?: string
  format?: string
  items?: JsonSchema
  properties?: Record<string, JsonSchema>
  required?: string[]
}

export interface ToolInfo {
  name: string
  label: string
  description: string
  execution: 'document' | 'image' | 'composite'
  session_required: boolean
  uses_mask: boolean
  needs_approval: boolean
  agent_hidden: string[]
  parameters: JsonSchema
}

export interface Selection {
  revision: number
  mask: Asset
  markers: { index: number; x: number; y: number }[]
}

export interface HistoryEntry {
  seq: number
  action: string
  params: Record<string, unknown> | null
  result: Record<string, unknown> | null
  created_at: string
}

export interface BatchItem {
  source: Asset | null
  status: 'succeeded' | 'failed'
  error: string | null
  outputs: Asset[]
}

export interface Batch {
  run: Run
  items: BatchItem[]
  created_at: string
}

export interface PlannerTrace {
  model?: string
  elapsed_ms?: number
  prompt_tokens?: number
  completion_tokens?: number
  finish_reason?: string
  rounds?: number
  tool_calls?: unknown[]
  rejected?: unknown[]
  failure?: string | null
}
