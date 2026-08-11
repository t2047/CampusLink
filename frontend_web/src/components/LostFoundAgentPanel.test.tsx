import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { invokeLostFoundAgent, type AgentInvokeResponse } from '../api/lostFoundAgent'
import { LostFoundAgentPanel } from './LostFoundAgentPanel'

vi.mock('../api/lostFoundAgent', () => ({
  invokeLostFoundAgent: vi.fn(),
}))

const baseResponse: AgentInvokeResponse = {
  response: '请补充地点和日期。',
  status: 'needs_more_info',
  match_results: [],
  confirmation_required: null,
  shared_context: { intent: 'report_lost', item_name: '黑色耳机' },
  actions_taken: [],
  request_id: 'request-1',
}

describe('LostFoundAgentPanel', () => {
  const invoke = vi.mocked(invokeLostFoundAgent)

  beforeEach(() => {
    invoke.mockReset()
    invoke.mockResolvedValue(baseResponse)
  })

  afterEach(() => cleanup())

  it('sends natural language and renders the Agent response', async () => {
    render(<MemoryRouter><LostFoundAgentPanel /></MemoryRouter>)

    fireEvent.change(screen.getByLabelText('Describe what you lost or want to find'), {
      target: { value: '我丢了一副黑色耳机' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))

    expect(await screen.findByText('请补充地点和日期。')).toBeInTheDocument()
    expect(invoke).toHaveBeenCalledWith(expect.objectContaining({
      message: '我丢了一副黑色耳机',
      conversationContext: expect.objectContaining({ sharedData: {} }),
    }))
  })

  it('uses the one-time confirmation id when the user confirms a write', async () => {
    invoke
      .mockResolvedValueOnce({
        ...baseResponse,
        response: '请确认报失信息。',
        status: 'needs_confirmation',
        confirmation_required: {
          confirmation_id: 'confirmation-1',
          action: 'report_lost',
          summary: '黑色耳机，中央图书馆',
          expires_at: '2026-08-09T14:00:00Z',
        },
      })
      .mockResolvedValueOnce({
        ...baseResponse,
        response: '报失记录已创建。',
        status: 'completed',
        confirmation_required: null,
        shared_context: {},
        actions_taken: [{ action: 'report_lost', status: 'success', result_summary: 'report_id=42' }],
        request_id: 'request-2',
      })
    const onReportCreated = vi.fn()
    render(<MemoryRouter><LostFoundAgentPanel onReportCreated={onReportCreated} /></MemoryRouter>)

    fireEvent.change(screen.getByLabelText('Describe what you lost or want to find'), {
      target: { value: '完整的报失描述' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Confirm' }))

    await waitFor(() => expect(invoke).toHaveBeenCalledTimes(2))
    expect(invoke.mock.calls[1][0]).toEqual(expect.objectContaining({
      message: '确认',
      confirmed: true,
      confirmationId: 'confirmation-1',
    }))
    expect(await screen.findByText('报失记录已创建。')).toBeInTheDocument()
    expect(onReportCreated).toHaveBeenCalledWith(42)
    expect(screen.getByRole('link', { name: 'View report' })).toHaveAttribute('href', '/lost-found/42')
  })

  it('keeps shared context across a multi-turn conversation', async () => {
    invoke
      .mockResolvedValueOnce(baseResponse)
      .mockResolvedValueOnce({
        ...baseResponse,
        response: '请确认报失信息。',
        status: 'needs_confirmation',
        confirmation_required: {
          confirmation_id: 'confirmation-2',
          action: 'report_lost',
          summary: '黑色耳机，中央图书馆',
          expires_at: '2026-08-09T14:00:00Z',
        },
        request_id: 'request-2',
      })
    render(<MemoryRouter><LostFoundAgentPanel /></MemoryRouter>)

    const input = screen.getByLabelText('Describe what you lost or want to find')
    fireEvent.change(input, { target: { value: '我丢了一副黑色耳机' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))
    expect(await screen.findByText('请补充地点和日期。')).toBeInTheDocument()

    fireEvent.change(input, { target: { value: '地点是中央图书馆，日期是2026-08-09' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))

    await waitFor(() => expect(invoke).toHaveBeenCalledTimes(2))
    expect(invoke.mock.calls[1][0].conversationContext.sharedData).toEqual(baseResponse.shared_context)
  })

  it('cancels a pending write without invoking the Agent again', async () => {
    invoke.mockResolvedValueOnce({
      ...baseResponse,
      status: 'needs_confirmation',
      confirmation_required: {
        confirmation_id: 'confirmation-cancel',
        action: 'report_lost',
        summary: '黑色耳机，中央图书馆',
        expires_at: '2026-08-09T14:00:00Z',
      },
    })
    render(<MemoryRouter><LostFoundAgentPanel /></MemoryRouter>)

    fireEvent.change(screen.getByLabelText('Describe what you lost or want to find'), {
      target: { value: '完整的报失描述' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Cancel' }))

    expect(await screen.findByText(/操作已取消/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Confirm' })).not.toBeInTheDocument()
    expect(invoke).toHaveBeenCalledTimes(1)
  })

  it('shows a recoverable error when the Agent service is unavailable', async () => {
    invoke.mockRejectedValueOnce(new Error('Lost & Found Agent is temporarily unavailable'))
    render(<MemoryRouter><LostFoundAgentPanel /></MemoryRouter>)

    fireEvent.change(screen.getByLabelText('Describe what you lost or want to find'), {
      target: { value: '帮我找蓝色雨伞' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))

    expect(await screen.findByText('Lost & Found Agent is temporarily unavailable')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Describe what you lost or want to find'), {
      target: { value: '重试搜索蓝色雨伞' },
    })
    expect(screen.getByRole('button', { name: 'Send' })).toBeEnabled()
  })

  it('renders bidirectional match details and a report link', async () => {
    invoke.mockResolvedValueOnce({
      ...baseResponse,
      response: '找到 1 个匹配度较高的候选物品。',
      status: 'match_found',
      match_results: [{
        item_id: '88',
        report_type: 'LOST',
        item_name: '红色折叠伞',
        category: 'UMBRELLA',
        description: '红色折叠伞，白色手柄上贴有姓名标签',
        colour: '红色',
        location: 'UHC',
        event_date: '2026-08-09',
        time_description: '下午',
        image_urls: ['https://images.example.test/88.jpg'],
        status: 'OPEN',
        match_score: 0.91,
        match_reason: ['物品类别一致', '颜色相似'],
      }],
      actions_taken: [{ action: 'search_lost_items', status: 'success' }],
    })
    render(<MemoryRouter><LostFoundAgentPanel /></MemoryRouter>)

    fireEvent.change(screen.getByLabelText('Describe what you lost or want to find'), {
      target: { value: '我捡到一把红色雨伞' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))

    expect(await screen.findByRole('link', { name: '#88 [LOST] 红色折叠伞' })).toHaveAttribute(
      'href',
      '/lost-found/88',
    )
    expect(screen.getByText(/UMBRELLA · 红色 · UHC/)).toBeInTheDocument()
    expect(screen.getByText('红色折叠伞，白色手柄上贴有姓名标签')).toBeInTheDocument()
    expect(screen.getByAltText('红色折叠伞')).toHaveAttribute(
      'src',
      'https://images.example.test/88.jpg',
    )
  })
})
