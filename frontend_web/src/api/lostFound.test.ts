import { describe, expect, it, vi } from 'vitest'
import { apiClient } from './client'
import {
  closeReport,
  createReport,
  deleteReport,
  searchReports,
  suggestCategory,
  updateReport,
} from './lostFound'

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

describe('updateReport', () => {
  it('sends a multipart PUT to the report endpoint', async () => {
    const put = vi.spyOn(apiClient, 'put').mockResolvedValue({ data: { id: 42 } })
    const image = new File(['image'], 'item.png', { type: 'image/png' })

    await updateReport(42, {
      itemName: 'White Earphones', category: 'ELECTRONICS', description: 'White earphones in a case',
      colour: 'White', location: 'Yale-NUS Library', eventDate: '2026-08-06', timeDescription: 'Morning',
    }, [image])

    expect(put).toHaveBeenCalledWith('/lost-found/reports/42', expect.any(FormData), expect.any(Object))
    const body = put.mock.calls[0][1] as FormData
    expect(body.get('report')).toBeInstanceOf(Blob)
    expect(body.getAll('images')).toEqual([image])
  })
})

describe('closeReport', () => {
  it('posts to the close endpoint', async () => {
    const post = vi.spyOn(apiClient, 'post').mockResolvedValue({ data: { id: 42, status: 'CLOSED' } })

    const result = await closeReport(42)

    expect(post).toHaveBeenCalledWith('/lost-found/reports/42/close')
    expect(result.status).toBe('CLOSED')
  })
})

describe('deleteReport', () => {
  it('deletes the report endpoint', async () => {
    const del = vi.spyOn(apiClient, 'delete').mockResolvedValue({ data: {} })

    await deleteReport(42)

    expect(del).toHaveBeenCalledWith('/lost-found/reports/42')
  })
})

describe('suggestCategory', () => {
  it('posts the item name and returns the suggested category', async () => {
    const post = vi.spyOn(apiClient, 'post').mockResolvedValue({ data: { category: 'ELECTRONICS' } })

    const result = await suggestCategory('黑色耳机')

    expect(post).toHaveBeenCalledWith('/lost-found/agent/classify', { itemName: '黑色耳机' })
    expect(result).toBe('ELECTRONICS')
  })

  it('returns null when the agent is unsure', async () => {
    vi.spyOn(apiClient, 'post').mockResolvedValue({ data: { category: null } })

    await expect(suggestCategory('mystery box')).resolves.toBeNull()
  })

  it('returns null when the category key is absent', async () => {
    vi.spyOn(apiClient, 'post').mockResolvedValue({ data: {} })

    await expect(suggestCategory('mystery box')).resolves.toBeNull()
  })
})
