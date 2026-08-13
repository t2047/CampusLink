import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AdminClaimSummary, PageResponse } from '../../types'
import { AdminClaimsSection } from './AdminClaimsSection'

const apiMocks = vi.hoisted(() => ({
  searchClaims: vi.fn(),
}))

vi.mock('../../api/adminLostFound', () => ({
  searchAdminClaims: apiMocks.searchClaims,
}))

const claim: AdminClaimSummary = {
  id: 42,
  status: 'SUBMITTED',
  proofSummary: 'The case has a scratch near the hinge',
  decisionNote: null,
  claimant: { id: 7, email: 'claimant@nus.edu.sg' },
  report: {
    id: 12,
    reportType: 'FOUND',
    itemName: 'Black Headphones',
    category: 'ELECTRONICS',
    colour: 'Black',
    location: 'Central Library',
    eventDate: '2026-08-07',
    status: 'OPEN',
    adminHidden: false,
    owner: { id: 8, email: 'owner@nus.edu.sg' },
  },
  createdAt: '2026-08-07T03:00:00Z',
  updatedAt: '2026-08-07T03:00:00Z',
}

const claimPage: PageResponse<AdminClaimSummary> = {
  content: [claim],
  page: 0,
  size: 25,
  totalElements: 1,
  totalPages: 1,
  first: true,
  last: true,
}

function LocationProbe() {
  const location = useLocation()
  return <div aria-label="Current location">{`${location.pathname}${location.search}`}</div>
}

function renderSection(initialEntry = '/admin/lost-found?tab=claims&status=SUBMITTED') {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <AdminClaimsSection />
      <LocationProbe />
    </MemoryRouter>,
  )
}

describe('AdminClaimsSection', () => {
  beforeEach(() => {
    apiMocks.searchClaims.mockResolvedValue(claimPage)
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('loads the default submitted queue and renders the read-only eight-column table', async () => {
    renderSection()

    const table = await screen.findByRole('table', { name: 'Lost and Found administration claims' })
    expect(apiMocks.searchClaims).toHaveBeenCalledWith({
      status: 'SUBMITTED',
      page: 0,
      size: 25,
      sort: 'createdAt,desc',
    })
    const headers = within(table).getAllByRole('columnheader').map((header) => header.textContent)
    expect(headers).toEqual([
      'Claim ID', 'Status', 'Item', 'Claimant', 'Report Owner', 'Location', 'Submitted At', 'Actions',
    ])
    expect(within(table).getByText('#42')).toBeInTheDocument()
    expect(within(table).getByText('Submitted')).toBeInTheDocument()
    expect(within(table).getByText('Black Headphones')).toBeInTheDocument()
    expect(within(table).getByText('claimant@nus.edu.sg')).toBeInTheDocument()
    expect(within(table).getByText('owner@nus.edu.sg')).toBeInTheDocument()
    expect(within(table).getByText('Central Library')).toBeInTheDocument()
    expect(within(table).getByText(/7 Aug 2026/)).toBeInTheDocument()
    expect(within(table).queryByText(claim.proofSummary)).not.toBeInTheDocument()
    expect(within(table).queryByText(/decision note/i)).not.toBeInTheDocument()
    expect(within(table).queryByRole('button', { name: /approve/i })).not.toBeInTheDocument()
    expect(within(table).queryByRole('button', { name: /reject/i })).not.toBeInTheDocument()
  })

  it('builds a View link with only the normalized Claims list context', async () => {
    renderSection('/admin/lost-found?tab=claims&status=SUBMITTED&keyword=wallet&page=2&returnUrl=%2Fadmin&unknown=x')

    const link = await screen.findByRole('link', { name: 'View' })
    expect(link).toHaveAttribute(
      'href',
      '/admin/lost-found/claims/42?status=SUBMITTED&keyword=wallet&page=2',
    )
  })

  it.each([
    ['APPROVED', 'Approved'],
    ['REJECTED', 'Rejected'],
  ] as const)('passes status=%s and renders its label', async (status, label) => {
    apiMocks.searchClaims.mockResolvedValue({
      ...claimPage,
      content: [{ ...claim, status }],
    })
    renderSection(`/admin/lost-found?tab=claims&status=${status}`)

    expect(await screen.findByText(label)).toBeInTheDocument()
    expect(apiMocks.searchClaims).toHaveBeenCalledWith(expect.objectContaining({ status }))
  })

  it('keeps ALL in the URL but omits status from the Backend request', async () => {
    renderSection('/admin/lost-found?tab=claims&status=ALL')

    await screen.findByText('Black Headphones')
    expect(apiMocks.searchClaims).toHaveBeenCalledWith({
      page: 0,
      size: 25,
      sort: 'createdAt,desc',
    })
    expect(screen.getByLabelText('Current location')).toHaveTextContent('status=ALL')
  })

  it('trims keyword and normalizes invalid status and page values', async () => {
    renderSection('/admin/lost-found?tab=claims&status=OPEN&keyword=%20%20wallet%20%20&page=-3&unknown=x')

    await waitFor(() => expect(apiMocks.searchClaims).toHaveBeenCalledWith({
      status: 'SUBMITTED',
      keyword: 'wallet',
      page: 0,
      size: 25,
      sort: 'createdAt,desc',
    }))
    expect(screen.getByLabelText('Current location')).toHaveTextContent(
      '/admin/lost-found?tab=claims&status=SUBMITTED&keyword=wallet',
    )
  })

  it('applies trimmed filters and resets page to zero', async () => {
    renderSection('/admin/lost-found?tab=claims&status=APPROVED&keyword=old&page=2')
    await screen.findByText('Black Headphones')

    fireEvent.mouseDown(screen.getByRole('combobox', { name: 'Status' }))
    fireEvent.click(screen.getByRole('option', { name: 'Rejected' }))
    fireEvent.change(screen.getByLabelText('Keyword'), { target: { value: '  wallet  ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Search' }))

    await waitFor(() => expect(apiMocks.searchClaims).toHaveBeenLastCalledWith({
      status: 'REJECTED',
      keyword: 'wallet',
      page: 0,
      size: 25,
      sort: 'createdAt,desc',
    }))
    expect(screen.getByLabelText('Current location')).toHaveTextContent(
      '/admin/lost-found?tab=claims&status=REJECTED&keyword=wallet',
    )
  })

  it('resets filters to the default submitted queue', async () => {
    renderSection('/admin/lost-found?tab=claims&status=ALL&keyword=wallet&page=2')
    await screen.findByText('Black Headphones')

    fireEvent.click(screen.getByRole('button', { name: 'Reset' }))

    await waitFor(() => expect(apiMocks.searchClaims).toHaveBeenLastCalledWith({
      status: 'SUBMITTED',
      page: 0,
      size: 25,
      sort: 'createdAt,desc',
    }))
    expect(screen.getByLabelText('Current location')).toHaveTextContent(
      '/admin/lost-found?tab=claims&status=SUBMITTED',
    )
  })

  it('converts MUI pagination to the Backend zero-based page', async () => {
    apiMocks.searchClaims.mockResolvedValue({ ...claimPage, totalPages: 3, last: false })
    renderSection()
    await screen.findByText('Black Headphones')

    fireEvent.click(screen.getByRole('button', { name: 'Go to page 2' }))

    await waitFor(() => expect(apiMocks.searchClaims).toHaveBeenLastCalledWith({
      status: 'SUBMITTED',
      page: 1,
      size: 25,
      sort: 'createdAt,desc',
    }))
    expect(screen.getByLabelText('Current location')).toHaveTextContent('page=1')
  })

  it('shows the default submitted empty state', async () => {
    apiMocks.searchClaims.mockResolvedValue({ ...claimPage, content: [], totalElements: 0 })
    renderSection()

    expect(await screen.findByText('No submitted claims require review.')).toBeInTheDocument()
  })

  it('shows the filtered empty state', async () => {
    apiMocks.searchClaims.mockResolvedValue({ ...claimPage, content: [], totalElements: 0 })
    renderSection('/admin/lost-found?tab=claims&status=APPROVED&keyword=wallet')

    expect(await screen.findByText('No claims match the current filters.')).toBeInTheDocument()
  })

  it('shows an initial loading indicator without an empty state', () => {
    apiMocks.searchClaims.mockReturnValue(new Promise(() => {}))
    renderSection()

    expect(screen.getByRole('progressbar')).toBeInTheDocument()
    expect(screen.queryByText('No submitted claims require review.')).not.toBeInTheDocument()
  })

  it('shows API errors and retries only the Claims GET with preserved parameters', async () => {
    apiMocks.searchClaims
      .mockRejectedValueOnce(new Error('Service unavailable'))
      .mockResolvedValueOnce(claimPage)
    renderSection('/admin/lost-found?tab=claims&status=APPROVED&keyword=wallet&page=2')

    expect(await screen.findByText('Service unavailable')).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))

    expect(await screen.findByText('Black Headphones')).toBeInTheDocument()
    expect(apiMocks.searchClaims).toHaveBeenCalledTimes(2)
    expect(apiMocks.searchClaims).toHaveBeenLastCalledWith({
      status: 'APPROVED',
      keyword: 'wallet',
      page: 2,
      size: 25,
      sort: 'createdAt,desc',
    })
    expect(screen.getByLabelText('Current location')).toHaveTextContent(
      '/admin/lost-found?tab=claims&status=APPROVED&keyword=wallet&page=2',
    )
  })
})
