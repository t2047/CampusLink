import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  invokeLostFoundAgent,
  uploadAgentImage,
  type AgentInvokeResponse,
  type StagedAgentImage,
} from '../api/lostFoundAgent'
import { AuthProvider } from '../auth/AuthContext'
import { LostFoundAgentPanel } from './LostFoundAgentPanel'

vi.mock('../api/lostFoundAgent', () => ({
  invokeLostFoundAgent: vi.fn(),
  uploadAgentImage: vi.fn(),
}))

function renderPanel(props?: React.ComponentProps<typeof LostFoundAgentPanel>) {
  return render(
    <MemoryRouter>
      <AuthProvider>
        <LostFoundAgentPanel {...props} />
      </AuthProvider>
    </MemoryRouter>,
  )
}

/** 预设某账号的 active session，模拟刷新后页面恢复该会话。 */
function seedActiveSession(email: string, sessionId: string) {
  window.localStorage.setItem(`lf-active-session-${email}`, sessionId)
}

function stagedImage(objectKey: string, url: string, name = 'a.png'): StagedAgentImage {
  return {
    objectKey,
    visualFingerprint: 'VF1:fp',
    url,
    contentType: 'image/png',
    originalName: name,
    fileSize: 4,
  }
}

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
    window.localStorage.clear()
    invoke.mockReset()
    invoke.mockResolvedValue(baseResponse)
    vi.mocked(uploadAgentImage).mockReset()
  })

  afterEach(() => cleanup())

  it('sends natural language and renders the Agent response', async () => {
    renderPanel()

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
    renderPanel({ onReportCreated })

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
    renderPanel()

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
    renderPanel()

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
    renderPanel()

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

  it('stages selected images and includes them in the invoke request', async () => {
    const upload = vi.mocked(uploadAgentImage)
    upload.mockResolvedValue(stagedImage('lost-found-staging/k1.png', '/api/lost-found/images/staging/k1.png'))
    renderPanel()

    const input = document.querySelector('input[type="file"]') as HTMLInputElement
    const file = new File([new Uint8Array([1, 2, 3, 4])], 'a.png', { type: 'image/png' })
    fireEvent.change(input, { target: { files: [file] } })

    expect(await screen.findByAltText('a.png')).toBeInTheDocument()
    expect(upload).toHaveBeenCalledWith(file)

    fireEvent.change(screen.getByLabelText('Describe what you lost or want to find'), {
      target: { value: '帮我找这个' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))

    await waitFor(() => expect(invoke).toHaveBeenCalledTimes(1))
    expect(invoke.mock.calls[0][0].images).toEqual([
      expect.objectContaining({ objectKey: 'lost-found-staging/k1.png' }),
    ])
  })

  it('sends a placeholder message when only images are attached', async () => {
    vi.mocked(uploadAgentImage).mockResolvedValue(
      stagedImage('lost-found-staging/k1.png', '/api/lost-found/images/staging/k1.png'),
    )
    renderPanel()

    const input = document.querySelector('input[type="file"]') as HTMLInputElement
    fireEvent.change(input, {
      target: { files: [new File([new Uint8Array([1, 2, 3, 4])], 'a.png', { type: 'image/png' })] },
    })
    await screen.findByAltText('a.png')

    fireEvent.click(screen.getByRole('button', { name: 'Send' }))

    await waitFor(() => expect(invoke).toHaveBeenCalledTimes(1))
    expect(invoke.mock.calls[0][0].message).toBe('帮我找这个')
  })

  it('removes a staged image on click', async () => {
    vi.mocked(uploadAgentImage).mockResolvedValue(
      stagedImage('lost-found-staging/k1.png', '/api/lost-found/images/staging/k1.png'),
    )
    renderPanel()

    const input = document.querySelector('input[type="file"]') as HTMLInputElement
    fireEvent.change(input, {
      target: { files: [new File([new Uint8Array([1, 2, 3, 4])], 'a.png', { type: 'image/png' })] },
    })

    expect(await screen.findByAltText('a.png')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Remove a.png' }))
    expect(screen.queryByAltText('a.png')).not.toBeInTheDocument()
  })

  it('shows a validation error for oversized images without uploading', async () => {
    const upload = vi.mocked(uploadAgentImage)
    renderPanel()

    const input = document.querySelector('input[type="file"]') as HTMLInputElement
    const big = new File([new Uint8Array(10 * 1024 * 1024 + 1)], 'big.png', { type: 'image/png' })
    fireEvent.change(input, { target: { files: [big] } })

    expect(await screen.findByText(/no larger than 10 MB/)).toBeInTheDocument()
    expect(upload).not.toHaveBeenCalled()
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
    renderPanel()

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

  it('restores the active session for the logged-in account on mount', async () => {
    // 模拟已登录账号 + 该账号存在持久化的 active session（刷新页面场景，§8.1）
    sessionStorage.setItem('campuslink.token', 'test-token')
    sessionStorage.setItem(
      'campuslink.user',
      JSON.stringify({ email: 'student@example.edu', role: 'STUDENT' }),
    )
    seedActiveSession('student@example.edu', 'web-persisted-1')
    renderPanel()

    fireEvent.change(screen.getByLabelText('Describe what you lost or want to find'), {
      target: { value: '我丢了一把伞' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))

    await waitFor(() => expect(invoke).toHaveBeenCalledTimes(1))
    expect(invoke.mock.calls[0][0].conversationContext.sessionId).toBe('web-persisted-1')
  })

  it('persists a freshly generated session under the account key', async () => {
    sessionStorage.setItem('campuslink.token', 'test-token')
    sessionStorage.setItem(
      'campuslink.user',
      JSON.stringify({ email: 'student@example.edu', role: 'STUDENT' }),
    )
    renderPanel()

    fireEvent.change(screen.getByLabelText('Describe what you lost or want to find'), {
      target: { value: '我丢了一把伞' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))

    await waitFor(() => expect(invoke).toHaveBeenCalledTimes(1))
    const sentSessionId = invoke.mock.calls[0][0].conversationContext.sessionId as string
    expect(sentSessionId).toMatch(/^web-/)
    expect(window.localStorage.getItem('lf-active-session-student@example.edu')).toBe(sentSessionId)
  })

  it('starts a new conversation with a fresh session and overwrites the active key', async () => {
    sessionStorage.setItem('campuslink.token', 'test-token')
    sessionStorage.setItem(
      'campuslink.user',
      JSON.stringify({ email: 'student@example.edu', role: 'STUDENT' }),
    )
    seedActiveSession('student@example.edu', 'web-old-1')
    renderPanel()

    // 先发一轮，拿到当前（恢复的）会话 id
    fireEvent.change(screen.getByLabelText('Describe what you lost or want to find'), {
      target: { value: '我丢了一把伞' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))
    await waitFor(() => expect(invoke).toHaveBeenCalledTimes(1))
    const oldSessionId = invoke.mock.calls[0][0].conversationContext.sessionId as string
    expect(oldSessionId).toBe('web-old-1')

    // 点击 New conversation：生成新会话并覆盖 active key，历史消息清空
    fireEvent.click(screen.getByRole('button', { name: 'New conversation' }))
    fireEvent.change(screen.getByLabelText('Describe what you lost or want to find'), {
      target: { value: '再找一把钥匙' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))

    await waitFor(() => expect(invoke).toHaveBeenCalledTimes(2))
    const newSessionId = invoke.mock.calls[1][0].conversationContext.sessionId as string
    expect(newSessionId).toMatch(/^web-/)
    expect(newSessionId).not.toBe(oldSessionId)
    expect(window.localStorage.getItem('lf-active-session-student@example.edu')).toBe(newSessionId)
  })
})
