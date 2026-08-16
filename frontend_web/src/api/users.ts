import { apiClient } from './client'
import type { UserProfile } from '../types'

export async function getMyProfile(): Promise<UserProfile> {
  const response = await apiClient.get<UserProfile>('/users/me/profile')
  return response.data
}

export async function updateNickname(nickname: string): Promise<UserProfile> {
  const response = await apiClient.put<UserProfile>('/users/me/profile', { nickname })
  return response.data
}

export async function uploadAvatar(file: File): Promise<UserProfile> {
  const form = new FormData()
  form.append('file', file)
  const response = await apiClient.post<UserProfile>('/users/me/avatar', form)
  return response.data
}
