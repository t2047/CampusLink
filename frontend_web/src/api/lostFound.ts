import { apiClient } from './client'
import type {
  CreateReportInput,
  LostFoundClaim,
  LostFoundMetadata,
  LostFoundReport,
  PageResponse,
  UpdateReportInput,
} from '../types'

export type ReportSearchParams = Record<string, string | number | undefined>

export async function getMetadata(): Promise<LostFoundMetadata> {
  const response = await apiClient.get<LostFoundMetadata>('/lost-found/metadata')
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
