import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { invokeLostFoundAgent, type AgentInvokeResponse } from '../api/lostFoundAgent'
import { searchReports } from '../api/lostFound'
import type { LostFoundReport, PageResponse } from '../types'
import { ReportsPage } from './ReportsPage'

vi.mock('../api/lostFoundAgent', () => ({ invokeLostFoundAgent: vi.fn() }))
vi.mock('../api/lostFound', () => ({ searchReports: vi.fn() }))

const createdReport: LostFoundReport = {
  id: 42,
  reportType: 'LOST',
  itemName: '黑色耳机',
  category: 'ELECTRONICS',
  description: '黑色 Sony 耳机和充电盒',
  colour: 'black',
  location: '中央图书馆',
  eventDate: '2026-08-09',
  timeDescription: null,
  status: 'OPEN',
  images: [],
  createdByMe: true,
  createdAt: '2026-08-09T13:00:00Z',
  updatedAt: '2026-08-09T13:00:00Z',
}

function page(content: LostFoundReport[]): PageResponse<LostFoundReport> {
  return { content, page: 0, size: 12, totalElements: content.length, totalPages: 1, first: true, last: true }
}

const confirmationResponse: AgentInvokeResponse = {
  response: '请确认报失信息。',
  status: 'needs_confirmation',
  match_results: [],
  confirmation_required: {
    confirmation_id: 'confirmation-42',
    action: 'report_lost',
    summary: '黑色耳机，中央图书馆，2026-08-09',
    expires_at: '2026-08-09T14:00:00Z',
  },
  shared_context: { intent: 'report_lost', item_name: '黑色耳机' },
  actions_taken: [],
  request_id: 'request-1',
}

describe('ReportsPage Agent refresh', () => {
  const search = vi.mocked(searchReports)
  const invoke = vi.mocked(invokeLostFoundAgent)

  beforeEach(() => {
    search.mockReset()
    invoke.mockReset()
    search.mockImplementation(async (params) => page(params.reportType === 'LOST' && !params.colour ? [createdReport] : []))
    invoke
      .mockResolvedValueOnce(confirmationResponse)
      .mockResolvedValueOnce({
        ...confirmationResponse,
        response: '报失记录 #42 已创建。',
        status: 'completed',
        confirmation_required: null,
        shared_context: {},
        actions_taken: [{ action: 'report_lost', status: 'success', result_summary: 'report_id=42' }],
        request_id: 'request-2',
      })
  })

  afterEach(() => cleanup())

  it('clears stale filters, switches to lost items and shows the newly created report', async () => {
    render(
      <MemoryRouter initialEntries={['/lost-found?reportType=LOST&status=OPEN&colour=red']}>
        <Routes><Route path="/lost-found" element={<ReportsPage />} /></Routes>
      </MemoryRouter>,
    )

    expect(await screen.findByText('No matching reports')).toBeInTheDocument()
    expect(screen.getByLabelText('Colour')).toHaveValue('red')

    fireEvent.change(screen.getByLabelText('Describe what you lost or want to find'), {
      target: { value: '我今天在中央图书馆丢了黑色耳机' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Confirm' }))

    await waitFor(() => expect(search).toHaveBeenCalledWith(expect.objectContaining({
      reportType: 'LOST',
      status: 'OPEN',
      colour: undefined,
    })))
    expect(screen.getByLabelText('Colour')).toHaveValue('')
    expect(await screen.findByText('黑色耳机')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'View report' })).toHaveAttribute('href', '/lost-found/42')
  })
})
