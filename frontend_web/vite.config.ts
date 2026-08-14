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
    setupFiles: './src/test/setup.ts',
  },
})
