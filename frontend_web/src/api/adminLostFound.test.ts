import { afterEach, describe, expect, it, vi } from 'vitest'
import { getAdminLostFoundOverview, searchAdminLostFoundReports } from './adminLostFound'
import { apiClient } from './client'

describe('admin Lost & Found API', () => {
  afterEach(() => vi.restoreAllMocks())

  it('loads the administrator overview', async () => {
    const get = vi.spyOn(apiClient, 'get').mockResolvedValue({ data: { totalReports: 12 } })

    await getAdminLostFoundOverview()

    expect(get).toHaveBeenCalledWith('/admin/lost-found/overview')
  })

  it('passes administrator filters and pagination as query parameters', async () => {
    const get = vi.spyOn(apiClient, 'get').mockResolvedValue({ data: { content: [] } })
    const params = {
      keyword: 'headphones',
      reportType: 'FOUND',
      status: 'OPEN',
      category: 'ELECTRONICS',
      page: 2,
      size: 25,
    }

    await searchAdminLostFoundReports(params)

    expect(get).toHaveBeenCalledWith('/admin/lost-found/reports', { params })
  })
})
