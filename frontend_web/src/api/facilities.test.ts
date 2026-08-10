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
})
