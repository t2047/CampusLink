import axios, { AxiosError } from 'axios'
import type { ApiErrorBody } from '../types'

export const TOKEN_KEY = 'campuslink.token'
export const USER_KEY = 'campuslink.user'

export const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080/api',
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
    return body?.error ?? body?.message ?? error.message
  }
  return error instanceof Error ? error.message : 'Something went wrong. Please try again.'
}
