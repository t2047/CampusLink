import { afterEach, describe, expect, it, vi } from 'vitest'
import type { AuditAction } from '../types'
import {
  approveAdminClaim,
  deleteAdminReport,
  delistAdminReport,
  getAdminClaimDetail,
  getAdminLostFoundOverview,
  rejectAdminClaim,
  restoreAdminReport,
  searchAdminAuditLogs,
  searchAdminClaims,
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

describe('admin claim API', () => {
  afterEach(() => vi.restoreAllMocks())

  it('searches admin claims with every supported query parameter and returns response data', async () => {
    const page = { content: [] }
    const get = vi.spyOn(apiClient, 'get').mockResolvedValue({ data: page })
    const params = {
      status: 'SUBMITTED' as const,
      keyword: 'headphones',
      reportId: 42,
      claimantEmail: 'claimant@nus.edu.sg',
      reportOwnerEmail: 'owner@nus.edu.sg',
      adminHidden: false,
      page: 2,
      size: 25,
      sort: 'createdAt,desc',
    }

    const result = await searchAdminClaims(params)

    expect(get).toHaveBeenCalledWith('/admin/lost-found/claims', { params })
    expect(result).toBe(page)
  })

  it('loads an admin claim detail and returns response data', async () => {
    const detail = { id: 42, status: 'SUBMITTED' }
    const get = vi.spyOn(apiClient, 'get').mockResolvedValue({ data: detail })

    const result = await getAdminClaimDetail(42)

    expect(get).toHaveBeenCalledWith('/admin/lost-found/claims/42')
    expect(result).toBe(detail)
  })

  it('approves an admin claim with the decision note body', async () => {
    const detail = { id: 42, status: 'APPROVED' }
    const post = vi.spyOn(apiClient, 'post').mockResolvedValue({ data: detail })
    const input = { decisionNote: 'Ownership evidence verified' }

    const result = await approveAdminClaim(42, input)

    expect(post).toHaveBeenCalledWith('/admin/lost-found/claims/42/approve', input)
    expect(result).toBe(detail)
  })

  it('rejects an admin claim with the required decision note body', async () => {
    const detail = { id: 42, status: 'REJECTED' }
    const post = vi.spyOn(apiClient, 'post').mockResolvedValue({ data: detail })
    const input = { decisionNote: 'Ownership evidence was insufficient' }

    const result = await rejectAdminClaim(42, input)

    expect(post).toHaveBeenCalledWith('/admin/lost-found/claims/42/reject', input)
    expect(result).toBe(detail)
  })
})
