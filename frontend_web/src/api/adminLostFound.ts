import { apiClient } from './client'
import type {
  AdminAuditLog,
  AdminClaimDecisionInput,
  AdminClaimDetail,
  AdminClaimRejectInput,
  AdminClaimSummary,
  AdminLostFoundOverview,
  AdminLostFoundReport,
  AuditAction,
  ClaimStatus,
  PageResponse,
} from '../types'

export type AdminReportSearchParams = Record<string, string | number | undefined>

export interface AdminClaimSearchParams {
  status?: ClaimStatus
  keyword?: string
  reportId?: number
  claimantEmail?: string
  reportOwnerEmail?: string
  adminHidden?: boolean
  page?: number
  size?: number
  sort?: string
}

export interface AdminAuditLogSearchParams {
  reportId?: number
  action?: AuditAction
  actorEmail?: string
  keyword?: string
  page?: number
  size?: number
  sort?: string
}

export async function getAdminLostFoundOverview(): Promise<AdminLostFoundOverview> {
  const response = await apiClient.get<AdminLostFoundOverview>('/admin/lost-found/overview')
  return response.data
}

export async function searchAdminLostFoundReports(
  params: AdminReportSearchParams,
): Promise<PageResponse<AdminLostFoundReport>> {
  const response = await apiClient.get<PageResponse<AdminLostFoundReport>>(
    '/admin/lost-found/reports',
    { params },
  )
  return response.data
}

export async function delistAdminReport(id: number, reason: string): Promise<AdminLostFoundReport> {
  const response = await apiClient.post<AdminLostFoundReport>(
    `/admin/lost-found/reports/${id}/delist`,
    { reason },
  )
  return response.data
}

export async function restoreAdminReport(id: number, reason: string): Promise<AdminLostFoundReport> {
  const response = await apiClient.post<AdminLostFoundReport>(
    `/admin/lost-found/reports/${id}/restore`,
    { reason },
  )
  return response.data
}

export async function deleteAdminReport(id: number, reason: string): Promise<void> {
  await apiClient.post(`/admin/lost-found/reports/${id}/delete`, { reason })
}

export async function searchAdminAuditLogs(
  params: AdminAuditLogSearchParams,
): Promise<PageResponse<AdminAuditLog>> {
  const response = await apiClient.get<PageResponse<AdminAuditLog>>(
    '/admin/lost-found/audit-logs',
    { params },
  )
  return response.data
}

export async function searchAdminClaims(
  params: AdminClaimSearchParams,
): Promise<PageResponse<AdminClaimSummary>> {
  const response = await apiClient.get<PageResponse<AdminClaimSummary>>(
    '/admin/lost-found/claims',
    { params },
  )
  return response.data
}

export async function getAdminClaimDetail(id: number): Promise<AdminClaimDetail> {
  const response = await apiClient.get<AdminClaimDetail>(`/admin/lost-found/claims/${id}`)
  return response.data
}

export async function approveAdminClaim(
  id: number,
  input: AdminClaimDecisionInput = {},
): Promise<AdminClaimDetail> {
  const response = await apiClient.post<AdminClaimDetail>(
    `/admin/lost-found/claims/${id}/approve`,
    input,
  )
  return response.data
}

export async function rejectAdminClaim(
  id: number,
  input: AdminClaimRejectInput,
): Promise<AdminClaimDetail> {
  const response = await apiClient.post<AdminClaimDetail>(
    `/admin/lost-found/claims/${id}/reject`,
    input,
  )
  return response.data
}
