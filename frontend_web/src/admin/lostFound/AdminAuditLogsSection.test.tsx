import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AdminAuditLogsSection } from './AdminAuditLogsSection'

const apiMocks = vi.hoisted(() => ({
  searchAudit: vi.fn(),
}))

vi.mock('../../api/adminLostFound', () => ({
  searchAdminAuditLogs: apiMocks.searchAudit,
}))

const auditPage = {
  content: [{
    id: 1,
    reportId: 42,
    itemName: 'Black Headphones',
    action: 'REPORT_DELISTED',
    actorEmail: 'admin@campuslink.com',
    reason: 'Inappropriate content',
    detail: 'adminHidden=false→true',
    createdAt: '2026-08-07T03:00:00Z',
  }],
  page: 0,
  size: 25,
  totalElements: 1,
  totalPages: 1,
  first: true,
  last: true,
}

describe('AdminAuditLogsSection', () => {
  beforeEach(() => {
    apiMocks.searchAudit.mockResolvedValue(auditPage)
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('renders audit log rows with action, actor and reason', async () => {
    render(<AdminAuditLogsSection />)

    expect(await screen.findByText('Black Headphones')).toBeInTheDocument()
    expect(screen.getByText('#42')).toBeInTheDocument()
    expect(screen.getByText('Delisted')).toBeInTheDocument()
    expect(screen.getByText('admin@campuslink.com')).toBeInTheDocument()
    expect(screen.getByText('Inappropriate content')).toBeInTheDocument()
    expect(screen.getByText('adminHidden=false→true')).toBeInTheDocument()
    expect(apiMocks.searchAudit).toHaveBeenCalledWith(expect.objectContaining({ size: 25, sort: 'createdAt,desc' }))
  })

  it('renders an empty state when no audit logs match', async () => {
    apiMocks.searchAudit.mockResolvedValue({ ...auditPage, content: [], totalElements: 0 })

    render(<AdminAuditLogsSection />)

    expect(await screen.findByText('No audit logs found')).toBeInTheDocument()
  })

  it('renders API errors', async () => {
    apiMocks.searchAudit.mockRejectedValue(new Error('Service unavailable'))

    render(<AdminAuditLogsSection />)

    expect(await screen.findByText('Service unavailable')).toBeInTheDocument()
  })

  it('submits filters and resets pagination', async () => {
    render(<AdminAuditLogsSection />)
    await screen.findByText('Black Headphones')

    fireEvent.change(screen.getByLabelText('Keyword'), { target: { value: '  headphones  ' } })
    fireEvent.change(screen.getByLabelText('Report ID'), { target: { value: '42' } })
    fireEvent.click(screen.getByRole('button', { name: 'Search' }))

    await waitFor(() => expect(apiMocks.searchAudit).toHaveBeenLastCalledWith(expect.objectContaining({
      keyword: 'headphones',
      reportId: 42,
      page: 0,
    })))
  })
})
