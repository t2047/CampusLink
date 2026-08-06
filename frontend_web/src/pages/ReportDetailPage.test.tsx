import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getReport, submitClaim } from '../api/lostFound'
import type { LostFoundReport } from '../types'
import { ReportDetailPage } from './ReportDetailPage'

vi.mock('../api/lostFound', () => ({
  getReport: vi.fn(),
  submitClaim: vi.fn(),
}))

const report: LostFoundReport = {
  id: 42,
  reportType: 'FOUND',
  itemName: 'Black headphones',
  category: 'ELECTRONICS',
  description: 'Black headphones in a small case',
  colour: 'Black',
  location: 'COM1',
  eventDate: '2026-08-06',
  timeDescription: 'Afternoon',
  status: 'OPEN',
  images: [],
  createdByMe: false,
  createdAt: '2026-08-06T10:00:00Z',
  updatedAt: '2026-08-06T10:00:00Z',
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/lost-found/42']}>
      <Routes>
        <Route path="/lost-found/:reportId" element={<ReportDetailPage />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('ReportDetailPage', () => {
  beforeEach(() => {
    vi.mocked(getReport).mockResolvedValue(report)
    vi.mocked(submitClaim).mockResolvedValue({
      id: 7,
      report: {
        id: report.id,
        itemName: report.itemName,
        category: report.category,
        location: report.location,
        status: report.status,
      },
      proofDescription: 'The item has my initials inside.',
      status: 'SUBMITTED',
      decisionNote: null,
      submittedByMe: true,
      createdAt: '2026-08-06T10:05:00Z',
      updatedAt: '2026-08-06T10:05:00Z',
    })
  })

  it('hides the claim button after a successful claim submission', async () => {
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: 'Submit a claim' }))
    fireEvent.change(screen.getByLabelText('Identifying proof'), {
      target: { value: 'The item has my initials inside.' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Submit claim' }))

    expect(await screen.findByText('Your claim was submitted to the person who posted this item.'))
      .toBeInTheDocument()
    await waitFor(() => {
      expect(screen.queryByRole('button', { name: 'Submit a claim' })).not.toBeInTheDocument()
    })
  })
})
