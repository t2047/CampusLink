import { describe, expect, it, vi } from 'vitest'
import { apiClient } from './client'
import { createReport, searchReports } from './lostFound'

describe('createReport', () => {
  it('creates a multipart request with report JSON and images', async () => {
    const post = vi.spyOn(apiClient, 'post').mockResolvedValue({ data: { id: 42 } })
    const image = new File(['image'], 'item.png', { type: 'image/png' })

    await createReport({
      reportType: 'FOUND', itemName: 'Black headphones', category: 'ELECTRONICS',
      description: 'Black headphones in a small case', colour: 'Black', location: 'COM1',
      eventDate: '2026-08-06', timeDescription: 'Afternoon',
    }, [image])

    const body = post.mock.calls[0][1]
    expect(body).toBeInstanceOf(FormData)
    expect((body as FormData).get('report')).toBeInstanceOf(Blob)
    expect((body as FormData).getAll('images')).toEqual([image])
  })

  it('passes combined search filters as query parameters', async () => {
    const get = vi.spyOn(apiClient, 'get').mockResolvedValue({ data: { content: [] } })

    await searchReports({
      reportType: 'FOUND', status: 'OPEN', category: 'ELECTRONICS', colour: 'black',
      location: 'library', dateFrom: '2026-08-01', dateTo: '2026-08-06', page: 2,
    })

    expect(get).toHaveBeenCalledWith('/lost-found/reports', {
      params: {
        reportType: 'FOUND', status: 'OPEN', category: 'ELECTRONICS', colour: 'black',
        location: 'library', dateFrom: '2026-08-01', dateTo: '2026-08-06', page: 2,
      },
    })
  })
})
