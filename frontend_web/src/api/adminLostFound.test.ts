import { afterEach, describe, expect, it, vi } from 'vitest'
import type { AuditAction } from '../types'
import {
  deleteAdminReport,
  delistAdminReport,
  getAdminLostFoundOverview,
  restoreAdminReport,
  searchAdminAuditLogs,
  searchAdminLostFoundReports,
} from './adminLostFound'
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

  it('delists a report with the admin reason', async () => {
    const post = vi.spyOn(apiClient, 'post').mockResolvedValue({ data: { id: 42, adminHidden: true } })

    await delistAdminReport(42, 'Inappropriate content')

    expect(post).toHaveBeenCalledWith('/admin/lost-found/reports/42/delist', {
      reason: 'Inappropriate content',
    })
  })

  it('restores a report with the admin reason', async () => {
    const post = vi.spyOn(apiClient, 'post').mockResolvedValue({ data: { id: 42, adminHidden: false } })

    await restoreAdminReport(42, 'Wrongly delisted')

    expect(post).toHaveBeenCalledWith('/admin/lost-found/reports/42/restore', {
      reason: 'Wrongly delisted',
    })
  })

  it('deletes a report with the admin reason', async () => {
    const post = vi.spyOn(apiClient, 'post').mockResolvedValue({ data: undefined })

    await deleteAdminReport(42, 'Community guidelines violation')

    expect(post).toHaveBeenCalledWith('/admin/lost-found/reports/42/delete', {
      reason: 'Community guidelines violation',
    })
  })

  it('loads audit logs with filters and pagination', async () => {
    const get = vi.spyOn(apiClient, 'get').mockResolvedValue({ data: { content: [] } })
    const params = { reportId: 42, action: 'REPORT_DELISTED' as AuditAction, page: 1, size: 25 }

    await searchAdminAuditLogs(params)

    expect(get).toHaveBeenCalledWith('/admin/lost-found/audit-logs', { params })
  })
})
