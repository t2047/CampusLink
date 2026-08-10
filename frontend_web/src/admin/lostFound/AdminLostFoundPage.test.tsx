import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AdminLostFoundPage } from './AdminLostFoundPage'

const apiMocks = vi.hoisted(() => ({
  getOverview: vi.fn(),
  searchReports: vi.fn(),
}))

vi.mock('../../api/adminLostFound', () => ({
  getAdminLostFoundOverview: apiMocks.getOverview,
  searchAdminLostFoundReports: apiMocks.searchReports,
}))

const overview = {
  totalReports: 12,
  openReports: 7,
  claimedReports: 3,
  closedReports: 2,
  lostReports: 5,
  foundReports: 7,
  submittedClaims: 4,
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
    expect(screen.getByText('owner@u.nus.edu')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'View report' })).toHaveAttribute('href', '/lost-found/42')
    expect(screen.queryByText(/proof/i)).not.toBeInTheDocument()
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
})
