import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { searchAdminFacilityBookings } from '../../api/adminFacilities'
import { facilitiesApi } from '../../api/facilities'
import type { AdminFacilityBooking, PageResponse } from '../../types'
import { ReservationsPage } from './FacilitiesPage'

vi.mock('../../api/adminFacilities', () => ({
  searchAdminFacilityBookings: vi.fn(),
}))

const searchBookings = vi.mocked(searchAdminFacilityBookings)

const firstBooking: AdminFacilityBooking = {
  bookingId: 101,
  userId: 10,
  userEmail: 'first.student@nus.edu.sg',
  spaceId: 5,
  spaceName: 'Seminar Room 2',
  building: 'COM3',
  floor: '2',
  roomNumber: '02-10',
  spaceType: 'SEMINAR_ROOM',
  startDateTime: '2026-08-16T00:30:00',
  endDateTime: '2026-08-16T01:30:00',
  status: 'CONFIRMED',
  createdAt: '2026-08-15T10:00:00',
  updatedAt: '2026-08-15T10:00:00',
}

const secondBooking: AdminFacilityBooking = {
  ...firstBooking,
  bookingId: 102,
  userId: 11,
  userEmail: null,
  spaceId: 6,
  spaceName: 'Study Room 4',
  building: 'COM2',
  roomNumber: '03-12',
  status: 'COMPLETED',
  startDateTime: '2026-08-17T14:00:00',
  endDateTime: '2026-08-17T15:30:00',
}

function bookingPage(
  content: AdminFacilityBooking[],
  page = 0,
  size = 25,
  totalElements = content.length,
): PageResponse<AdminFacilityBooking> {
  return {
    content,
    page,
    size,
    totalElements,
    totalPages: totalElements === 0 ? 0 : Math.ceil(totalElements / size),
    first: page === 0,
    last: totalElements === 0 || page >= Math.ceil(totalElements / size) - 1,
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((resolvePromise) => {
    resolve = resolvePromise
  })
  return { promise, resolve }
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/admin/facilities/reservations']}>
      <ReservationsPage />
    </MemoryRouter>,
  )
}

describe('ReservationsPage', () => {
  beforeEach(() => {
    searchBookings.mockReset()
    searchBookings.mockResolvedValue(bookingPage([firstBooking, secondBooking]))
  })

  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
  })

  it('loads system reservations and never calls the ordinary user bookings API', () => {
    const pending = deferred<PageResponse<AdminFacilityBooking>>()
    searchBookings.mockReturnValue(pending.promise)
    const ordinaryBookings = vi.spyOn(facilitiesApi, 'getReservations')

    renderPage()

    expect(screen.getByRole('status')).toHaveTextContent('Loading reservations')
    expect(searchBookings).toHaveBeenCalledWith({
      status: undefined,
      userEmail: undefined,
      page: 0,
      size: 25,
      sort: 'startDateTime,asc',
    })
    expect(ordinaryBookings).not.toHaveBeenCalled()
  })

  it('shows system-level users, locations, statuses, and stable Facilities local datetimes', async () => {
    renderPage()

    const table = await screen.findByRole('table', { name: 'System reservations' })
    expect(within(table).getByText('101')).toBeInTheDocument()
    expect(within(table).getByText('Seminar Room 2')).toBeInTheDocument()
    expect(within(table).getByText('COM3 / 02-10')).toBeInTheDocument()
    expect(within(table).getByText('first.student@nus.edu.sg')).toBeInTheDocument()
    expect(within(table).getByText('Unknown user')).toBeInTheDocument()
    expect(within(table).getByText('16 Aug 2026, 12:30 AM')).toBeInTheDocument()
    expect(within(table).getByText('16 Aug 2026, 1:30 AM')).toBeInTheDocument()
    expect(within(table).getByText('Confirmed')).toBeInTheDocument()
    expect(within(table).getByText('Completed')).toBeInTheDocument()
    expect(screen.queryByText(/User #/)).not.toBeInTheDocument()
    expect(screen.queryByText(/current account/i)).not.toBeInTheDocument()
  })

  it('applies status and trimmed user email filters through the backend and resets page to zero', async () => {
    renderPage()
    await screen.findByRole('table', { name: 'System reservations' })

    fireEvent.change(screen.getByLabelText('Status filter'), { target: { value: 'CONFIRMED' } })
    fireEvent.change(screen.getByRole('textbox', { name: 'User Email' }), {
      target: { value: '  first.student@nus.edu.sg  ' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Apply Filters' }))

    await waitFor(() => expect(searchBookings).toHaveBeenCalledTimes(2))
    expect(searchBookings).toHaveBeenLastCalledWith({
      status: 'CONFIRMED',
      userEmail: 'first.student@nus.edu.sg',
      page: 0,
      size: 25,
      sort: 'startDateTime,asc',
    })
  })

  it('uses backend pagination and supports changing page size', async () => {
    searchBookings.mockImplementation(async (params) => bookingPage(
      [firstBooking],
      params.page ?? 0,
      params.size ?? 25,
      60,
    ))
    renderPage()

    await screen.findByRole('table', { name: 'System reservations' })
    fireEvent.click(screen.getByRole('button', { name: 'Go to next page' }))

    await waitFor(() => expect(searchBookings).toHaveBeenLastCalledWith(expect.objectContaining({
      page: 1,
      size: 25,
    })))

    const rowsPerPage = screen.getByRole('combobox', { name: 'Rows per page:' })
    fireEvent.mouseDown(rowsPerPage)
    fireEvent.click(screen.getByRole('option', { name: '50' }))

    await waitFor(() => expect(searchBookings).toHaveBeenLastCalledWith(expect.objectContaining({
      page: 0,
      size: 50,
    })))
  })

  it('shows an error and retries the same backend request', async () => {
    const retry = deferred<PageResponse<AdminFacilityBooking>>()
    searchBookings
      .mockRejectedValueOnce(new Error('Unable to load reservations.'))
      .mockReturnValueOnce(retry.promise)

    renderPage()

    expect(await screen.findByRole('alert')).toHaveTextContent('Unable to load reservations.')
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))
    expect(screen.getByRole('status')).toHaveTextContent('Loading reservations')

    await act(async () => {
      retry.resolve(bookingPage([firstBooking]))
      await retry.promise
    })

    expect(await screen.findByRole('table', { name: 'System reservations' })).toBeInTheDocument()
    expect(searchBookings).toHaveBeenCalledTimes(2)
  })

  it('shows the system-level empty result message', async () => {
    searchBookings.mockResolvedValue(bookingPage([]))

    renderPage()

    expect(await screen.findByText('No reservations found.')).toBeInTheDocument()
    expect(screen.queryByText(/current account/i)).not.toBeInTheDocument()
    expect(screen.queryByRole('table', { name: 'System reservations' })).not.toBeInTheDocument()
  })
})