import { afterEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from './client'
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
})
