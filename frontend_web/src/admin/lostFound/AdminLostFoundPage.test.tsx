import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AdminLostFoundPage } from './AdminLostFoundPage'

const apiMocks = vi.hoisted(() => ({
  getOverview: vi.fn(),
  searchReports: vi.fn(),
  delist: vi.fn(),
  restore: vi.fn(),
  deleteReport: vi.fn(),
  searchAudit: vi.fn(),
}))

vi.mock('../../api/adminLostFound', () => ({
  getAdminLostFoundOverview: apiMocks.getOverview,
  searchAdminLostFoundReports: apiMocks.searchReports,
  delistAdminReport: apiMocks.delist,
  restoreAdminReport: apiMocks.restore,
  deleteAdminReport: apiMocks.deleteReport,
  searchAdminAuditLogs: apiMocks.searchAudit,
}))

vi.mock('./AdminAuditLogsSection', () => ({
  AdminAuditLogsSection: () => <div>Audit logs section</div>,
}))

const overview = {
  totalReports: 12,
  openReports: 7,
  claimedReports: 3,
  closedReports: 2,
  lostReports: 5,
  foundReports: 7,
  submittedClaims: 4,
  hiddenReports: 1,
}

const reports = {
  content: [{
    id: 42,
    reportType: 'FOUND',
    itemName: 'Black Headphones',
    category: 'ELECTRONICS',
    colour: 'Black',
    location: 'Central Library',
    eventDate: '2026-08-07',
    status: 'OPEN',
    adminHidden: false,
    createdByEmail: 'owner@u.nus.edu',
    createdAt: '2026-08-07T03:00:00Z',
    updatedAt: '2026-08-07T03:00:00Z',
  }],
  page: 0,
  size: 25,
  totalElements: 1,
  totalPages: 1,
  first: true,
  last: true,
}

const hiddenReports = {
  ...reports,
  content: [{
    ...reports.content[0],
    id: 43,
    itemName: 'Hidden Poster',
    adminHidden: true,
  }],
}

function renderPage(initialEntry = '/admin/lost-found') {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <AdminLostFoundPage />
    </MemoryRouter>,
  )
}

describe('AdminLostFoundPage', () => {
  beforeEach(() => {
    apiMocks.getOverview.mockResolvedValue(overview)
    apiMocks.searchReports.mockResolvedValue(reports)
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('renders overview metrics and the report table without sensitive claim data', async () => {
    renderPage()

    expect(await screen.findByRole('heading', { name: 'Lost & Found' })).toBeInTheDocument()
    expect(await screen.findByText('Black Headphones')).toBeInTheDocument()
    expect(screen.getByText('12')).toBeInTheDocument()
    expect(screen.queryByText('7 lost · 7 found')).not.toBeInTheDocument()
    expect(screen.getByText('5 lost · 7 found')).toBeInTheDocument()
    expect(screen.getByText('1')).toBeInTheDocument() // hidden reports metric
    expect(screen.getByText('owner@u.nus.edu')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'View report' })).toHaveAttribute('href', '/lost-found/42')
    expect(screen.queryByText(/proof/i)).not.toBeInTheDocument()
  })

  it('shows a hidden chip for delisted reports', async () => {
    apiMocks.searchReports.mockResolvedValue(hiddenReports)

    renderPage()

    expect(await screen.findByText('Hidden Poster')).toBeInTheDocument()
    const row = screen.getByRole('row', { name: /Hidden Poster/ })
    expect(within(row).getByText('Hidden')).toBeInTheDocument()
    expect(within(row).getByRole('button', { name: 'Restore' })).toBeInTheDocument()
  })

  it('submits trimmed keyword filters and resets pagination', async () => {
    renderPage('/admin/lost-found?page=3')
    await screen.findByText('Black Headphones')

    fireEvent.change(screen.getByLabelText('Keyword'), { target: { value: '  headphones  ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Search' }))

    await waitFor(() => expect(apiMocks.searchReports).toHaveBeenLastCalledWith(expect.objectContaining({
      keyword: 'headphones',
      page: 0,
      size: 25,
    })))
  })

  it('propagates the hidden visibility filter to the reports endpoint', async () => {
    renderPage('/admin/lost-found?adminHidden=true')

    await waitFor(() => expect(apiMocks.searchReports).toHaveBeenCalledWith(expect.objectContaining({
      adminHidden: 'true',
      page: 0,
    })))
  })

  it('renders an empty state when no reports match', async () => {
    apiMocks.searchReports.mockResolvedValue({ ...reports, content: [], totalElements: 0 })

    renderPage()

    expect(await screen.findByText('No reports found')).toBeInTheDocument()
    expect(screen.getByText('Try clearing one or more filters.')).toBeInTheDocument()
  })

  it('renders API errors without exposing a broken table', async () => {
    apiMocks.getOverview.mockRejectedValue(new Error('Service unavailable'))

    renderPage()

    expect(await screen.findByText('Service unavailable')).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('delists a report with the required reason and refreshes', async () => {
    apiMocks.delist.mockResolvedValue({ ...reports.content[0], adminHidden: true })

    renderPage()
    await screen.findByText('Black Headphones')

    fireEvent.click(screen.getByRole('button', { name: 'Delist' }))
    expect(await screen.findByRole('dialog')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Confirm' })).toBeDisabled()

    fireEvent.change(screen.getByRole('textbox', { name: 'Reason' }), { target: { value:'  Inappropriate content  ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }))

    await waitFor(() => expect(apiMocks.delist).toHaveBeenCalledWith(42, 'Inappropriate content'))
    await waitFor(() => expect(apiMocks.searchReports).toHaveBeenCalledTimes(2))
  })

  it('restores a hidden report with the required reason', async () => {
    apiMocks.searchReports.mockResolvedValue(hiddenReports)
    apiMocks.restore.mockResolvedValue(hiddenReports.content[0])

    renderPage()
    await screen.findByText('Hidden Poster')

    fireEvent.click(screen.getByRole('button', { name: 'Restore' }))
    expect(await screen.findByRole('dialog')).toBeInTheDocument()
    fireEvent.change(screen.getByRole('textbox', { name: 'Reason' }), { target: { value:'Wrongly delisted' } })
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }))

    await waitFor(() => expect(apiMocks.restore).toHaveBeenCalledWith(43, 'Wrongly delisted'))
    await waitFor(() => expect(apiMocks.searchReports).toHaveBeenCalledTimes(2))
  })

  it('deletes a report after confirming with a reason', async () => {
    apiMocks.deleteReport.mockResolvedValue(undefined)

    renderPage()
    await screen.findByText('Black Headphones')

    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
    expect(await screen.findByRole('dialog')).toBeInTheDocument()
    fireEvent.change(screen.getByRole('textbox', { name: 'Reason' }), { target: { value:'Community guidelines violation' } })
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }))

    await waitFor(() => expect(apiMocks.deleteReport).toHaveBeenCalledWith(42, 'Community guidelines violation'))
    await waitFor(() => expect(apiMocks.searchReports).toHaveBeenCalledTimes(2))
  })

  it('switches to the audit logs tab without refetching reports', async () => {
    renderPage()
    await screen.findByText('Black Headphones')

    fireEvent.click(screen.getByRole('tab', { name: 'Audit Logs' }))

    expect(await screen.findByText('Audit logs section')).toBeInTheDocument()
    expect(apiMocks.searchReports).toHaveBeenCalledTimes(1)
  })
})
