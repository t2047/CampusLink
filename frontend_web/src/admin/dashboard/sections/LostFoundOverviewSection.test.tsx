import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getAdminLostFoundOverview } from '../../../api/adminLostFound'
import type { AdminLostFoundOverview } from '../../../types'
import { LostFoundOverviewSection } from './LostFoundOverviewSection'

vi.mock('../../../api/adminLostFound', () => ({
  getAdminLostFoundOverview: vi.fn(),
}))

const overviewFixture: AdminLostFoundOverview = {
  totalReports: 12,
  openReports: 5,
  claimedReports: 3,
  closedReports: 4,
  lostReports: 7,
  foundReports: 5,
  submittedClaims: 2,
  hiddenReports: 0,
}

interface Deferred<T> {
  promise: Promise<T>
  resolve: (value: T) => void
  reject: (reason?: unknown) => void
}

function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

function renderSection() {
  return render(<LostFoundOverviewSection />)
}

function getOverviewRegion() {
  return screen.getByRole('region', { name: 'Lost & Found Overview' })
}

function getMetric(label: string) {
  return within(getOverviewRegion()).getByRole('group', { name: label })
}

function expectMetric(label: string, value: number) {
  expect(within(getMetric(label)).getByText(String(value))).toBeInTheDocument()
}

function getActionRequiredRegion() {
  return screen.getByRole('region', { name: 'Action Required' })
}

describe('LostFoundOverviewSection', () => {
  const getOverview = vi.mocked(getAdminLostFoundOverview)

  beforeEach(() => {
    getOverview.mockReset()
    getOverview.mockResolvedValue(overviewFixture)
  })

  afterEach(() => cleanup())

  it('loads and displays only the three authorized overview metrics', async () => {
    renderSection()

    expect(
      await screen.findByRole('group', { name: 'Total Reports' }),
    ).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Lost & Found Overview' })).toBeInTheDocument()
    expect(getOverview).toHaveBeenCalledTimes(1)
    expectMetric('Total Reports', 12)
    expectMetric('Open Reports', 5)
    expectMetric('Pending Claims', 2)
    expect(screen.queryByText('Claimed Reports')).not.toBeInTheDocument()
    expect(screen.queryByText('Closed Reports')).not.toBeInTheDocument()
    expect(screen.queryByText('Lost Reports')).not.toBeInTheDocument()
    expect(screen.queryByText('Found Reports')).not.toBeInTheDocument()
  })

  it('shows an accessible loading state without fallback business values', () => {
    getOverview.mockReturnValue(deferred<AdminLostFoundOverview>().promise)

    renderSection()

    expect(screen.getByRole('status')).toHaveTextContent('Loading Lost & Found overview')
    expect(screen.queryByText('12')).not.toBeInTheDocument()
    expect(screen.queryByText('5')).not.toBeInTheDocument()
    expect(screen.queryByText('2')).not.toBeInTheDocument()
    expect(screen.queryByText('0')).not.toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(getOverview).toHaveBeenCalledTimes(1)
  })

  it('renders zero as real data instead of loading, error, or empty state', async () => {
    getOverview.mockResolvedValue({
      totalReports: 0,
      openReports: 0,
      claimedReports: 0,
      closedReports: 0,
      lostReports: 0,
      foundReports: 0,
      submittedClaims: 0,
      hiddenReports: 0,
    })

    renderSection()

    expect(
      await screen.findByRole('group', { name: 'Total Reports' }),
    ).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Lost & Found Overview' })).toBeInTheDocument()
    expectMetric('Total Reports', 0)
    expectMetric('Open Reports', 0)
    expectMetric('Pending Claims', 0)
    expect(within(getOverviewRegion()).getAllByText('0')).toHaveLength(3)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    expect(within(getActionRequiredRegion()).getByText('No pending claims require review.')).toBeInTheDocument()
  })

  it('shows the API error and retry control without fake or stale values', async () => {
    getOverview.mockRejectedValue(new Error('Unable to load Lost & Found overview.'))

    renderSection()

    expect(await screen.findByRole('alert')).toHaveTextContent('Unable to load Lost & Found overview.')
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument()
    expect(screen.queryByText('12')).not.toBeInTheDocument()
    expect(screen.queryByText('5')).not.toBeInTheDocument()
    expect(screen.queryByText('2')).not.toBeInTheDocument()
    expect(screen.queryByText('0')).not.toBeInTheDocument()
    expect(screen.queryByText('2 pending claims require review.')).not.toBeInTheDocument()
  })

  it('retries once, prevents concurrent retry, and replaces the error with real data', async () => {
    const retryRequest = deferred<AdminLostFoundOverview>()
    getOverview
      .mockRejectedValueOnce(new Error('Unable to load Lost & Found overview.'))
      .mockReturnValueOnce(retryRequest.promise)

    renderSection()

    expect(await screen.findByRole('alert')).toHaveTextContent('Unable to load Lost & Found overview.')
    const retryButton = screen.getByRole('button', { name: 'Retry' })

    fireEvent.click(retryButton)

    expect(screen.getByRole('status')).toHaveTextContent('Loading Lost & Found overview')
    fireEvent.click(retryButton)
    expect(getOverview).toHaveBeenCalledTimes(2)

    await act(async () => {
      retryRequest.resolve(overviewFixture)
      await retryRequest.promise
    })

    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument())
    expectMetric('Total Reports', 12)
    expectMetric('Open Reports', 5)
    expectMetric('Pending Claims', 2)
    expect(within(getActionRequiredRegion()).getByText('2 pending claims require review.')).toBeInTheDocument()
  })

  it('settles a pending request safely after unmount', async () => {
    const request = deferred<AdminLostFoundOverview>()
    getOverview.mockReturnValue(request.promise)
    const { unmount } = renderSection()

    expect(getOverview).toHaveBeenCalledTimes(1)
    unmount()

    await act(async () => {
      request.resolve(overviewFixture)
      await request.promise
    })

    expect(getOverview).toHaveBeenCalledTimes(1)
  })

  it('keeps all metric cards read-only and non-navigational', async () => {
    renderSection()

    expect(
      await screen.findByRole('group', { name: 'Total Reports' }),
    ).toBeInTheDocument()

    for (const label of ['Total Reports', 'Open Reports', 'Pending Claims']) {
      const metric = getMetric(label)
      expect(metric.closest('a')).toBeNull()
      expect(metric.closest('button')).toBeNull()
      expect(metric).not.toHaveAttribute('href')
      expect(within(metric).queryByRole('link')).not.toBeInTheDocument()
      expect(within(metric).queryByRole('button')).not.toBeInTheDocument()
    }
  })

  it('shows plural pending claims and the read-only limitation without actions', async () => {
    renderSection()

    const actionRequired = await screen.findByRole('region', { name: 'Action Required' })
    expect(within(actionRequired).getByText('2 pending claims require review.')).toBeInTheDocument()
    expect(within(actionRequired).getByText('Claim review actions are not available in this read-only dashboard yet.')).toBeInTheDocument()
    expect(within(actionRequired).queryByRole('link')).not.toBeInTheDocument()
    expect(within(actionRequired).queryByRole('button')).not.toBeInTheDocument()
    expect(getOverview).toHaveBeenCalledTimes(1)
  })

  it('uses singular grammar for one pending claim', async () => {
    getOverview.mockResolvedValue({ ...overviewFixture, submittedClaims: 1 })

    renderSection()

    const actionRequired = await screen.findByRole('region', { name: 'Action Required' })
    expect(within(actionRequired).getByText('1 pending claim requires review.')).toBeInTheDocument()
    expect(within(actionRequired).getByText('Claim review actions are not available in this read-only dashboard yet.')).toBeInTheDocument()
  })

  it('shows the zero pending-claims message without implying an available action', async () => {
    getOverview.mockResolvedValue({ ...overviewFixture, submittedClaims: 0 })

    renderSection()

    const actionRequired = await screen.findByRole('region', { name: 'Action Required' })
    expect(within(actionRequired).getByText('No pending claims require review.')).toBeInTheDocument()
    expect(within(actionRequired).queryByText('Claim review actions are not available in this read-only dashboard yet.')).not.toBeInTheDocument()
    expect(within(actionRequired).queryByRole('link')).not.toBeInTheDocument()
    expect(within(actionRequired).queryByRole('button')).not.toBeInTheDocument()
    expect(getOverview).toHaveBeenCalledTimes(1)
  })
})
