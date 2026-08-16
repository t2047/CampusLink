import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { closeReport, deleteReport, getReport, submitClaim, updateReport } from '../api/lostFound'
import type { LostFoundReport } from '../types'
import { ReportDetailPage } from './ReportDetailPage'

vi.mock('../api/lostFound', () => ({
  closeReport: vi.fn(),
  deleteReport: vi.fn(),
  getReport: vi.fn(),
  submitClaim: vi.fn(),
  updateReport: vi.fn(),
}))

function report(overrides: Partial<LostFoundReport> = {}): LostFoundReport {
  return {
    id: 42,
    reportType: 'FOUND',
    itemName: 'Black AirPods',
    category: 'ELECTRONICS',
    description: 'Black AirPods with a small scratch on the case.',
    colour: 'black',
    location: 'Central Library',
    eventDate: '2026-08-09',
    timeDescription: 'Afternoon',
    status: 'OPEN',
    images: [],
    createdByMe: true,
    adminHidden: false,
    createdAt: '2026-08-09T13:00:00Z',
    updatedAt: '2026-08-09T13:00:00Z',
    ...overrides,
  }
}

function renderDetail(overrides: Partial<LostFoundReport> = {}) {
  vi.mocked(getReport).mockResolvedValue(report(overrides))
  return render(
    <MemoryRouter initialEntries={['/lost-found/42']}>
      <Routes><Route path="/lost-found/:reportId" element={<ReportDetailPage />} /></Routes>
    </MemoryRouter>,
  )
}

describe('ReportDetailPage management actions', () => {
  beforeEach(() => {
    vi.mocked(getReport).mockReset()
    vi.mocked(closeReport).mockReset()
    vi.mocked(deleteReport).mockReset()
    vi.mocked(updateReport).mockReset()
  })

  afterEach(() => cleanup())

  it('shows edit, close and delete buttons only for the owner of an open report', async () => {
    renderDetail()
    expect(await screen.findByText('Black AirPods')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Edit' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Close' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Delete' })).toBeInTheDocument()
  })

  it('hides management buttons for non-owners', async () => {
    renderDetail({ createdByMe: false })
    await screen.findByText('Black AirPods')
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Close' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Delete' })).not.toBeInTheDocument()
  })

  it('hides management buttons once a report is closed', async () => {
    renderDetail({ status: 'CLOSED' })
    await screen.findByText('Black AirPods')
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument()
  })

  it('closes the report after confirmation', async () => {
    vi.mocked(closeReport).mockResolvedValue({ ...report(), status: 'CLOSED' })
    renderDetail()
    await screen.findByText('Black AirPods')

    fireEvent.click(screen.getByRole('button', { name: 'Close' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Close report' }))

    await waitFor(() => expect(closeReport).toHaveBeenCalledWith(42))
    expect(await screen.findByText('Report closed.')).toBeInTheDocument()
  })

  it('deletes the report and navigates back to the list', async () => {
    vi.mocked(deleteReport).mockResolvedValue(undefined)
    renderDetail()
    await screen.findByText('Black AirPods')

    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Delete report' }))

    await waitFor(() => expect(deleteReport).toHaveBeenCalledWith(42))
  })

  it('submits an edited report through the edit dialog', async () => {
    vi.mocked(updateReport).mockResolvedValue({ ...report(), itemName: 'White Earphones' })
    renderDetail()
    await screen.findByText('Black AirPods')

    fireEvent.click(screen.getByRole('button', { name: 'Edit' }))
    const nameField = await screen.findByRole('textbox', { name: /item name/i })
    fireEvent.change(nameField, { target: { value: 'White Earphones' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }))

    await waitFor(() => expect(updateReport).toHaveBeenCalledWith(42, expect.objectContaining({ itemName: 'White Earphones' }), [], expect.any(Function)))
    expect(await screen.findByText('White Earphones')).toBeInTheDocument()
    expect(await screen.findByText('Report updated.')).toBeInTheDocument()
  })

  it('submits a claim without triggering management actions', async () => {
    vi.mocked(submitClaim).mockResolvedValue({
      id: 1, report: { id: 42, itemName: 'Black AirPods', category: 'ELECTRONICS', location: 'Central Library', status: 'OPEN' },
      proofDescription: 'A scratch on the case', status: 'SUBMITTED', decisionNote: null, submittedByMe: true,
      createdAt: '2026-08-09T13:00:00Z', updatedAt: '2026-08-09T13:00:00Z',
    })
    renderDetail({ createdByMe: false })
    await screen.findByText('Black AirPods')
    fireEvent.click(screen.getByRole('button', { name: 'Submit a claim' }))
    fireEvent.change(screen.getByLabelText('Identifying proof'), { target: { value: 'A distinctive scratch' } })
    fireEvent.click(screen.getByRole('button', { name: 'Submit claim' }))
    await waitFor(() => expect(submitClaim).toHaveBeenCalledWith(42, 'A distinctive scratch'))
  })
})
