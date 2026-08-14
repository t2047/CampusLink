import { apiClient } from './client'
import type { AgentMatchResult } from './lostFoundAgent'
import type {
  CreateReportInput,
  ItemCategory,
  LostFoundClaim,
  LostFoundMetadata,
  LostFoundReport,
  PageResponse,
  ReportType,
  UpdateReportInput,
} from '../types'

export type ReportSearchParams = Record<string, string | number | undefined>

export interface AgentImageSearchInput {
  objectKey: string
  visualFingerprint: string
  url: string
}

export type AgentImageSearchStatus = 'match_found' | 'no_match' | 'failed'

/** Browse 以图搜物响应：与 Agent 面板同图结果逐字节一致（后端仅透传 Agent 打分）。 */
export interface AgentImageSearchResponse {
  status: AgentImageSearchStatus
  match_results: AgentMatchResult[]
  request_id: string
  message?: string | null
}

export interface AgentImageSearchRequest {
  reportType: ReportType
  keyword?: string
  category?: string
  colour?: string
  location?: string
  dateFrom?: string
  dateTo?: string
  images: AgentImageSearchInput[]
}

export async function getMetadata(): Promise<LostFoundMetadata> {
  const response = await apiClient.get<LostFoundMetadata>('/lost-found/metadata')
  return response.data
}

/** Browse 以图搜物：带视觉指纹 + 可选筛选调用 Agent 轻量搜索端点。 */
export async function searchByImage(
  request: AgentImageSearchRequest,
): Promise<AgentImageSearchResponse> {
  const response = await apiClient.post<AgentImageSearchResponse>(
    '/lost-found/agent/search',
    request,
  )
  return response.data
}

export async function searchReports(
  params: ReportSearchParams,
): Promise<PageResponse<LostFoundReport>> {
  const response = await apiClient.get<PageResponse<LostFoundReport>>('/lost-found/reports', {
    params,
  })
  return response.data
}

export async function getReport(id: string | number): Promise<LostFoundReport> {
  const response = await apiClient.get<LostFoundReport>(`/lost-found/reports/${id}`)
  return response.data
}

export async function createReport(
  input: CreateReportInput,
  images: File[],
  onProgress?: (percent: number) => void,
): Promise<LostFoundReport> {
  const form = new FormData()
  form.append('report', new Blob([JSON.stringify(input)], { type: 'application/json' }))
  images.forEach((image) => form.append('images', image))

  const response = await apiClient.post<LostFoundReport>('/lost-found/reports', form, {
    onUploadProgress: (event) => {
      if (event.total && onProgress) {
        onProgress(Math.round((event.loaded * 100) / event.total))
      }
    },
  })
  return response.data
}

export async function updateReport(
  reportId: number,
  input: UpdateReportInput,
  images: File[],
  onProgress?: (percent: number) => void,
): Promise<LostFoundReport> {
  const form = new FormData()
  form.append('report', new Blob([JSON.stringify(input)], { type: 'application/json' }))
  images.forEach((image) => form.append('images', image))

  const response = await apiClient.put<LostFoundReport>(`/lost-found/reports/${reportId}`, form, {
    onUploadProgress: (event) => {
      if (event.total && onProgress) {
        onProgress(Math.round((event.loaded * 100) / event.total))
      }
    },
  })
  return response.data
}

export async function closeReport(reportId: number): Promise<LostFoundReport> {
  const response = await apiClient.post<LostFoundReport>(`/lost-found/reports/${reportId}/close`)
  return response.data
}

export async function suggestCategory(itemName: string): Promise<ItemCategory | null> {
  const response = await apiClient.post<{ category: ItemCategory | null }>(
    '/lost-found/agent/classify',
    { itemName },
  )
  return response.data.category ?? null
}

export async function deleteReport(reportId: number): Promise<void> {
  await apiClient.delete(`/lost-found/reports/${reportId}`)
}

export async function submitClaim(
  reportId: number,
  proofDescription: string,
): Promise<LostFoundClaim> {
  const response = await apiClient.post<LostFoundClaim>(
    `/lost-found/reports/${reportId}/claims`,
    { proofDescription },
  )
  return response.data
}

export async function getMyClaims(): Promise<LostFoundClaim[]> {
  const response = await apiClient.get<LostFoundClaim[]>('/lost-found/claims/mine')
  return response.data
}

export async function getReceivedClaims(): Promise<LostFoundClaim[]> {
  const response = await apiClient.get<LostFoundClaim[]>('/lost-found/claims/received')
  return response.data
}

export async function decideClaim(
  claimId: number,
  decision: 'approve' | 'reject',
  decisionNote: string,
): Promise<LostFoundClaim> {
  const response = await apiClient.post<LostFoundClaim>(
    `/lost-found/claims/${claimId}/${decision}`,
    { decisionNote },
  )
  return response.data
}
