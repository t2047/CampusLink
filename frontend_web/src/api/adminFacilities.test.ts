import { afterEach, describe, expect, it, vi } from 'vitest'
import type {
  AdminFacilitiesOverview,
  AdminFacilityBooking,
  AdminFacilityMaintenance,
  PageResponse,
} from '../types'
import {
  getAdminFacilitiesOverview,
  getAdminFacilityMaintenance,
  searchAdminFacilityBookings,
  searchAdminFacilityMaintenance,
} from './adminFacilities'
import { apiClient } from './client'

const overviewFixture: AdminFacilitiesOverview = {
  summary: {
    totalSpaces: 12,
    availableSpaces: 8,
    outOfServiceSpaces: 3,
    inactiveSpaces: 1,
    totalBookings: 20,
    confirmedBookings: 9,
    cancelledBookings: 4,
    completedBookings: 7,
    totalMaintenanceRequests: 14,
    submittedMaintenanceRequests: 5,
    inProgressMaintenanceRequests: 3,
    resolvedMaintenanceRequests: 4,
    cancelledMaintenanceRequests: 2,
    openMaintenanceRequests: 8,
  },
  spaceStatusBreakdown: [
    { status: 'AVAILABLE', count: 8 },
    { status: 'OUT_OF_SERVICE', count: 3 },
    { status: 'INACTIVE', count: 1 },
  ],
  bookingStatusBreakdown: [
    { status: 'CONFIRMED', count: 9 },
    { status: 'CANCELLED', count: 4 },
    { status: 'COMPLETED', count: 7 },
  ],
  maintenanceStatusBreakdown: [
    { status: 'SUBMITTED', count: 5 },
    { status: 'IN_PROGRESS', count: 3 },
    { status: 'RESOLVED', count: 4 },
    { status: 'CANCELLED', count: 2 },
  ],
}

const bookingFixture: AdminFacilityBooking = {
  bookingId: 1,
  userId: 10,
  userEmail: 'student@nus.edu.sg',
  spaceId: 5,
  spaceName: 'Seminar Room 2',
  building: 'COM3',
  floor: '2',
  roomNumber: '02-10',
  spaceType: 'SEMINAR_ROOM',
  startDateTime: '2026-08-16T10:00:00',
  endDateTime: '2026-08-16T11:00:00',
  status: 'CONFIRMED',
  createdAt: '2026-08-15T10:00:00',
  updatedAt: '2026-08-15T10:00:00',
}

const bookingsPage: PageResponse<AdminFacilityBooking> = {
  content: [bookingFixture],
  page: 0,
  size: 5,
  totalElements: 1,
  totalPages: 1,
  first: true,
  last: true,
}

const maintenanceFixture: AdminFacilityMaintenance = {
  ticketId: 41,
  userId: 12,
  userEmail: 'student@nus.edu.sg',
  spaceId: 5,
  spaceName: 'Seminar Room 2',
  spaceType: 'SEMINAR_ROOM',
  building: 'COM3',
  floor: '2',
  roomNumber: '02-10',
  facilityType: 'AIR_CONDITIONING',
  description: 'The air conditioning is too warm.',
  priority: 'HIGH',
  status: 'IN_PROGRESS',
  createdAt: '2026-08-15T10:00:00',
  updatedAt: '2026-08-15T11:00:00',
}

const maintenancePage: PageResponse<AdminFacilityMaintenance> = {
  content: [maintenanceFixture],
  page: 0,
  size: 25,
  totalElements: 1,
  totalPages: 1,
  first: true,
  last: true,
}

describe('admin Facilities API', () => {
  afterEach(() => vi.restoreAllMocks())

  it('loads the Facilities overview and returns response data', async () => {
    const get = vi.spyOn(apiClient, 'get').mockResolvedValue({ data: overviewFixture })

    const result = await getAdminFacilitiesOverview()

    expect(get).toHaveBeenCalledWith('/admin/facilities/overview')
    expect(result).toBe(overviewFixture)
  })

  it('passes system booking filters, pagination, and sorting', async () => {
    const get = vi.spyOn(apiClient, 'get').mockResolvedValue({ data: bookingsPage })
    const params = {
      status: 'CONFIRMED' as const,
      spaceId: 5,
      userId: 10,
      userEmail: 'student@nus.edu.sg',
      startFrom: '2026-08-16T00:00:00',
      startTo: '2026-08-17T00:00:00',
      page: 0,
      size: 5,
      sort: 'startDateTime,asc',
    }

    const result = await searchAdminFacilityBookings(params)

    expect(get).toHaveBeenCalledWith('/admin/facilities/bookings/search', { params })
    expect(result).toBe(bookingsPage)
  })

  it('encodes multiple maintenance statuses and all maintenance filters', async () => {
    const get = vi.spyOn(apiClient, 'get').mockResolvedValue({ data: maintenancePage })

    const result = await searchAdminFacilityMaintenance({
      statuses: ['SUBMITTED', 'IN_PROGRESS'],
      priority: 'HIGH',
      spaceId: 5,
      userId: 12,
      userEmail: '  student@nus.edu.sg  ',
      building: '  com3 ',
      createdFrom: '2026-08-01T00:00:00',
      createdTo: '2026-08-15T23:59:59',
      page: 1,
      size: 50,
      sort: 'createdAt,desc',
    })

    const call = get.mock.calls.at(-1)
    expect(call?.[0]).toBe('/admin/facilities/maintenance/search')
    const params = call?.[1]?.params as URLSearchParams
    expect(params.getAll('status')).toEqual(['SUBMITTED', 'IN_PROGRESS'])
    expect(params.get('priority')).toBe('HIGH')
    expect(params.get('spaceId')).toBe('5')
    expect(params.get('userId')).toBe('12')
    expect(params.get('userEmail')).toBe('student@nus.edu.sg')
    expect(params.get('building')).toBe('com3')
    expect(params.get('createdFrom')).toBe('2026-08-01T00:00:00')
    expect(params.get('createdTo')).toBe('2026-08-15T23:59:59')
    expect(params.get('page')).toBe('1')
    expect(params.get('size')).toBe('50')
    expect(params.get('sort')).toBe('createdAt,desc')
    expect(result).toBe(maintenancePage)
  })

  it('loads a maintenance detail by ticket ID', async () => {
    const get = vi.spyOn(apiClient, 'get').mockResolvedValue({ data: maintenanceFixture })

    const result = await getAdminFacilityMaintenance(41)

    expect(get).toHaveBeenCalledWith('/admin/facilities/maintenance/search/41')
    expect(result).toBe(maintenanceFixture)
  })

  it('propagates API errors', async () => {
    const error = new Error('Facilities request failed')
    vi.spyOn(apiClient, 'get').mockRejectedValue(error)

    await expect(getAdminFacilitiesOverview()).rejects.toBe(error)
    await expect(searchAdminFacilityBookings({ page: 0 })).rejects.toBe(error)
    await expect(searchAdminFacilityMaintenance({ page: 0 })).rejects.toBe(error)
    await expect(getAdminFacilityMaintenance(41)).rejects.toBe(error)
  })
})
