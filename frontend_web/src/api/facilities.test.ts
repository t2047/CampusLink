import { afterEach, describe, expect, it, vi } from 'vitest'
import { apiClient, apiErrorMessage } from './client'
import { facilitiesApi } from './facilities'

describe('facilitiesApi', () => {
  afterEach(() => vi.restoreAllMocks())

  it('searches spaces with only populated filters and repeated equipment parameters', async () => {
    const get = vi.spyOn(apiClient, 'get').mockResolvedValue({ data: [] })

    await facilitiesApi.searchSpaces({
      query: ' study ',
      building: '',
      spaceType: 'STUDY_ROOM',
      minimumCapacity: 4,
      equipment: ['Projector', ' ', 'Whiteboard'],
      startDateTime: '2026-08-11T14:00',
      endDateTime: '2026-08-11T16:00',
    })

    expect(get).toHaveBeenCalledOnce()
    const params = get.mock.calls[0][1]?.params as URLSearchParams
    expect(Array.from(params.entries())).toEqual([
      ['query', 'study'],
      ['spaceType', 'STUDY_ROOM'],
      ['minimumCapacity', '4'],
      ['equipment', 'Projector'],
      ['equipment', 'Whiteboard'],
      ['startDateTime', '2026-08-11T14:00'],
      ['endDateTime', '2026-08-11T16:00'],
    ])
    expect(params.toString()).not.toContain('Z')
    expect(params.toString()).not.toMatch(/%2B\d{2}%3A\d{2}/)
  })

  it('gets a space by ID', async () => {
    const get = vi.spyOn(apiClient, 'get').mockResolvedValue({ data: { spaceId: 7 } })

    await facilitiesApi.getSpace(7)

    expect(get).toHaveBeenCalledWith('/facilities/spaces/7')
  })

  it('checks availability with local datetime query parameters', async () => {
    const response = {
      available: true,
      reasonCode: null,
      space: { spaceId: 7 },
      startDateTime: '2026-08-13T14:00:00',
      endDateTime: '2026-08-13T16:00:00',
    }
    const get = vi.spyOn(apiClient, 'get').mockResolvedValue({ data: response })

    await expect(facilitiesApi.checkSpaceAvailability(7, '2026-08-13T14:00:00', '2026-08-13T16:00:00')).resolves.toBe(response)

    expect(get).toHaveBeenCalledOnce()
    expect(get.mock.calls[0][0]).toBe('/facilities/spaces/7/availability')
    const params = get.mock.calls[0][1]?.params as URLSearchParams
    expect(Array.from(params.entries())).toEqual([
      ['startDateTime', '2026-08-13T14:00:00'],
      ['endDateTime', '2026-08-13T16:00:00'],
    ])
    expect(params.toString()).not.toContain('Z')
    expect(params.toString()).not.toMatch(/%2B\d{2}%3A\d{2}/)
  })

  it('creates a booking with the backend request body and returns the 201 response data', async () => {
    const request = {
      spaceId: 7,
      startDateTime: '2026-08-13T14:00:00',
      endDateTime: '2026-08-13T16:00:00',
    }
    const response = {
      success: true,
      bookingId: 42,
      space: { spaceId: 7, name: 'COM3 Study Room 01' },
      startDateTime: request.startDateTime,
      endDateTime: request.endDateTime,
      status: 'CONFIRMED',
      createdAt: '2026-08-12T10:00:00',
      updatedAt: '2026-08-12T10:00:00',
    }
    const post = vi.spyOn(apiClient, 'post').mockResolvedValue({ status: 201, data: response })

    await expect(facilitiesApi.createBooking(request)).resolves.toBe(response)
    expect(post).toHaveBeenCalledWith('/facilities/bookings', request)
  })

  it('preserves a booking conflict response for page-level handling', async () => {
    const conflict = { isAxiosError: true, response: { status: 409, data: { code: 'BOOKING_CONFLICT' } } }
    vi.spyOn(apiClient, 'post').mockRejectedValue(conflict)

    await expect(facilitiesApi.createBooking({
      spaceId: 7,
      startDateTime: '2026-08-13T14:00:00',
      endDateTime: '2026-08-13T16:00:00',
    })).rejects.toBe(conflict)
  })

  it('lists bookings owned by the authenticated user', async () => {
    const response = [{ bookingId: 42, status: 'CONFIRMED' }]
    const get = vi.spyOn(apiClient, 'get').mockResolvedValue({ data: response })

    await expect(facilitiesApi.listBookings()).resolves.toBe(response)
    expect(get).toHaveBeenCalledWith('/facilities/bookings')
  })

  it('gets one owned booking by ID', async () => {
    const response = { bookingId: 42, status: 'CONFIRMED' }
    const get = vi.spyOn(apiClient, 'get').mockResolvedValue({ data: response })

    await expect(facilitiesApi.getBooking(42)).resolves.toBe(response)
    expect(get).toHaveBeenCalledWith('/facilities/bookings/42')
  })

  it('cancels a booking with PATCH and preserves backend errors for UI mapping', async () => {
    const response = { bookingId: 42, status: 'CANCELLED' }
    const patch = vi.spyOn(apiClient, 'patch').mockResolvedValueOnce({ data: response })

    await expect(facilitiesApi.cancelBooking(42)).resolves.toBe(response)
    expect(patch).toHaveBeenCalledWith('/facilities/bookings/42/cancel')

    const conflict = {
      isAxiosError: true,
      message: 'Request failed with status code 409',
      response: { status: 409, data: { code: 'BOOKING_CANCELLATION_NOT_ALLOWED', error: 'A booking cannot be cancelled after its start time' } },
    }
    patch.mockRejectedValueOnce(conflict)

    await expect(facilitiesApi.cancelBooking(42)).rejects.toBe(conflict)
    expect(apiErrorMessage(conflict)).toBe('A booking cannot be cancelled after its start time')
  })

  it('submits a maintenance request without sending user identity', async () => {
    const request = {
      spaceId: 7,
      facilityType: 'projector',
      description: 'The projector does not turn on.',
      priority: 'HIGH' as const,
    }
    const response = { success: true, ticketId: 81, status: 'SUBMITTED' }
    const post = vi.spyOn(apiClient, 'post').mockResolvedValue({ status: 201, data: response })

    await expect(facilitiesApi.submitMaintenanceRequest(request)).resolves.toBe(response)
    expect(post).toHaveBeenCalledWith('/facilities/maintenance', request)
    expect(post.mock.calls[0][1]).not.toHaveProperty('userId')
  })

  it('lists owned maintenance requests and gets one request by ID', async () => {
    const response = [{ ticketId: 81, status: 'SUBMITTED' }]
    const get = vi.spyOn(apiClient, 'get').mockResolvedValueOnce({ data: response })
      .mockResolvedValueOnce({ data: response[0] })

    await expect(facilitiesApi.listMaintenanceRequests()).resolves.toBe(response)
    await expect(facilitiesApi.getMaintenanceRequest(81)).resolves.toBe(response[0])
    expect(get).toHaveBeenNthCalledWith(1, '/facilities/maintenance')
    expect(get).toHaveBeenNthCalledWith(2, '/facilities/maintenance/81')
  })

  it('preserves maintenance validation errors for the shared error mapper', async () => {
    const validationError = {
      isAxiosError: true,
      message: 'Request failed with status code 400',
      response: { status: 400, data: { errors: { description: 'must not be blank' } } },
    }
    vi.spyOn(apiClient, 'post').mockRejectedValue(validationError)

    await expect(facilitiesApi.submitMaintenanceRequest({
      spaceId: 7,
      facilityType: 'projector',
      description: '',
    })).rejects.toBe(validationError)
    expect(apiErrorMessage(validationError)).toBe('must not be blank')
  })
})
