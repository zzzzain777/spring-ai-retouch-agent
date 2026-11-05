import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// 前端只和 Java 服务说话：/api 与 /events（SSE）都转发到 7302，浏览器端是同源请求，登录 Cookie 照常携带
const api = process.env.RETOUCH_API ?? 'http://127.0.0.1:7302'
const proxy = { '/api': api, '/events': api }

export default defineConfig({
  plugins: [react()],
  server: { host: '127.0.0.1', port: 7301, strictPort: true, proxy },
  preview: { host: '127.0.0.1', port: 7301, strictPort: true, proxy },
})
