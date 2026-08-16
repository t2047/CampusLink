import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { searchAdminFacilityBookings } from '../../../api/adminFacilities'
import type { AdminFacilityBooking, PageResponse } from '../../../types'
import { UpcomingReservationsSection } from './UpcomingReservationsSection'

vi.mock('../../../api/adminFacilities', () => ({
  searchAdminFacilityBookings: vi.fn(),
}))

const searchBookings = vi.mocked(searchAdminFacilityBookings)

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

function page(content: AdminFacilityBooking[]): PageResponse<AdminFacilityBooking> {
  return {
    content,
    page: 0,
    size: 5,
    totalElements: content.length,
    totalPages: content.length === 0 ? 0 : 1,
    first: true,
    last: true,
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((resolvePromise) => {
    resolve = resolvePromise
  })
  return { promise, resolve }
}

function renderSection() {
  return render(
    <MemoryRouter>
      <UpcomingReservationsSection />
    </MemoryRouter>,
  )
}

describe('UpcomingReservationsSection', () => {
  beforeEach(() => {
    searchBookings.mockReset()
    searchBookings.mockResolvedValue(page([bookingFixture]))
  })

  afterEach(() => {
    cleanup()
    vi.useRealTimers()
  })

  it('uses Singapore local datetime without a UTC suffix for the upcoming request', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-15T16:30:45.000Z'))
    searchBookings.mockReturnValue(deferred<PageResponse<AdminFacilityBooking>>().promise)

    renderSection()

    expect(screen.getByRole('status')).toHaveTextContent('Loading upcoming reservations')
    expect(searchBookings).toHaveBeenCalledWith({
      status: 'CONFIRMED',
      startFrom: '2026-08-16T00:30:45',
      page: 0,
      size: 5,
      sort: 'startDateTime,asc',
    })
    expect(searchBookings.mock.calls[0][0].startFrom).not.toContain('Z')
  })

  it('shows facility, room, user, dates, and status', async () => {
    renderSection()

    const table = await screen.findByRole('table', { name: 'Upcoming reservations' })
    expect(within(table).getByText('Seminar Room 2')).toBeInTheDocument()
    expect(within(table).getByText('COM3 / 02-10')).toBeInTheDocument()
    expect(within(table).getByText('student@nus.edu.sg')).toBeInTheDocument()
    expect(within(table).getByText('16 Aug 2026, 10:00 AM')).toBeInTheDocument()
    expect(within(table).getByText('16 Aug 2026, 11:00 AM')).toBeInTheDocument()
    expect(within(table).getByText('Confirmed')).toBeInTheDocument()
  })

  it('shows Unknown user when the booking email is null', async () => {
    searchBookings.mockResolvedValue(page([{ ...bookingFixture, userEmail: null }]))

    renderSection()

    expect(await screen.findByText('Unknown user')).toBeInTheDocument()
  })

  it('shows the empty state', async () => {
    searchBookings.mockResolvedValue(page([]))

    renderSection()

    expect(await screen.findByText('No upcoming reservations.')).toBeInTheDocument()
    expect(screen.queryByRole('table', { name: 'Upcoming reservations' })).not.toBeInTheDocument()
  })

  it('shows an error and retries successfully without duplicate requests', async () => {
    const retry = deferred<PageResponse<AdminFacilityBooking>>()
    searchBookings
      .mockRejectedValueOnce(new Error('Unable to load upcoming reservations.'))
      .mockReturnValueOnce(retry.promise)

    renderSection()

    expect(await screen.findByRole('alert')).toHaveTextContent('Unable to load upcoming reservations.')
    const retryButton = screen.getByRole('button', { name: 'Retry' })
    fireEvent.click(retryButton)
    expect(screen.getByRole('status')).toHaveTextContent('Loading upcoming reservations')
    fireEvent.click(retryButton)
    expect(searchBookings).toHaveBeenCalledTimes(2)

    await act(async () => {
      retry.resolve(page([bookingFixture]))
      await retry.promise
    })

    expect(await screen.findByRole('table', { name: 'Upcoming reservations' })).toBeInTheDocument()
  })

  it('links to the full reservations page', () => {
    renderSection()

    expect(screen.getByRole('link', { name: 'View All' })).toHaveAttribute(
      'href',
      '/admin/facilities/reservations',
    )
  })

  it('shows no more than five rows even if the API returns more', async () => {
    const bookings = Array.from({ length: 6 }, (_, index) => ({
      ...bookingFixture,
      bookingId: index + 1,
      spaceName: `Room ${index + 1}`,
    }))
    searchBookings.mockResolvedValue(page(bookings))

    renderSection()

    const table = await screen.findByRole('table', { name: 'Upcoming reservations' })
    expect(within(table).getAllByRole('row')).toHaveLength(6)
    expect(within(table).queryByText('Room 6')).not.toBeInTheDocument()
  })

  it('settles a pending request safely after unmount', async () => {
    const pending = deferred<PageResponse<AdminFacilityBooking>>()
    searchBookings.mockReturnValue(pending.promise)
    const { unmount } = renderSection()

    expect(searchBookings).toHaveBeenCalledTimes(1)
    unmount()

    await act(async () => {
      pending.resolve(page([bookingFixture]))
      await pending.promise
    })

    expect(searchBookings).toHaveBeenCalledTimes(1)
  })
})
