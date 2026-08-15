import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { facilitiesApi, type BookingResponse, type Space } from '../../api/facilities'
import { BookingDetailsPage } from './BookingDetailsPage'
import { FacilitiesLayout } from './FacilitiesLayout'
import { MyBookingsPage } from './MyBookingsPage'

vi.mock('../../api/facilities', () => ({
  facilitiesApi: {
    listBookings: vi.fn(),
    getBooking: vi.fn(),
    cancelBooking: vi.fn(),
  },
}))

const space: Space = {
  spaceId: 7,
  name: 'COM2 Seminar Room 03-12',
  building: 'COM2',
  floor: '3',
  roomNumber: '03-12',
  spaceType: 'SEMINAR_ROOM',
  capacity: 8,
  equipment: ['Projector', 'Whiteboard'],
  openingTime: '08:00:00',
  closingTime: '22:00:00',
  status: 'AVAILABLE',
}

const booking: BookingResponse = {
  success: true,
  bookingId: 42,
  space,
  startDateTime: '2099-08-13T14:00:00',
  endDateTime: '2099-08-13T16:00:00',
  status: 'CONFIRMED',
  createdAt: '2099-08-10T10:30:00',
  updatedAt: '2099-08-10T10:30:00',
}

function renderBookings(path = '/facilities/bookings') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/facilities" element={<FacilitiesLayout />}>
          <Route path="bookings" element={<MyBookingsPage />} />
          <Route path="bookings/:bookingId" element={<BookingDetailsPage />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

describe('Facilities booking pages', () => {
  const listBookings = vi.mocked(facilitiesApi.listBookings)
  const getBooking = vi.mocked(facilitiesApi.getBooking)
  const cancelBooking = vi.mocked(facilitiesApi.cancelBooking)

  beforeEach(() => {
    listBookings.mockReset()
    getBooking.mockReset()
    cancelBooking.mockReset()
  })
  afterEach(() => cleanup())

  it('shows loading while My Bookings is requested', () => {
    listBookings.mockReturnValue(new Promise(() => {}))
    renderBookings()

    expect(screen.getByRole('progressbar')).toBeInTheDocument()
    expect(listBookings).toHaveBeenCalledOnce()
  })

  it('renders upcoming bookings first and navigates to booking details', async () => {
    const later = { ...booking, bookingId: 43, startDateTime: '2099-08-14T14:00:00', endDateTime: '2099-08-14T16:00:00' }
    listBookings.mockResolvedValue([later, booking])
    getBooking.mockResolvedValue(booking)
    renderBookings()

    const headings = await screen.findAllByRole('heading', { name: space.name })
    expect(headings).toHaveLength(2)
    expect(screen.getAllByRole('link', { name: 'View details' })[0]).toHaveAttribute('href', '/facilities/bookings/42')
    expect(screen.getByText('13 Aug 2099 · 2:00 PM – 4:00 PM')).toBeInTheDocument()

    fireEvent.click(screen.getAllByRole('link', { name: 'View details' })[0])

    expect(await screen.findByRole('heading', { name: 'Booking #42' })).toBeInTheDocument()
    expect(getBooking).toHaveBeenCalledWith(42)
  })

  it('renders My Bookings empty and error states', async () => {
    listBookings.mockResolvedValueOnce([])
    const { unmount } = renderBookings()
    expect(await screen.findByText('No bookings yet')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Search spaces' })).toHaveAttribute('href', '/facilities')
    unmount()

    listBookings.mockRejectedValueOnce(new Error('Bookings service unavailable'))
    renderBookings()
    expect(await screen.findByText('Bookings service unavailable')).toBeInTheDocument()
    expect(screen.queryByText('No bookings yet')).not.toBeInTheDocument()
  })

  it('renders booking details from the backend response', async () => {
    getBooking.mockResolvedValue(booking)
    renderBookings('/facilities/bookings/42')

    expect(screen.getByRole('progressbar')).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'Booking #42' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: space.name })).toBeInTheDocument()
    expect(screen.getByText('COM2 · Floor 3 · Room 03-12')).toBeInTheDocument()
    expect(screen.getByText('13 Aug 2099')).toBeInTheDocument()
    expect(screen.getByText('2:00 PM – 4:00 PM')).toBeInTheDocument()
    expect(screen.getAllByText('10 Aug 2099, 10:30 AM', { exact: false })).toHaveLength(2)
    expect(screen.getByRole('button', { name: 'Cancel Booking' })).toBeInTheDocument()
  })

  it('renders safe not-found and API error states', async () => {
    getBooking.mockRejectedValueOnce({ isAxiosError: true, response: { status: 404 } })
    const { unmount } = renderBookings('/facilities/bookings/999')
    expect(await screen.findByText('Booking not found.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to My Bookings' })).toHaveAttribute('href', '/facilities/bookings')
    unmount()

    getBooking.mockRejectedValueOnce(new Error('Booking service unavailable'))
    renderBookings('/facilities/bookings/42')
    expect(await screen.findByText('Booking service unavailable')).toBeInTheDocument()
  })

  it('does not offer cancellation for an already cancelled booking', async () => {
    getBooking.mockResolvedValue({ ...booking, status: 'CANCELLED' })
    renderBookings('/facilities/bookings/42')

    expect(await screen.findByText('CANCELLED')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Cancel Booking' })).not.toBeInTheDocument()
  })

  it('opens confirmation without cancelling, then refreshes details and My Bookings after success', async () => {
    const cancelled = { ...booking, status: 'CANCELLED' as const, updatedAt: '2099-08-11T09:00:00' }
    getBooking.mockResolvedValue(booking)
    cancelBooking.mockResolvedValue(cancelled)
    listBookings.mockResolvedValue([cancelled])
    renderBookings('/facilities/bookings/42')

    fireEvent.click(await screen.findByRole('button', { name: 'Cancel Booking' }))
    expect(cancelBooking).not.toHaveBeenCalled()
    const dialog = screen.getByRole('dialog', { name: 'Cancel this booking?' })
    expect(within(dialog).getByText('13 Aug 2099')).toBeInTheDocument()
    expect(within(dialog).getByText('2:00 PM – 4:00 PM')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Confirm Cancellation' }))

    await waitFor(() => expect(cancelBooking).toHaveBeenCalledWith(42))
    expect(await screen.findByText('Booking cancelled successfully.')).toBeInTheDocument()
    expect(screen.getByText('CANCELLED')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Cancel Booking' })).not.toBeInTheDocument()

    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Cancel this booking?' })).not.toBeInTheDocument())
    fireEvent.click(screen.getByRole('link', { name: '← Back to My Bookings' }))
    expect(await screen.findByRole('heading', { name: 'My Bookings' })).toBeInTheDocument()
    expect(listBookings).toHaveBeenCalledOnce()
    expect(screen.getByText('CANCELLED')).toBeInTheDocument()
  })

  it('locks the confirmation controls and prevents duplicate cancellation requests', async () => {
    getBooking.mockResolvedValue(booking)
    cancelBooking.mockReturnValue(new Promise(() => {}))
    renderBookings('/facilities/bookings/42')

    fireEvent.click(await screen.findByRole('button', { name: 'Cancel Booking' }))
    const confirm = screen.getByRole('button', { name: 'Confirm Cancellation' })
    fireEvent.click(confirm)
    fireEvent.click(confirm)

    expect(await screen.findByRole('button', { name: 'Cancelling...' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Keep Booking' })).toBeDisabled()
    expect(cancelBooking).toHaveBeenCalledOnce()
  })

  it('shows the backend cancellation error and allows a safe retry', async () => {
    getBooking.mockResolvedValue(booking)
    cancelBooking.mockRejectedValue({
      isAxiosError: true,
      message: 'Request failed with status code 409',
      response: { status: 409, data: { code: 'BOOKING_CANCELLATION_NOT_ALLOWED', error: 'A booking cannot be cancelled after its start time' } },
    })
    renderBookings('/facilities/bookings/42')

    fireEvent.click(await screen.findByRole('button', { name: 'Cancel Booking' }))
    fireEvent.click(screen.getByRole('button', { name: 'Confirm Cancellation' }))

    expect(await screen.findByText('A booking cannot be cancelled after its start time')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Confirm Cancellation' })).toBeEnabled()
    expect(cancelBooking).toHaveBeenCalledWith(42)
  })
})
