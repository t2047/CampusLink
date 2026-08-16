import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { searchReports } from '../api/lostFound'
import type { LostFoundReport, PageResponse } from '../types'
import { MyReportsPage } from './MyReportsPage'

vi.mock('../api/lostFound', () => ({ searchReports: vi.fn() }))

function report(overrides: Partial<LostFoundReport> = {}): LostFoundReport {
  return {
    id: 1,
    reportType: 'LOST',
    itemName: 'Black Headphones',
    category: 'ELECTRONICS',
    description: 'Wireless headphones.',
    colour: 'black',
    location: 'Central Library',
    eventDate: '2026-08-09',
    timeDescription: null,
    status: 'OPEN',
    images: [],
    createdByMe: true,
    adminHidden: false,
    createdAt: '2026-08-09T13:00:00Z',
    updatedAt: '2026-08-09T13:00:00Z',
    ...overrides,
  }
}

function page(reports: LostFoundReport[]): PageResponse<LostFoundReport> {
  return { content: reports, page: 0, size: 20, totalElements: reports.length, totalPages: 1, first: true, last: true }
}

function renderPage(reportType: 'LOST' | 'FOUND' = 'LOST') {
  return render(
    <MemoryRouter>
      <MyReportsPage reportType={reportType} />
    </MemoryRouter>,
  )
}

beforeEach(() => {
  vi.mocked(searchReports).mockResolvedValue(page([]))
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe('MyReportsPage', () => {
  it('requests owner=me with the given reportType', async () => {
    renderPage('LOST')
    await waitFor(() => expect(searchReports).toHaveBeenCalledWith(expect.objectContaining({ owner: 'me', reportType: 'LOST' })))
  })

  it('renders the user’s reports', async () => {
    vi.mocked(searchReports).mockResolvedValue(page([report({ itemName: 'Black Headphones' })]))
    renderPage('LOST')

    expect(await screen.findByText('Black Headphones')).toBeInTheDocument()
  })

  it('shows a removed-by-admin badge for hidden reports', async () => {
    vi.mocked(searchReports).mockResolvedValue(page([report({ itemName: 'Hidden Watch', adminHidden: true })]))
    renderPage('LOST')

    expect(await screen.findByText('Removed by admin')).toBeInTheDocument()
  })

  it('filters by status when a status chip is selected', async () => {
    vi.mocked(searchReports).mockResolvedValue(page([]))
    renderPage('LOST')

    fireEvent.click(await screen.findByRole('button', { name: 'CLOSED' }))

    await waitFor(() => expect(searchReports).toHaveBeenCalledWith(expect.objectContaining({ status: 'CLOSED' })))
  })

  it('shows an empty state with a publish CTA for lost items', async () => {
    renderPage('LOST')

    expect(await screen.findByText('No reports here yet')).toBeInTheDocument()
    const links = screen.getAllByRole('link', { name: /Report lost/ })
    expect(links.length).toBeGreaterThanOrEqual(2)
    expect(links.every((link) => link.getAttribute('href') === '/lost-found/new/lost')).toBe(true)
  })

  it('links found-item publishing to the found form', async () => {
    renderPage('FOUND')

    await screen.findByText('No reports here yet')
    const links = screen.getAllByRole('link', { name: /Report found/ })
    expect(links.length).toBeGreaterThanOrEqual(2)
    expect(links.every((link) => link.getAttribute('href') === '/lost-found/new/found')).toBe(true)
  })
})
