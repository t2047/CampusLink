import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { invokeMailAgent, type MailAgentChatResponse } from '../api/mailAgent'
import { MailAgentPanel } from './MailAgentPanel'

vi.mock('../api/mailAgent', () => ({
  invokeMailAgent: vi.fn(),
}))

const baseResponse: MailAgentChatResponse = {
  response: 'Found 1 email: Exam Reminder',
  status: 'completed',
  session_id: 'mail-test',
  actions_taken: [
    { tool: 'search_mail', args: { query: 'exam', folder: 'inbox' } },
    { tool: 'read_mail', args: { message_id: 'msg-1' } },
  ],
  model: 'test-model',
}

describe('MailAgentPanel', () => {
  const invoke = vi.mocked(invokeMailAgent)

  beforeEach(() => {
    invoke.mockReset()
    invoke.mockResolvedValue(baseResponse)
  })

  afterEach(() => cleanup())

  it('sends a natural language request and renders the agent response', async () => {
    render(<MailAgentPanel />)

    fireEvent.change(screen.getByLabelText('Ask about your mail…'), {
      target: { value: 'find the exam email' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))

    expect(await screen.findByText('Found 1 email: Exam Reminder')).toBeInTheDocument()
    expect(invoke).toHaveBeenCalledWith(expect.objectContaining({
      message: 'find the exam email',
      session_id: expect.stringMatching(/^mail-/),
    }))
  })

  it('renders markdown formatting and the tools the agent called', async () => {
    invoke.mockResolvedValueOnce({
      ...baseResponse,
      response: '**Found 1 email**: Exam Reminder',
    })
    render(<MailAgentPanel />)

    fireEvent.change(screen.getByLabelText('Ask about your mail…'), {
      target: { value: 'find the exam email' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))

    // Markdown: **Found 1 email** renders as a <strong>.
    expect(await screen.findByText('Found 1 email', { selector: 'strong' })).toBeInTheDocument()
    // Tool call chips.
    expect(screen.getByText('搜索邮件')).toBeInTheDocument()
    expect(screen.getByText('阅读邮件')).toBeInTheDocument()
    expect(screen.getByText('{"query":"exam","folder":"inbox"}')).toBeInTheDocument()
  })

  it('renders an error message when the request fails', async () => {
    invoke.mockRejectedValue(new Error('Agent unavailable'))
    render(<MailAgentPanel />)

    fireEvent.change(screen.getByLabelText('Ask about your mail…'), {
      target: { value: 'hello' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))

    expect(await screen.findByText('Agent unavailable')).toBeInTheDocument()
  })
})
