import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getAdminFacilitiesOverview } from '../../../api/adminFacilities'
import type { AdminFacilitiesOverview } from '../../../types'
import { FacilitiesOverviewSection } from './FacilitiesOverviewSection'

vi.mock('../../../api/adminFacilities', () => ({
  getAdminFacilitiesOverview: vi.fn(),
}))

const getOverview = vi.mocked(getAdminFacilitiesOverview)

const overviewFixture: AdminFacilitiesOverview = {
  summary: {
    totalSpaces: 12,
    availableSpaces: 8,
    outOfServiceSpaces: 3,
    inactiveSpaces: 1,
    totalBookings: 20,
    confirmedBookings: 9,
    cancelledBookings: 4,
    completedBookings: 7,
    totalMaintenanceRequests: 14,
    submittedMaintenanceRequests: 5,
    inProgressMaintenanceRequests: 3,
    resolvedMaintenanceRequests: 4,
    cancelledMaintenanceRequests: 2,
    openMaintenanceRequests: 8,
  },
  spaceStatusBreakdown: [
    { status: 'AVAILABLE', count: 8 },
    { status: 'OUT_OF_SERVICE', count: 3 },
    { status: 'INACTIVE', count: 1 },
  ],
  bookingStatusBreakdown: [
    { status: 'CONFIRMED', count: 9 },
    { status: 'CANCELLED', count: 4 },
    { status: 'COMPLETED', count: 7 },
  ],
  maintenanceStatusBreakdown: [
    { status: 'SUBMITTED', count: 5 },
    { status: 'IN_PROGRESS', count: 3 },
    { status: 'RESOLVED', count: 4 },
    { status: 'CANCELLED', count: 2 },
  ],
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

function expectMetric(label: string, value: number) {
  expect(within(screen.getByRole('group', { name: label })).getByText(String(value))).toBeInTheDocument()
}

describe('FacilitiesOverviewSection', () => {
  beforeEach(() => {
    getOverview.mockReset()
    getOverview.mockResolvedValue(overviewFixture)
  })

  afterEach(() => cleanup())

  it('shows an accessible loading state initially', () => {
    getOverview.mockReturnValue(deferred<AdminFacilitiesOverview>().promise)

    render(<FacilitiesOverviewSection />)

    expect(screen.getByRole('status')).toHaveTextContent('Loading Facilities overview')
    expect(screen.getByLabelText('Loading Facilities overview')).toBeInTheDocument()
  })

  it('shows four Facilities KPIs after a successful request', async () => {
    render(<FacilitiesOverviewSection />)

    expect(await screen.findByRole('group', { name: 'Total Facilities' })).toBeInTheDocument()
    expectMetric('Total Facilities', 12)
    expectMetric('Available Facilities', 8)
    expectMetric('Total Bookings', 20)
    expectMetric('Open Maintenance', 8)
  })

  it('shows all space statuses, natural labels, and exact counts', async () => {
    render(<FacilitiesOverviewSection />)

    expect(await screen.findByRole('heading', { name: 'Space Status' })).toBeInTheDocument()
    expect(screen.getByRole('group', { name: 'Available: 8' })).toBeInTheDocument()
    expect(screen.getByRole('group', { name: 'Out of Service: 3' })).toBeInTheDocument()
    expect(screen.getByRole('group', { name: 'Inactive: 1' })).toBeInTheDocument()
    expect(screen.queryByText('OUT_OF_SERVICE')).not.toBeInTheDocument()
  })

  it('treats an all-zero response as a successful stable result', async () => {
    getOverview.mockResolvedValue({
      ...overviewFixture,
      summary: Object.fromEntries(
        Object.keys(overviewFixture.summary).map((key) => [key, 0]),
      ) as unknown as AdminFacilitiesOverview['summary'],
      spaceStatusBreakdown: overviewFixture.spaceStatusBreakdown.map(({ status }) => ({ status, count: 0 })),
      bookingStatusBreakdown: overviewFixture.bookingStatusBreakdown.map(({ status }) => ({ status, count: 0 })),
      maintenanceStatusBreakdown: overviewFixture.maintenanceStatusBreakdown.map(({ status }) => ({ status, count: 0 })),
    })

    render(<FacilitiesOverviewSection />)

    expect(await screen.findByText('No facility data is currently available.')).toBeInTheDocument()
    expectMetric('Total Facilities', 0)
    expectMetric('Available Facilities', 0)
    expectMetric('Total Bookings', 0)
    expectMetric('Open Maintenance', 0)
    expect(screen.getByRole('group', { name: 'Available: 0' })).toBeInTheDocument()
    expect(screen.getByRole('group', { name: 'Out of Service: 0' })).toBeInTheDocument()
    expect(screen.getByRole('group', { name: 'Inactive: 0' })).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('shows the API error and a retry control without stale data', async () => {
    getOverview.mockRejectedValue(new Error('Unable to load Facilities overview.'))

    render(<FacilitiesOverviewSection />)

    expect(await screen.findByRole('alert')).toHaveTextContent('Unable to load Facilities overview.')
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument()
    expect(screen.queryByRole('group', { name: 'Total Facilities' })).not.toBeInTheDocument()
  })

  it('retries successfully and prevents duplicate concurrent requests', async () => {
    const retryRequest = deferred<AdminFacilitiesOverview>()
    getOverview
      .mockRejectedValueOnce(new Error('Unable to load Facilities overview.'))
      .mockReturnValueOnce(retryRequest.promise)

    render(<FacilitiesOverviewSection />)

    expect(await screen.findByRole('alert')).toHaveTextContent('Unable to load Facilities overview.')
    const retryButton = screen.getByRole('button', { name: 'Retry' })

    fireEvent.click(retryButton)

    expect(screen.getByRole('status')).toHaveTextContent('Loading Facilities overview')
    fireEvent.click(retryButton)
    expect(getOverview).toHaveBeenCalledTimes(2)

    await act(async () => {
      retryRequest.resolve(overviewFixture)
      await retryRequest.promise
    })

    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument())
    expectMetric('Total Facilities', 12)
  })

  it('settles a pending request safely after unmount', async () => {
    const request = deferred<AdminFacilitiesOverview>()
    getOverview.mockReturnValue(request.promise)
    const { unmount } = render(<FacilitiesOverviewSection />)

    expect(getOverview).toHaveBeenCalledTimes(1)
    unmount()

    await act(async () => {
      request.resolve(overviewFixture)
      await request.promise
    })

    expect(getOverview).toHaveBeenCalledTimes(1)
  })
})
