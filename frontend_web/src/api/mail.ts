import { apiClient } from './client'
import type { MailFolder, MailMessage, MailPageResponse, SendMailInput } from '../types'

export interface MailSearchParams {
  folder?: MailFolder
  q?: string
  unread?: boolean
  starred?: boolean
  page?: number
  size?: number
}

export async function listMail(params: MailSearchParams): Promise<MailPageResponse> {
  const response = await apiClient.get<MailPageResponse>('/mail/messages', { params })
  return response.data
}

export async function getMailMessage(id: string): Promise<MailMessage> {
  const response = await apiClient.get<MailMessage>(`/mail/messages/${id}`)
  return response.data
}

export async function sendMail(input: SendMailInput): Promise<MailMessage> {
  const response = await apiClient.post<MailMessage>('/mail/messages', input)
  return response.data
}

export async function updateMail(
  id: string,
  patch: Partial<Pick<MailMessage, 'read' | 'starred' | 'folder'>>,
): Promise<MailMessage> {
  const response = await apiClient.patch<MailMessage>(`/mail/messages/${id}`, patch)
  return response.data
}

export async function archiveMail(id: string): Promise<MailMessage> {
  const response = await apiClient.post<MailMessage>(`/mail/messages/${id}/archive`)
  return response.data
}

export async function deleteMail(id: string): Promise<MailMessage> {
  const response = await apiClient.post<MailMessage>(`/mail/messages/${id}/delete`)
  return response.data
}

export interface MailOAuthStatus {
  connected: boolean
  email: string | null
}

export interface MailOAuthUrl {
  auth_url: string
  connected: boolean
}

export async function getMailOAuthStatus(): Promise<MailOAuthStatus> {
  const response = await apiClient.get<MailOAuthStatus>('/mail/oauth/status')
  return response.data
}

export async function getMailOAuthUrl(): Promise<MailOAuthUrl> {
  const response = await apiClient.get<MailOAuthUrl>('/mail/oauth/url')
  return response.data
}

export async function disconnectMail(): Promise<MailOAuthStatus> {
  const response = await apiClient.post<MailOAuthStatus>('/mail/oauth/disconnect')
  return response.data
}
