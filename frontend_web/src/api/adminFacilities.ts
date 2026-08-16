import type {
  AdminFacilitiesOverview,
  AdminFacilityBooking,
  AdminFacilityBookingStatus,
  AdminFacilityMaintenance,
  AdminFacilityMaintenancePriority,
  AdminFacilityMaintenanceStatus,
  PageResponse,
} from '../types'
import { apiClient } from './client'

export interface AdminFacilityBookingSearchParams {
  status?: AdminFacilityBookingStatus
  spaceId?: number
  userId?: number
  userEmail?: string
  startFrom?: string
  startTo?: string
  page?: number
  size?: number
  sort?: string
}

export interface AdminFacilityMaintenanceSearchParams {
  statuses?: AdminFacilityMaintenanceStatus[]
  priority?: AdminFacilityMaintenancePriority
  spaceId?: number
  userId?: number
  userEmail?: string
  building?: string
  createdFrom?: string
  createdTo?: string
  page?: number
  size?: number
  sort?: string
}

export async function getAdminFacilitiesOverview(): Promise<AdminFacilitiesOverview> {
  const response = await apiClient.get<AdminFacilitiesOverview>('/admin/facilities/overview')
  return response.data
}

export async function searchAdminFacilityBookings(
  params: AdminFacilityBookingSearchParams,
): Promise<PageResponse<AdminFacilityBooking>> {
  const response = await apiClient.get<PageResponse<AdminFacilityBooking>>(
    '/admin/facilities/bookings/search',
    { params },
  )
  return response.data
}

export async function searchAdminFacilityMaintenance(
  params: AdminFacilityMaintenanceSearchParams,
): Promise<PageResponse<AdminFacilityMaintenance>> {
  const query = new URLSearchParams()
  params.statuses?.forEach((status) => query.append('status', status))

  const append = (key: string, value: string | number | undefined) => {
    if (value === undefined || String(value).trim() === '') return
    query.append(key, String(value).trim())
  }
  append('priority', params.priority)
  append('spaceId', params.spaceId)
  append('userId', params.userId)
  append('userEmail', params.userEmail)
  append('building', params.building)
  append('createdFrom', params.createdFrom)
  append('createdTo', params.createdTo)
  append('page', params.page)
  append('size', params.size)
  append('sort', params.sort)

  const response = await apiClient.get<PageResponse<AdminFacilityMaintenance>>(
    '/admin/facilities/maintenance/search',
    { params: query },
  )
  return response.data
}

export async function getAdminFacilityMaintenance(ticketId: number): Promise<AdminFacilityMaintenance> {
  const response = await apiClient.get<AdminFacilityMaintenance>(
    `/admin/facilities/maintenance/search/${ticketId}`,
  )
  return response.data
}
