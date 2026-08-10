import { apiClient } from './client'
import type {
  AdminLostFoundOverview,
  AdminLostFoundReport,
  PageResponse,
} from '../types'

export type AdminReportSearchParams = Record<string, string | number | undefined>

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
