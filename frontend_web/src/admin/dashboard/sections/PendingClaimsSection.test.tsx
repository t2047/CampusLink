import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { StrictMode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { searchAdminClaims } from '../../../api/adminLostFound'
import type { AdminClaimSummary, PageResponse } from '../../../types'
import { PendingClaimsSection } from './PendingClaimsSection'

vi.mock('../../../api/adminLostFound', () => ({
  searchAdminClaims: vi.fn(),
}))

const searchClaims = vi.mocked(searchAdminClaims)

const claimFixture: AdminClaimSummary = {
  id: 42,
  status: 'SUBMITTED',
  proofSummary: 'The case has a scratch near the hinge and contains private ownership evidence.',
  decisionNote: null,
  claimant: { id: 7, email: 'claimant@nus.edu.sg' },
  report: {
    id: 12,
    reportType: 'FOUND',
    itemName: 'Black Headphones',
    category: 'ELECTRONICS',
    colour: 'Black',
    location: 'Central Library',
    eventDate: '2026-08-06',
    status: 'OPEN',
    adminHidden: false,
    owner: { id: 8, email: 'owner@nus.edu.sg' },
  },
  createdAt: '2026-08-07T03:00:00Z',
  updatedAt: '2026-08-07T03:00:00Z',
}

function claimsPage(content: AdminClaimSummary[]): PageResponse<AdminClaimSummary> {
  return {
    content,
    page: 0,
    size: 5,
    totalElements: content.length,
    totalPages: content.length === 0 ? 0 : Math.ceil(content.length / 5),
    first: true,
    last: content.length <= 5,
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((resolvePromise) => {
    resolve = resolvePromise
  })
  return { promise, resolve }
}

function renderSection() {
  return render(
    <MemoryRouter>
      <PendingClaimsSection />
    </MemoryRouter>,
  )
}

describe('PendingClaimsSection', () => {
  beforeEach(() => {
    searchClaims.mockReset()
    searchClaims.mockResolvedValue(claimsPage([claimFixture]))
  })

  afterEach(() => cleanup())

  it('requests the submitted claims page and shows accessible loading', () => {
    const pending = deferred<PageResponse<AdminClaimSummary>>()
    searchClaims.mockReturnValue(pending.promise)

    renderSection()

    expect(searchClaims).toHaveBeenCalledWith({
      status: 'SUBMITTED',
      page: 0,
      size: 5,
      sort: 'createdAt,desc',
    })
    expect(screen.getByRole('status')).toHaveTextContent('Loading pending claims')
    expect(screen.getByLabelText('Loading pending claims')).toBeInTheDocument()
  })

  it('shows real claim fields, Singapore time, status, and navigation links', async () => {
    renderSection()

    const table = await screen.findByRole('table', { name: 'Pending claims requiring review' })
    expect(within(table).getByText('Black Headphones')).toBeInTheDocument()
    expect(within(table).getByText('claimant@nus.edu.sg')).toBeInTheDocument()
    expect(within(table).getByText('owner@nus.edu.sg')).toBeInTheDocument()
    const expectedTime = new Intl.DateTimeFormat('en-SG', {
      dateStyle: 'medium',
      timeStyle: 'short',
      timeZone: 'Asia/Singapore',
    }).format(new Date(claimFixture.createdAt))
    expect(within(table).getByText(expectedTime)).toBeInTheDocument()
    expect(within(table).getByText('SUBMITTED')).toBeInTheDocument()
    expect(within(table).getByRole('link', { name: 'Review' })).toHaveAttribute(
      'href',
      '/admin/lost-found/claims/42',
    )
    expect(screen.getByRole('link', { name: 'View All' })).toHaveAttribute(
      'href',
      '/admin/lost-found?tab=claims&status=SUBMITTED',
    )
    expect(within(table).queryByText(claimFixture.proofSummary)).not.toBeInTheDocument()
  })

  it('shows at most five claims', async () => {
    const claims = Array.from({ length: 6 }, (_, index) => ({
      ...claimFixture,
      id: index + 1,
      report: { ...claimFixture.report, itemName: `Item ${index + 1}` },
    }))
    searchClaims.mockResolvedValue(claimsPage(claims))

    renderSection()

    const table = await screen.findByRole('table', { name: 'Pending claims requiring review' })
    expect(within(table).getAllByRole('row')).toHaveLength(6)
    expect(within(table).getByText('Item 5')).toBeInTheDocument()
    expect(within(table).queryByText('Item 6')).not.toBeInTheDocument()
  })

  it('shows the pending claims empty state', async () => {
    searchClaims.mockResolvedValue(claimsPage([]))

    renderSection()

    expect(await screen.findByText('No pending claims require review.')).toBeInTheDocument()
    expect(screen.queryByRole('table', { name: 'Pending claims requiring review' })).not.toBeInTheDocument()
  })

  it('shows an error without stale data and retries successfully', async () => {
    const retry = deferred<PageResponse<AdminClaimSummary>>()
    searchClaims
      .mockRejectedValueOnce(new Error('Unable to load pending claims.'))
      .mockReturnValueOnce(retry.promise)

    renderSection()

    expect(await screen.findByRole('alert')).toHaveTextContent('Unable to load pending claims.')
    expect(screen.queryByRole('table', { name: 'Pending claims requiring review' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))
    expect(screen.getByRole('status')).toHaveTextContent('Loading pending claims')
    expect(searchClaims).toHaveBeenCalledTimes(2)

    await act(async () => {
      retry.resolve(claimsPage([claimFixture]))
      await retry.promise
    })

    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument())
    expect(screen.getByRole('table', { name: 'Pending claims requiring review' })).toBeInTheDocument()
  })

  it('prevents duplicate concurrent requests under StrictMode', () => {
    const pending = deferred<PageResponse<AdminClaimSummary>>()
    searchClaims.mockReturnValue(pending.promise)

    render(
      <StrictMode>
        <MemoryRouter>
          <PendingClaimsSection />
        </MemoryRouter>
      </StrictMode>,
    )

    expect(searchClaims).toHaveBeenCalledTimes(1)
  })

  it('settles a pending request safely after unmount', async () => {
    const pending = deferred<PageResponse<AdminClaimSummary>>()
    searchClaims.mockReturnValue(pending.promise)
    const view = renderSection()

    view.unmount()

    await act(async () => {
      pending.resolve(claimsPage([claimFixture]))
      await pending.promise
    })

    expect(searchClaims).toHaveBeenCalledTimes(1)
  })
})
