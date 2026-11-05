import { useCallback, useEffect, useState } from 'react'
import { ApiError, api, message } from './api'
import { BatchPage } from './components/BatchPage'
import { GeneratePage } from './components/GeneratePage'
import { Login } from './components/Login'
import { Workspace } from './components/Workspace'
import { navigate } from './nav'
import type { ToolInfo, User } from './types'

/** 极简的 hash 路由：#/s/{会话 id}、#/generate、#/batch。 */
type Route = { page: 'edit'; sessionId: string | null } | { page: 'generate' } | { page: 'batch' }

function parse(hash: string): Route {
  const path = hash.replace(/^#/, '')
  if (path.startsWith('/generate')) return { page: 'generate' }
  if (path.startsWith('/batch')) return { page: 'batch' }
  const match = path.match(/^\/s\/([0-9a-f-]{36})/)
  return { page: 'edit', sessionId: match ? match[1] : null }
}

export function App() {
  const [user, setUser] = useState<User | null | undefined>(undefined)
  const [tools, setTools] = useState<ToolInfo[]>([])
  const [route, setRoute] = useState<Route>(() => parse(window.location.hash))
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const onHash = () => setRoute(parse(window.location.hash))
    window.addEventListener('hashchange', onHash)
    return () => window.removeEventListener('hashchange', onHash)
  }, [])

  useEffect(() => {
    api.me().then(setUser, (e) => {
      if (e instanceof ApiError && e.status === 401) setUser(null)
      else setError(message(e))
    })
  }, [])

  useEffect(() => {
    if (user) api.tools().then(setTools, (e) => setError(message(e)))
  }, [user])

  const logout = useCallback(async () => {
    await api.logout().catch(() => undefined)
    setUser(null)
    navigate('/')
  }, [])

  if (error && user === undefined) return <div className="boot">无法连接服务：{error}</div>
  if (user === undefined) return <div className="boot">加载中…</div>
  if (user === null) return <Login onLogin={setUser} />

  return (
    <div className="app">
      <header className="topbar">
        <span className="brand">修图 Agent</span>
        <nav>
          <a className={route.page === 'edit' ? 'active' : ''} href="#/">
            修图
          </a>
          <a className={route.page === 'generate' ? 'active' : ''} href="#/generate">
            文生图
          </a>
          <a className={route.page === 'batch' ? 'active' : ''} href="#/batch">
            批量处理
          </a>
        </nav>
        <span className="spacer" />
        <span className="user" data-testid="current-user">
          {user.username}
        </span>
        <button className="link" onClick={logout}>
          退出
        </button>
      </header>
      {route.page === 'edit' && <Workspace sessionId={route.sessionId} tools={tools} />}
      {route.page === 'generate' && <GeneratePage />}
      {route.page === 'batch' && <BatchPage tools={tools} />}
    </div>
  )
}
