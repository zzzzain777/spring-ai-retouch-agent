import { useState, type FormEvent } from 'react'
import { api, message } from '../api'
import type { User } from '../types'

export function Login({ onLogin }: { onLogin: (user: User) => void }) {
  const [mode, setMode] = useState<'login' | 'register'>('login')
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const user = mode === 'login' ? await api.login(username, password) : await api.register(username, password)
      onLogin(user)
    } catch (e) {
      setError(message(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="login">
      <form className="card" onSubmit={submit}>
        <h1>修图 Agent</h1>
        <div className="tabs">
          <button type="button" className={mode === 'login' ? 'active' : ''} onClick={() => setMode('login')}>
            登录
          </button>
          <button type="button" className={mode === 'register' ? 'active' : ''} onClick={() => setMode('register')}>
            注册
          </button>
        </div>
        <label>
          用户名
          <input name="username" autoComplete="username" value={username} onChange={(e) => setUsername(e.target.value)} />
        </label>
        <label>
          密码
          <input
            name="password"
            type="password"
            autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </label>
        {mode === 'register' && <p className="hint">用户名 3–32 位字母、数字或下划线；密码至少 6 位。</p>}
        {error && <p className="error">{error}</p>}
        <button className="primary" type="submit" disabled={busy}>
          {mode === 'login' ? '登录' : '注册并登录'}
        </button>
      </form>
    </div>
  )
}
