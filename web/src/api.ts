import type {
  Asset,
  Batch,
  HistoryEntry,
  PlannerTrace,
  Run,
  RunStatus,
  SessionDetail,
  SessionSummary,
  Selection,
  ToolInfo,
  Turn,
  User,
} from './types'

/** Java 服务返回的错误：status 是 HTTP 状态码，message 是可直接展示的中文原因（响应体的 detail）。 */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message)
  }
}

async function request<T>(method: string, path: string, body?: unknown, headers: Record<string, string> = {}): Promise<T> {
  const init: RequestInit = { method, credentials: 'same-origin', headers: { ...headers } }
  if (body instanceof FormData) {
    init.body = body
  } else if (body !== undefined) {
    init.body = JSON.stringify(body)
    ;(init.headers as Record<string, string>)['Content-Type'] = 'application/json'
  }
  const response = await fetch(path, init)
  const text = await response.text()
  let data: unknown = null
  if (text) {
    try {
      data = JSON.parse(text)
    } catch {
      data = text
    }
  }
  if (!response.ok) {
    const detail = data && typeof data === 'object' && 'detail' in data ? String((data as { detail: unknown }).detail) : null
    throw new ApiError(response.status, detail ?? `请求失败（${response.status}）`)
  }
  return data as T
}

export const TERMINAL: RunStatus[] = ['succeeded', 'failed', 'canceled']

export const api = {
  me: () => request<User>('GET', '/api/auth/me'),
  login: (username: string, password: string) => request<User>('POST', '/api/auth/login', { username, password }),
  register: (username: string, password: string) => request<User>('POST', '/api/auth/register', { username, password }),
  logout: () => request<void>('POST', '/api/auth/logout'),

  upload: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return request<Asset>('POST', '/api/assets', form)
  },
  recentAssets: (limit = 60) => request<Asset[]>('GET', `/api/assets?limit=${limit}`),
  asset: (id: string) => request<Asset>('GET', `/api/assets/${id}`),

  sessions: () => request<SessionSummary[]>('GET', '/api/sessions?limit=50'),
  createSession: (currentAssetId: string, title?: string) =>
    request<SessionDetail>('POST', '/api/sessions', { current_asset_id: currentAssetId, title }),
  session: (id: string) => request<SessionDetail>('GET', `/api/sessions/${id}`),
  patchSession: (id: string, patch: { title?: string; current_asset_id?: string }) =>
    request<SessionDetail>('PATCH', `/api/sessions/${id}`, patch),
  undo: (id: string) => request<SessionDetail>('POST', `/api/sessions/${id}/undo`),
  redo: (id: string) => request<SessionDetail>('POST', `/api/sessions/${id}/redo`),
  history: (id: string) => request<HistoryEntry[]>('GET', `/api/sessions/${id}/history`),

  tools: () => request<ToolInfo[]>('GET', '/api/tools'),
  invoke: (sessionId: string, tool: string, params: Record<string, unknown>) =>
    request<{ run: Run; session: SessionDetail }>('POST', `/api/sessions/${sessionId}/tools`, { tool, params }),
  run: (id: string) => request<Run>('GET', `/api/runs/${id}`),

  selection: (sessionId: string) => request<Selection | null>('GET', `/api/sessions/${sessionId}/selection`),
  select: (sessionId: string, body: Record<string, unknown>) =>
    request<Selection>('POST', `/api/sessions/${sessionId}/selection`, body),
  clearSelection: (sessionId: string) => request<void>('DELETE', `/api/sessions/${sessionId}/selection`),

  turns: (sessionId: string) => request<Turn[]>('GET', `/api/sessions/${sessionId}/messages`),
  send: (sessionId: string, text: string, idempotencyKey: string) =>
    request<Turn>('POST', `/api/sessions/${sessionId}/messages`, { text }, { 'Idempotency-Key': idempotencyKey }),
  confirm: (sessionId: string, turnId: string) => request<Turn>('POST', `/api/sessions/${sessionId}/messages/${turnId}/confirm`),
  cancel: (sessionId: string, turnId: string) => request<Turn>('POST', `/api/sessions/${sessionId}/messages/${turnId}/cancel`),
  retry: (sessionId: string, turnId: string) => request<Turn>('POST', `/api/sessions/${sessionId}/messages/${turnId}/retry`),
  trace: (sessionId: string, turnId: string) =>
    request<PlannerTrace>('GET', `/api/sessions/${sessionId}/messages/${turnId}/trace`),

  generate: (body: Record<string, unknown>) => request<Run>('POST', '/api/generations', body),
  createBatch: (body: Record<string, unknown>) => request<Run>('POST', '/api/batches', body),
  batches: () => request<Batch[]>('GET', '/api/batches?limit=20'),
  batch: (id: string) => request<Batch>('GET', `/api/batches/${id}`),
}

/**
 * 订阅任务进度（SSE，帧格式 `data: {json}`）。服务端先发库里的快照、再转发 Redis 事件，终态后由这里关闭连接。
 * 返回取消订阅的函数。
 */
export function watchRun(runId: string, onFrame: (run: Run) => void): () => void {
  const source = new EventSource(`/events/runs/${runId}`)
  source.onmessage = (event) => {
    try {
      const frame = JSON.parse(event.data) as Run
      onFrame(frame)
      if (TERMINAL.includes(frame.status)) {
        source.close()
      }
    } catch {
      // 心跳等非 JSON 帧忽略
    }
  }
  return () => source.close()
}

/** 等任务结束：SSE 推进度，结束后再读一次完整结果（含候选图）。 */
export function untilDone(runId: string, onProgress: (run: Run) => void): Promise<Run> {
  return new Promise((resolve) => {
    let finished = false
    const stop = watchRun(runId, (frame) => {
      onProgress(frame)
      if (!finished && TERMINAL.includes(frame.status)) {
        finished = true
        stop()
        api.run(runId).then(resolve, () => resolve(frame))
      }
    })
  })
}

export function message(error: unknown): string {
  if (error instanceof ApiError) return error.message
  if (error instanceof Error) return error.message
  return String(error)
}
