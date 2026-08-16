import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // Chat Core 前端走同源相对路径（/api → 后端），由 vite 代理避免 CORS；
    // Admin/Lost&Found 的 axios client 使用绝对地址，不受此影响
    proxy: {
      '/api/mail': {
        target: 'http://localhost:5000',
        changeOrigin: true,
      },
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    // jsdom 27 需要非 opaque origin 才会提供 localStorage；应用的 Chat 页面
    // 使用 localStorage 保存会话、语言和主题，因此测试环境固定一个本地 URL。
    environmentOptions: {
      jsdom: {
        url: 'http://localhost/',
      },
    },
    setupFiles: './src/test/setup.ts',
  },
})
