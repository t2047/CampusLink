import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { StrictMode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getAdminLostFoundOverview } from '../../../api/adminLostFound'
import type { AdminLostFoundOverview } from '../../../types'
import { LostFoundOverviewSection } from './LostFoundOverviewSection'

vi.mock('../../../api/adminLostFound', () => ({
  getAdminLostFoundOverview: vi.fn(),
}))

const getOverview = vi.mocked(getAdminLostFoundOverview)

const overviewFixture: AdminLostFoundOverview = {
  totalReports: 12,
  openReports: 5,
  claimedReports: 3,
  closedReports: 4,
  lostReports: 7,
  foundReports: 5,
  submittedClaims: 2,
  processedClaims: 0,
  hiddenReports: 1,
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((resolvePromise) => {
    resolve = resolvePromise
  })
  return { promise, resolve }
}

function renderSection() {
  return render(<LostFoundOverviewSection />)
}

describe('LostFoundOverviewSection', () => {
  beforeEach(() => {
    getOverview.mockReset()
    getOverview.mockResolvedValue(overviewFixture)
  })

  afterEach(() => cleanup())

  it('shows accessible loading and requests the overview once', () => {
    const pending = deferred<AdminLostFoundOverview>()
    getOverview.mockReturnValue(pending.promise)

    renderSection()

    expect(getOverview).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('status')).toHaveTextContent('Loading Lost & Found overview')
    expect(screen.getByLabelText('Loading Lost & Found overview')).toBeInTheDocument()
  })

  it('shows four KPIs including Hidden Reports and the complete status chart', async () => {
    renderSection()

    expect(await screen.findByRole('group', { name: 'Total Reports' })).toHaveTextContent('12')
    expect(screen.getByRole('group', { name: 'Open Reports' })).toHaveTextContent('5')
    expect(screen.getByRole('group', { name: 'Pending Claims' })).toHaveTextContent('2')
    expect(screen.getByRole('group', { name: 'Hidden Reports' })).toHaveTextContent('1')

    expect(screen.getByRole('heading', { name: 'Lost & Found Report Status' })).toBeInTheDocument()
    expect(screen.getByRole('group', { name: 'Open: 5' })).toBeInTheDocument()
    expect(screen.getByRole('group', { name: 'Claimed: 3' })).toBeInTheDocument()
    expect(screen.getByRole('group', { name: 'Closed: 4' })).toBeInTheDocument()
    expect(screen.getByRole('img', { name: 'Open reports: 5' })).toBeInTheDocument()
    expect(screen.getByRole('img', { name: 'Claimed reports: 3' })).toBeInTheDocument()
    expect(screen.getByRole('img', { name: 'Closed reports: 4' })).toBeInTheDocument()
  })

  it('renders all-zero overview data normally', async () => {
    getOverview.mockResolvedValue({
      totalReports: 0,
      openReports: 0,
      claimedReports: 0,
      closedReports: 0,
      lostReports: 0,
      foundReports: 0,
      submittedClaims: 0,
  processedClaims: 0,
      hiddenReports: 0,
    })

    renderSection()

    expect(await screen.findByRole('group', { name: 'Total Reports' })).toHaveTextContent('0')
    expect(screen.getByRole('group', { name: 'Open Reports' })).toHaveTextContent('0')
    expect(screen.getByRole('group', { name: 'Pending Claims' })).toHaveTextContent('0')
    expect(screen.getByRole('group', { name: 'Hidden Reports' })).toHaveTextContent('0')
    expect(screen.getByRole('group', { name: 'Open: 0' })).toBeInTheDocument()
    expect(screen.getByRole('group', { name: 'Claimed: 0' })).toBeInTheDocument()
    expect(screen.getByRole('group', { name: 'Closed: 0' })).toBeInTheDocument()
  })

  it('removes the obsolete Action Required card and read-only limitation copy', async () => {
    renderSection()

    await screen.findByRole('group', { name: 'Total Reports' })
    expect(screen.queryByRole('region', { name: 'Action Required' })).not.toBeInTheDocument()
    expect(screen.queryByText('Claim review actions are not available in this read-only dashboard yet.')).not.toBeInTheDocument()
    expect(screen.queryByText(/pending claims? requires? review/i)).not.toBeInTheDocument()
  })

  it('shows an error, clears stale data, and retries successfully', async () => {
    const retry = deferred<AdminLostFoundOverview>()
    getOverview
      .mockRejectedValueOnce(new Error('Unable to load Lost & Found overview.'))
      .mockReturnValueOnce(retry.promise)

    renderSection()

    expect(await screen.findByRole('alert')).toHaveTextContent('Unable to load Lost & Found overview.')
    expect(screen.queryByRole('group', { name: 'Total Reports' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))
    expect(screen.getByRole('status')).toHaveTextContent('Loading Lost & Found overview')
    expect(getOverview).toHaveBeenCalledTimes(2)

    await act(async () => {
      retry.resolve(overviewFixture)
      await retry.promise
    })

    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument())
    expect(screen.getByRole('group', { name: 'Total Reports' })).toHaveTextContent('12')
  })

  it('prevents duplicate concurrent requests under StrictMode', () => {
    const pending = deferred<AdminLostFoundOverview>()
    getOverview.mockReturnValue(pending.promise)

    render(
      <StrictMode>
        <LostFoundOverviewSection />
      </StrictMode>,
    )

    expect(getOverview).toHaveBeenCalledTimes(1)
  })

  it('settles a pending request safely after unmount', async () => {
    const pending = deferred<AdminLostFoundOverview>()
    getOverview.mockReturnValue(pending.promise)
    const view = renderSection()

    view.unmount()

    await act(async () => {
      pending.resolve(overviewFixture)
      await pending.promise
    })

    expect(getOverview).toHaveBeenCalledTimes(1)
  })
})
