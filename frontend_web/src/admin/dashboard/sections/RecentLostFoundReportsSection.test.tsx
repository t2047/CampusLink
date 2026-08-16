import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { StrictMode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { searchAdminLostFoundReports } from '../../../api/adminLostFound'
import type { AdminLostFoundReport, PageResponse } from '../../../types'
import { RecentLostFoundReportsSection } from './RecentLostFoundReportsSection'

vi.mock('../../../api/adminLostFound', () => ({
  searchAdminLostFoundReports: vi.fn(),
}))

const searchReports = vi.mocked(searchAdminLostFoundReports)

const reportFixture: AdminLostFoundReport = {
  id: 42,
  reportType: 'FOUND',
  itemName: 'Black Headphones',
  category: 'ELECTRONICS',
  colour: 'Black',
  location: 'Central Library',
  eventDate: '2026-08-06',
  status: 'OPEN',
  adminHidden: false,
  createdByEmail: 'reporter@nus.edu.sg',
  createdAt: '2026-08-07T03:00:00Z',
  updatedAt: '2026-08-07T03:00:00Z',
}

function reportsPage(content: AdminLostFoundReport[]): PageResponse<AdminLostFoundReport> {
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
      <RecentLostFoundReportsSection />
    </MemoryRouter>,
  )
}

describe('RecentLostFoundReportsSection', () => {
  beforeEach(() => {
    searchReports.mockReset()
    searchReports.mockResolvedValue(reportsPage([reportFixture]))
  })

  afterEach(() => cleanup())

  it('requests the recent reports page and shows accessible loading', () => {
    const pending = deferred<PageResponse<AdminLostFoundReport>>()
    searchReports.mockReturnValue(pending.promise)

    renderSection()

    expect(searchReports).toHaveBeenCalledWith({ page: 0, size: 5, sort: 'createdAt,desc' })
    expect(screen.getByRole('status')).toHaveTextContent('Loading recent Lost & Found reports')
    expect(screen.getByLabelText('Loading recent Lost & Found reports')).toBeInTheDocument()
  })

  it('shows report fields, natural chips, Singapore time, and navigation links', async () => {
    searchReports.mockResolvedValue(reportsPage([
      reportFixture,
      { ...reportFixture, id: 43, itemName: 'Student Card', reportType: 'LOST', status: 'CLAIMED', adminHidden: true },
      { ...reportFixture, id: 44, itemName: 'Blue Umbrella', status: 'CLOSED' },
    ]))

    renderSection()

    const table = await screen.findByRole('table', { name: 'Recent Lost & Found reports' })
    expect(within(table).getByText('Black Headphones')).toBeInTheDocument()
    expect(within(table).getAllByText('Found')).toHaveLength(2)
    expect(within(table).getByText('Lost')).toBeInTheDocument()
    expect(within(table).getByText('Open')).toBeInTheDocument()
    expect(within(table).getByText('Claimed')).toBeInTheDocument()
    expect(within(table).getByText('Closed')).toBeInTheDocument()
    expect(within(table).getAllByText('Central Library')).toHaveLength(3)
    expect(within(table).getAllByText('reporter@nus.edu.sg')).toHaveLength(3)
    expect(within(table).getAllByText('7 Aug 2026, 11:00 am')).toHaveLength(3)
    expect(within(table).getByText('Hidden')).toBeInTheDocument()
    expect(within(table).getAllByRole('link', { name: 'View Report' })[0]).toHaveAttribute('href', '/lost-found/42')
    expect(screen.getByRole('link', { name: 'View All' })).toHaveAttribute('href', '/admin/lost-found')
    expect(within(table).queryByRole('button', { name: /delist|restore|delete/i })).not.toBeInTheDocument()
  })

  it('shows at most five reports', async () => {
    searchReports.mockResolvedValue(reportsPage(Array.from({ length: 6 }, (_, index) => ({
      ...reportFixture,
      id: index + 1,
      itemName: `Report Item ${index + 1}`,
    }))))

    renderSection()

    const table = await screen.findByRole('table', { name: 'Recent Lost & Found reports' })
    expect(within(table).getAllByRole('row')).toHaveLength(6)
    expect(within(table).getByText('Report Item 5')).toBeInTheDocument()
    expect(within(table).queryByText('Report Item 6')).not.toBeInTheDocument()
  })

  it('shows the empty state', async () => {
    searchReports.mockResolvedValue(reportsPage([]))

    renderSection()

    expect(await screen.findByText('No Lost & Found reports are currently available.')).toBeInTheDocument()
  })

  it('shows an error without stale data and retries successfully', async () => {
    const retry = deferred<PageResponse<AdminLostFoundReport>>()
    searchReports.mockRejectedValueOnce(new Error('Unable to load recent reports.')).mockReturnValueOnce(retry.promise)

    renderSection()

    expect(await screen.findByRole('alert')).toHaveTextContent('Unable to load recent reports.')
    expect(screen.queryByRole('table', { name: 'Recent Lost & Found reports' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))
    expect(screen.getByRole('status')).toHaveTextContent('Loading recent Lost & Found reports')
    expect(searchReports).toHaveBeenCalledTimes(2)

    await act(async () => {
      retry.resolve(reportsPage([reportFixture]))
      await retry.promise
    })

    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument())
    expect(screen.getByRole('table', { name: 'Recent Lost & Found reports' })).toBeInTheDocument()
  })

  it('prevents duplicate concurrent requests and settles safely after unmount', async () => {
    const pending = deferred<PageResponse<AdminLostFoundReport>>()
    searchReports.mockReturnValue(pending.promise)
    const view = render(
      <StrictMode>
        <MemoryRouter><RecentLostFoundReportsSection /></MemoryRouter>
      </StrictMode>,
    )

    expect(searchReports).toHaveBeenCalledTimes(1)
    view.unmount()
    await act(async () => {
      pending.resolve(reportsPage([reportFixture]))
      await pending.promise
    })
    expect(searchReports).toHaveBeenCalledTimes(1)
  })
})
