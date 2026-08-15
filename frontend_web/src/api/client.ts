import axios, { AxiosError } from 'axios'
import type { ApiErrorBody } from '../types'

export const TOKEN_KEY = 'campuslink.token'
export const USER_KEY = 'campuslink.user'

// API 基址统一（与 services/api.ts 一致）：读 VITE_API_BASE（后端根地址，可选）。
// 默认同源相对 /api —— dev 由 vite proxy 转发到 8080；部署时设
// VITE_API_BASE=http://backend 即走完整地址（需后端 CORS 放行该 origin）。
const API_BASE = import.meta.env.VITE_API_BASE ?? ''

export const apiClient = axios.create({
  baseURL: `${API_BASE}/api`,
  timeout: 20_000,
})

apiClient.interceptors.request.use((config) => {
  const token = sessionStorage.getItem(TOKEN_KEY)
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

apiClient.interceptors.response.use(
  (response) => response,
  (error: AxiosError) => {
    if (error.response?.status === 401) {
      sessionStorage.removeItem(TOKEN_KEY)
      sessionStorage.removeItem(USER_KEY)
      if (window.location.pathname !== '/login') {
        window.location.assign('/login')
      }
    }
    return Promise.reject(error)
  },
)

export function apiErrorMessage(error: unknown): string {
  if (axios.isAxiosError<ApiErrorBody>(error)) {
    const body = error.response?.data
    if (body?.fieldErrors) {
      return Object.values(body.fieldErrors).join(' ')
    }
    if (body?.errors) {
      return Object.values(body.errors).join(' ')
    }
    return body?.error ?? body?.message ?? error.message
  }
  return error instanceof Error ? error.message : 'Something went wrong. Please try again.'
}
