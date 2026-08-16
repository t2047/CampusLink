import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { StrictMode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getAdminFacilitiesOverview, searchAdminFacilityBookings } from '../../api/adminFacilities'
import { facilitiesApi } from '../../api/facilities'
import type { AdminFacilitiesOverview, AdminFacilityBooking, PageResponse } from '../../types'
import { FacilitiesDashboardPage } from './FacilitiesPage'

vi.mock('../../api/adminFacilities', () => ({
  getAdminFacilitiesOverview: vi.fn(),
  searchAdminFacilityBookings: vi.fn(),
  searchAdminFacilityMaintenance: vi.fn(),
  getAdminFacilityMaintenance: vi.fn(),
}))

const getOverview = vi.mocked(getAdminFacilitiesOverview)
const searchBookings = vi.mocked(searchAdminFacilityBookings)

const overview: AdminFacilitiesOverview = {
  summary: {
    totalSpaces: 12, availableSpaces: 8, outOfServiceSpaces: 3, inactiveSpaces: 1,
    totalBookings: 20, confirmedBookings: 9, cancelledBookings: 4, completedBookings: 7,
    totalMaintenanceRequests: 14, submittedMaintenanceRequests: 5, inProgressMaintenanceRequests: 3,
    resolvedMaintenanceRequests: 4, cancelledMaintenanceRequests: 2, openMaintenanceRequests: 8,
  },
  spaceStatusBreakdown: [
    { status: 'AVAILABLE', count: 8 }, { status: 'OUT_OF_SERVICE', count: 3 }, { status: 'INACTIVE', count: 1 },
  ],
  bookingStatusBreakdown: [],
  maintenanceStatusBreakdown: [],
}

function booking(overrides: Partial<AdminFacilityBooking> = {}): AdminFacilityBooking {
  return {
    bookingId: 1, userId: 10, userEmail: 'student@nus.edu.sg', spaceId: 5, spaceName: 'Seminar Room 2',
    building: 'COM3', floor: '2', roomNumber: '02-10', spaceType: 'SEMINAR_ROOM',
    startDateTime: '2026-08-15T10:00:00', endDateTime: '2026-08-15T11:00:00', status: 'CONFIRMED',
    createdAt: '2026-08-14T10:00:00', updatedAt: '2026-08-14T10:00:00', ...overrides,
  }
}

function page(content: AdminFacilityBooking[], pageIndex = 0, totalPages = content.length === 0 ? 0 : 1): PageResponse<AdminFacilityBooking> {
  return { content, page: pageIndex, size: 100, totalElements: content.length, totalPages,
    first: pageIndex === 0, last: totalPages === 0 || pageIndex === totalPages - 1 }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((resolvePromise) => { resolve = resolvePromise })
  return { promise, resolve }
}

function renderPage(strict = false) {
  const page = <MemoryRouter><FacilitiesDashboardPage /></MemoryRouter>
  return render(strict ? <StrictMode>{page}</StrictMode> : page)
}

describe('FacilitiesDashboardPage system analytics', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-08-14T16:30:00Z'))
    getOverview.mockReset()
    getOverview.mockResolvedValue(overview)
    searchBookings.mockReset()
    searchBookings.mockResolvedValue(page([]))
    vi.spyOn(facilitiesApi, 'getDashboard').mockRejectedValue(new Error('Legacy dashboard API must not be called'))
    vi.spyOn(facilitiesApi, 'getReservations').mockRejectedValue(new Error('Student bookings API must not be called'))
    vi.spyOn(facilitiesApi, 'getMaintenance').mockRejectedValue(new Error('Student maintenance API must not be called'))
    vi.spyOn(facilitiesApi, 'listBookings').mockRejectedValue(new Error('Student bookings API must not be called'))
    vi.spyOn(facilitiesApi, 'listMaintenanceRequests').mockRejectedValue(new Error('Student maintenance API must not be called'))
  })

  afterEach(() => {
    cleanup()
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('uses Admin overview and system bookings with a Singapore 30-day LocalDateTime window', async () => {
    renderPage()

    expect(screen.getByRole('status')).toHaveTextContent('Loading Facilities analytics')
    expect(getOverview).toHaveBeenCalledTimes(1)
    expect(searchBookings).toHaveBeenCalledWith({
      startFrom: '2026-07-17T00:00:00',
      startTo: '2026-08-15T23:59:59',
      page: 0,
      size: 100,
      sort: 'startDateTime,asc',
    })
    expect(searchBookings.mock.calls[0][0].startFrom).not.toContain('Z')
    expect(searchBookings.mock.calls[0][0].startTo).not.toContain('Z')
    expect(facilitiesApi.getDashboard).not.toHaveBeenCalled()
    expect(facilitiesApi.getReservations).not.toHaveBeenCalled()
    expect(facilitiesApi.getMaintenance).not.toHaveBeenCalled()
    expect(facilitiesApi.listBookings).not.toHaveBeenCalled()
    expect(facilitiesApi.listMaintenanceRequests).not.toHaveBeenCalled()

    expect(await screen.findByRole('group', { name: 'Total Facilities' })).toHaveTextContent('12')
    expect(searchBookings).toHaveBeenCalledTimes(1)
  })

  it('loads all booking pages once in page order and includes the last page in analytics', async () => {
    searchBookings.mockImplementation(async ({ page: pageIndex = 0 }) => {
      if (pageIndex === 0) return page([booking({ bookingId: 1, spaceId: 1, spaceName: 'First Facility' })], 0, 3)
      if (pageIndex === 1) return page([booking({ bookingId: 2, spaceId: 2, spaceName: 'Second Facility' })], 1, 3)
      return page([booking({ bookingId: 3, spaceId: 3, spaceName: 'Last Page Facility' })], 2, 3)
    })

    renderPage()

    expect(await screen.findByText('Last Page Facility')).toBeInTheDocument()
    expect(searchBookings.mock.calls.map(([params]) => params.page)).toEqual([0, 1, 2])
  })

  it('shows system KPI, seven-day trend, deterministic usage, and excludes cancelled bookings', async () => {
    searchBookings.mockResolvedValue(page([
      booking({ bookingId: 1, spaceId: 5, spaceName: 'Seminar Room 2', status: 'CONFIRMED' }),
      booking({ bookingId: 2, spaceId: 5, spaceName: 'Seminar Room 2', status: 'COMPLETED' }),
      booking({ bookingId: 3, spaceId: 6, spaceName: 'Alpha Lab', status: 'COMPLETED', startDateTime: '2026-08-13T09:00:00' }),
      booking({ bookingId: 4, spaceId: 7, spaceName: 'Beta Lab', status: 'CONFIRMED', startDateTime: '2026-08-09T09:00:00' }),
      booking({ bookingId: 5, spaceId: 8, spaceName: 'Cancelled Room', status: 'CANCELLED' }),
      booking({ bookingId: 6, spaceId: 9, spaceName: 'Outside Trend', status: 'CONFIRMED', startDateTime: '2026-08-08T09:00:00' }),
    ]))

    renderPage()

    expect(await screen.findByRole('group', { name: 'Total Facilities' })).toHaveTextContent('12')
    expect(screen.getByRole('group', { name: 'Available Facilities' })).toHaveTextContent('8')
    expect(screen.getByRole('group', { name: "Today's Reservations" })).toHaveTextContent('2')
    expect(screen.getByRole('group', { name: 'Open Maintenance Requests' })).toHaveTextContent('8')
    expect(screen.queryByText('Under Maintenance')).not.toBeInTheDocument()

    const trendGroups = screen.getAllByRole('group', { name: /reservations$/ })
    expect(trendGroups).toHaveLength(7)
    expect(screen.getByRole('group', { name: '9 Aug: 1 reservations' })).toBeInTheDocument()
    expect(screen.getByRole('group', { name: '10 Aug: 0 reservations' })).toBeInTheDocument()
    expect(screen.getByRole('group', { name: '13 Aug: 1 reservations' })).toBeInTheDocument()
    expect(screen.getByRole('group', { name: '15 Aug: 2 reservations' })).toBeInTheDocument()

    expect(screen.getByRole('img', { name: 'Seminar Room 2 usage: 2 reservations' })).toHaveStyle({ width: '100%' })
    expect(screen.getByRole('img', { name: 'Alpha Lab usage: 1 reservations' })).toHaveStyle({ width: '50%' })
    expect(screen.queryByText('Cancelled Room')).not.toBeInTheDocument()
  })

  it('renders a stable zero trend and usage empty state for an empty booking result', async () => {
    renderPage()

    expect(await screen.findByText('No active facility reservations in the last 30 days.')).toBeInTheDocument()
    expect(screen.getAllByRole('group', { name: /0 reservations$/ })).toHaveLength(7)
    expect(screen.getByRole('group', { name: "Today's Reservations" })).toHaveTextContent('0')
  })

  it('shows a complete error without partial analytics when a later page fails, then retries all data', async () => {
    searchBookings
      .mockResolvedValueOnce(page([booking()], 0, 2))
      .mockRejectedValueOnce(new Error('Second booking page failed'))
      .mockResolvedValueOnce(page([booking()], 0, 1))

    renderPage()

    expect(await screen.findByRole('alert')).toHaveTextContent('Second booking page failed')
    expect(screen.queryByRole('group', { name: 'Total Facilities' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))
    expect(screen.getByRole('status')).toHaveTextContent('Loading Facilities analytics')
    expect(await screen.findByRole('group', { name: 'Total Facilities' })).toHaveTextContent('12')
    expect(getOverview).toHaveBeenCalledTimes(2)
    expect(searchBookings).toHaveBeenCalledTimes(3)
  })

  it('prevents duplicate concurrent requests in StrictMode', () => {
    const overviewRequest = deferred<AdminFacilitiesOverview>()
    const bookingsRequest = deferred<PageResponse<AdminFacilityBooking>>()
    getOverview.mockReturnValue(overviewRequest.promise)
    searchBookings.mockReturnValue(bookingsRequest.promise)

    renderPage(true)

    expect(getOverview).toHaveBeenCalledTimes(1)
    expect(searchBookings).toHaveBeenCalledTimes(1)
  })

  it('does not update state after unmount', async () => {
    const overviewRequest = deferred<AdminFacilitiesOverview>()
    const bookingsRequest = deferred<PageResponse<AdminFacilityBooking>>()
    getOverview.mockReturnValue(overviewRequest.promise)
    searchBookings.mockReturnValue(bookingsRequest.promise)
    const view = renderPage()

    view.unmount()
    await act(async () => {
      overviewRequest.resolve(overview)
      bookingsRequest.resolve(page([]))
      await Promise.all([overviewRequest.promise, bookingsRequest.promise])
    })

    expect(getOverview).toHaveBeenCalledTimes(1)
    expect(searchBookings).toHaveBeenCalledTimes(1)
  })
})
