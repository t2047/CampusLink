import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { invokeLostFoundAgent, uploadAgentImage, type AgentInvokeResponse, type AgentMatchResult } from '../api/lostFoundAgent'
import { searchByImage, searchReports } from '../api/lostFound'
import type { LostFoundReport, PageResponse } from '../types'
import { ReportsPage } from './ReportsPage'

vi.mock('../api/lostFoundAgent', () => ({ invokeLostFoundAgent: vi.fn(), uploadAgentImage: vi.fn() }))
vi.mock('../api/lostFound', () => ({ searchReports: vi.fn(), searchByImage: vi.fn() }))

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
  adminHidden: false,
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

describe('ReportsPage image search', () => {
  const search = vi.mocked(searchReports)
  const searchImage = vi.mocked(searchByImage)
  const upload = vi.mocked(uploadAgentImage)

  const stagedImage = {
    objectKey: 'lost-found-staging/k.png',
    visualFingerprint: 'VF1:fp',
    url: '/api/lost-found/images/staging/k.png',
    contentType: 'image/png',
    originalName: 'a.png',
    fileSize: 100,
  }

  const matchResult: AgentMatchResult = {
    item_id: '42',
    report_type: 'LOST',
    item_name: '黑色耳机',
    category: 'ELECTRONICS',
    description: '黑色 Sony 耳机和充电盒',
    colour: 'black',
    location: '中央图书馆',
    event_date: '2026-08-09',
    time_description: null,
    image_urls: [],
    status: 'OPEN',
    match_score: 1,
    match_reason: ['图片特征相似'],
  }

  beforeEach(() => {
    search.mockReset()
    searchImage.mockReset()
    upload.mockReset()
    search.mockResolvedValue(page([]))
    upload.mockResolvedValue(stagedImage)
  })

  afterEach(() => cleanup())

  function renderPage() {
    render(
      <MemoryRouter initialEntries={['/lost-found?reportType=FOUND&status=OPEN']}>
        <Routes><Route path="/lost-found" element={<ReportsPage />} /></Routes>
      </MemoryRouter>,
    )
  }

  function uploadImage() {
    fireEvent.change(screen.getByLabelText('Search by image'), {
      target: { files: [new File(['x'], 'a.png', { type: 'image/png' })] },
    })
    return screen.findByAltText('a.png')
  }

  it('searches by the staged image and renders matching cards without pagination', async () => {
    searchImage.mockResolvedValue({ status: 'match_found', match_results: [matchResult], request_id: 'trace-search' })
    renderPage()

    await uploadImage()
    fireEvent.click(screen.getByRole('button', { name: 'Search' }))

    await waitFor(() => expect(searchImage).toHaveBeenCalledWith(expect.objectContaining({
      reportType: 'FOUND',
      images: [{
        objectKey: 'lost-found-staging/k.png',
        visualFingerprint: 'VF1:fp',
        url: '/api/lost-found/images/staging/k.png',
      }],
    })))
    expect(await screen.findByText('黑色耳机')).toBeInTheDocument()
    expect(screen.queryByLabelText('Go to next page')).toBeNull()
  })

  it('combines text filters with the image search', async () => {
    searchImage.mockResolvedValue({ status: 'no_match', match_results: [], request_id: 'trace-search' })
    renderPage()

    await uploadImage()
    fireEvent.change(screen.getByLabelText('Keyword'), { target: { value: '耳机' } })
    fireEvent.click(screen.getByRole('button', { name: 'Search' }))

    await waitFor(() => expect(searchImage).toHaveBeenCalledWith(expect.objectContaining({
      reportType: 'FOUND',
      keyword: '耳机',
    })))
    expect(await screen.findByText('无匹配')).toBeInTheDocument()
  })

  it('uses the active report type toggle as the search direction', async () => {
    searchImage.mockResolvedValue({ status: 'no_match', match_results: [], request_id: 'trace-search' })
    renderPage()

    fireEvent.click(screen.getByRole('button', { name: 'Lost items' }))
    await uploadImage()
    fireEvent.click(screen.getByRole('button', { name: 'Search' }))

    await waitFor(() => expect(searchImage).toHaveBeenCalledWith(expect.objectContaining({
      reportType: 'LOST',
    })))
  })

  it('clearing the image falls back to the normal list search', async () => {
    searchImage.mockResolvedValue({ status: 'no_match', match_results: [], request_id: 'trace-search' })
    renderPage()

    await uploadImage()
    fireEvent.click(screen.getByRole('button', { name: 'Remove a.png' }))
    fireEvent.click(screen.getByRole('button', { name: 'Search' }))

    await waitFor(() => expect(search).toHaveBeenCalled())
    expect(searchImage).not.toHaveBeenCalled()
  })
})
