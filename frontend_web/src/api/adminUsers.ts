import { apiClient } from './client'
import type { AuthResponse } from '../types'

export type UserRole = 'STUDENT' | 'ADMIN' | 'SUPER_ADMIN'

export interface AdminUser {
  id: number
  email: string
  role: UserRole
}

export interface CreateAdminUserInput {
  email: string
  password: string
  role: 'STUDENT' | 'ADMIN'
}

export async function listAdminUsers(): Promise<AdminUser[]> {
  const response = await apiClient.get<AdminUser[]>('/admin/users')
  return response.data
}

export async function createAdminUser(input: CreateAdminUserInput): Promise<AuthResponse> {
  const response = await apiClient.post<AuthResponse>('/admin/users', input)
  return response.data
}

export async function updateUserRole(id: number, role: UserRole): Promise<AdminUser> {
  const response = await apiClient.put<AdminUser>(`/admin/users/${id}/role`, { role })
  return response.data
}
