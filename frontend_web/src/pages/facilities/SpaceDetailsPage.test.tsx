import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  facilitiesApi,
  type AvailabilityResponse,
  type BookingResponse,
  type Space,
} from '../../api/facilities'
import { SpaceDetailsPage } from './SpaceDetailsPage'

vi.mock('../../api/facilities', () => ({
  facilitiesApi: {
    getSpace: vi.fn(),
    checkSpaceAvailability: vi.fn(),
    createBooking: vi.fn(),
  },
}))

const space: Space = {
  spaceId: 7,
  name: 'COM3 Study Room 01',
  building: 'COM3',
  floor: '2',
  roomNumber: '02-01',
  spaceType: 'STUDY_ROOM',
  capacity: 6,
  equipment: ['Projector', 'Whiteboard'],
  openingTime: '08:00:00',
  closingTime: '22:00:00',
  status: 'AVAILABLE',
}

const availableResponse: AvailabilityResponse = {
  available: true,
  reasonCode: null,
  space,
  startDateTime: '2026-08-13T14:00:00',
  endDateTime: '2026-08-13T16:00:00',
}

const bookingResponse: BookingResponse = {
  success: true,
  bookingId: 42,
  space,
  startDateTime: '2026-08-13T14:00:00',
  endDateTime: '2026-08-13T16:00:00',
  status: 'CONFIRMED',
  createdAt: '2026-08-12T10:00:00',
  updatedAt: '2026-08-12T10:00:00',
}

function renderDetails() {
  return render(
    <MemoryRouter initialEntries={['/facilities/spaces/7']}>
      <Routes>
        <Route path="/facilities/spaces/:spaceId" element={<SpaceDetailsPage />} />
      </Routes>
    </MemoryRouter>,
  )
}

function selectBookingTime(startTime = '14:00', endTime = '16:00') {
  fireEvent.change(screen.getByLabelText('Date'), { target: { value: '2026-08-13' } })
  fireEvent.change(screen.getByLabelText('Start time'), { target: { value: startTime } })
  fireEvent.change(screen.getByLabelText('End time'), { target: { value: endTime } })
}

describe('SpaceDetailsPage availability and booking', () => {
  const getSpace = vi.mocked(facilitiesApi.getSpace)
  const checkSpaceAvailability = vi.mocked(facilitiesApi.checkSpaceAvailability)
  const createBooking = vi.mocked(facilitiesApi.createBooking)

  beforeEach(() => {
    getSpace.mockReset().mockResolvedValue(space)
    checkSpaceAvailability.mockReset()
    createBooking.mockReset()
  })

  afterEach(() => cleanup())

  async function renderLoaded() {
    renderDetails()
    await screen.findByRole('heading', { name: space.name })
  }

  async function checkAvailable() {
    checkSpaceAvailability.mockResolvedValue(availableResponse)
    selectBookingTime()
    fireEvent.click(screen.getByRole('button', { name: 'Check Availability' }))
    await screen.findByText('This space is available for the selected time.')
  }

  it('renders the booking section and prevents incomplete availability checks', async () => {
    await renderLoaded()

    expect(screen.getByRole('heading', { name: 'Availability and Booking' })).toBeInTheDocument()
    expect(screen.getByLabelText('Date')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Check Availability' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Book this space' })).toBeDisabled()
    expect(screen.getByRole('link', { name: 'Report a facility issue' })).toHaveAttribute('href', '/facilities/maintenance/new?spaceId=7')
    expect(checkSpaceAvailability).not.toHaveBeenCalled()
  })

  it('rejects an invalid time range without sending a request', async () => {
    await renderLoaded()
    selectBookingTime('16:00', '14:00')

    fireEvent.click(screen.getByRole('button', { name: 'Check Availability' }))

    expect(await screen.findByText('End time must be later than start time.')).toBeInTheDocument()
    expect(checkSpaceAvailability).not.toHaveBeenCalled()
  })

  it('shows a loading state while availability is checked', async () => {
    checkSpaceAvailability.mockReturnValue(new Promise(() => {}))
    await renderLoaded()
    selectBookingTime()

    fireEvent.click(screen.getByRole('button', { name: 'Check Availability' }))

    expect(await screen.findByRole('button', { name: /Checking/ })).toBeDisabled()
    expect(screen.getByRole('progressbar')).toBeInTheDocument()
  })

  it('shows an available result and sends seconds without a timezone suffix', async () => {
    await renderLoaded()
    await checkAvailable()

    expect(checkSpaceAvailability).toHaveBeenCalledWith(7, '2026-08-13T14:00:00', '2026-08-13T16:00:00')
    expect(screen.getByText('Available')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Book this space' })).toBeEnabled()
  })

  it('shows a friendly booking conflict when the space is unavailable', async () => {
    checkSpaceAvailability.mockResolvedValue({ ...availableResponse, available: false, reasonCode: 'BOOKING_CONFLICT' })
    await renderLoaded()
    selectBookingTime()

    fireEvent.click(screen.getByRole('button', { name: 'Check Availability' }))

    expect(await screen.findByText('This space is already booked during the selected time.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Book this space' })).toBeDisabled()
  })

  it('invalidates an available result when the selected time changes', async () => {
    await renderLoaded()
    await checkAvailable()

    fireEvent.change(screen.getByLabelText('End time'), { target: { value: '17:00' } })

    expect(screen.queryByText('This space is available for the selected time.')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Book this space' })).toBeDisabled()
  })

  it('ignores an in-flight availability response after the selected time changes', async () => {
    let resolveAvailability!: (response: AvailabilityResponse) => void
    checkSpaceAvailability.mockReturnValue(new Promise((resolve) => { resolveAvailability = resolve }))
    await renderLoaded()
    selectBookingTime()
    fireEvent.click(screen.getByRole('button', { name: 'Check Availability' }))

    fireEvent.change(screen.getByLabelText('End time'), { target: { value: '17:00' } })
    await act(async () => resolveAvailability(availableResponse))

    expect(screen.queryByText('This space is available for the selected time.')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Book this space' })).toBeDisabled()
  })

  it('shows an explicit confirmation before creating a booking', async () => {
    await renderLoaded()
    await checkAvailable()

    fireEvent.click(screen.getByRole('button', { name: 'Book this space' }))

    const dialog = screen.getByRole('dialog', { name: 'Confirm booking' })
    expect(within(dialog).getByText(space.name, { exact: false })).toBeInTheDocument()
    expect(within(dialog).getByText('2026-08-13', { exact: false })).toBeInTheDocument()
    expect(within(dialog).getByText('14:00', { exact: false })).toBeInTheDocument()
    expect(within(dialog).getByText('16:00', { exact: false })).toBeInTheDocument()
    expect(createBooking).not.toHaveBeenCalled()
  })

  it('creates a booking after confirmation and renders the success state', async () => {
    createBooking.mockResolvedValue(bookingResponse)
    await renderLoaded()
    await checkAvailable()
    fireEvent.click(screen.getByRole('button', { name: 'Book this space' }))

    fireEvent.click(screen.getByRole('button', { name: 'Confirm Booking' }))

    await waitFor(() => expect(createBooking).toHaveBeenCalledWith({
      spaceId: 7,
      startDateTime: '2026-08-13T14:00:00',
      endDateTime: '2026-08-13T16:00:00',
    }))
    expect(await screen.findByText('Booking confirmed')).toBeInTheDocument()
    expect(screen.getByText('Booking ID: 42')).toBeInTheDocument()
    expect(screen.getByText('Space: COM3 Study Room 01')).toBeInTheDocument()
    expect(screen.getByText('Date/time: 2026-08-13 14:00 – 16:00')).toBeInTheDocument()
    expect(screen.getByText('Status: CONFIRMED')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Confirm booking' })).not.toBeInTheDocument())
    expect(screen.getByRole('button', { name: 'Book this space' })).toBeDisabled()
  })

  it('shows and protects the create-booking loading state', async () => {
    createBooking.mockReturnValue(new Promise(() => {}))
    await renderLoaded()
    await checkAvailable()
    fireEvent.click(screen.getByRole('button', { name: 'Book this space' }))

    fireEvent.click(screen.getByRole('button', { name: 'Confirm Booking' }))

    expect(await screen.findByRole('button', { name: 'Confirming...' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeDisabled()
    expect(createBooking).toHaveBeenCalledOnce()
  })

  it('handles a create-booking conflict and requires availability to be checked again', async () => {
    createBooking.mockRejectedValue({ isAxiosError: true, response: { status: 409, data: { code: 'BOOKING_CONFLICT' } } })
    await renderLoaded()
    await checkAvailable()
    fireEvent.click(screen.getByRole('button', { name: 'Book this space' }))

    fireEvent.click(screen.getByRole('button', { name: 'Confirm Booking' }))

    expect(await screen.findByText('This space is no longer available for the selected time. Please check availability again.')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Confirm booking' })).not.toBeInTheDocument())
    expect(screen.getByRole('button', { name: 'Book this space' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Check Availability' })).toBeEnabled()
  })

  it('shows a safe availability request error', async () => {
    checkSpaceAvailability.mockRejectedValue(new Error('Facilities service unavailable'))
    await renderLoaded()
    selectBookingTime()

    fireEvent.click(screen.getByRole('button', { name: 'Check Availability' }))

    expect(await screen.findByText('Facilities service unavailable')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Book this space' })).toBeDisabled()
  })

  it('shows a generic create-booking error without bypassing confirmation', async () => {
    createBooking.mockRejectedValue(new Error('Booking service unavailable'))
    await renderLoaded()
    await checkAvailable()
    fireEvent.click(screen.getByRole('button', { name: 'Book this space' }))

    fireEvent.click(screen.getByRole('button', { name: 'Confirm Booking' }))

    expect(await screen.findByText('Booking service unavailable')).toBeInTheDocument()
    expect(createBooking).toHaveBeenCalledOnce()
  })
})
