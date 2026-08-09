import { describe, expect, it, vi } from 'vitest'
import { apiClient } from './client'
import { invokeLostFoundAgent } from './lostFoundAgent'

describe('Lost & Found Agent API', () => {
  it('sends conversation state through the authenticated backend proxy', async () => {
    const post = vi.spyOn(apiClient, 'post').mockResolvedValue({
      data: { response: '请补充地点', status: 'needs_more_info' },
    })

    await invokeLostFoundAgent({
      message: '我丢了耳机',
      conversationContext: {
        sessionId: 'session-1',
        sharedData: { intent: 'report_lost' },
      },
    })

    expect(post).toHaveBeenCalledWith('/lost-found/agent/invoke', {
      message: '我丢了耳机',
      confirmed: false,
      conversationContext: {
        sessionId: 'session-1',
        sharedData: { intent: 'report_lost' },
      },
    })
  })
})
