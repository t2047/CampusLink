import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from './client'
import { invokeLostFoundAgent, uploadAgentImage } from './lostFoundAgent'

describe('Lost & Found Agent API', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

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

    expect(post).toHaveBeenCalledWith(
      '/lost-found/agent/invoke',
      {
        message: '我丢了耳机',
        confirmed: false,
        conversationContext: {
          sessionId: 'session-1',
          sharedData: { intent: 'report_lost' },
        },
      },
      { timeout: 25_000 },
    )
  })

  it('forwards staged images on the invoke request', async () => {
    const post = vi.spyOn(apiClient, 'post').mockResolvedValue({
      data: { response: '找到候选', status: 'match_found' },
    })

    await invokeLostFoundAgent({
      message: '帮我找这个',
      conversationContext: { sessionId: 'session-2', sharedData: {} },
      images: [{
        objectKey: 'lost-found-staging/k.png',
        visualFingerprint: 'VF1:fp',
        url: '/api/lost-found/images/staging/k.png',
        contentType: 'image/png',
        originalName: 'a.png',
        fileSize: 4,
      }],
    })

    expect(post.mock.calls[0][0]).toBe('/lost-found/agent/invoke')
    expect(post.mock.calls[0][1]).toEqual(expect.objectContaining({
      images: [expect.objectContaining({ objectKey: 'lost-found-staging/k.png' })],
    }))
  })

  it('uploads an image to the staging endpoint as multipart', async () => {
    const post = vi.spyOn(apiClient, 'post').mockResolvedValue({
      data: {
        objectKey: 'lost-found-staging/k.png',
        visualFingerprint: 'VF1:fp',
        url: '/api/lost-found/images/staging/k.png',
        contentType: 'image/png',
        originalName: 'a.png',
        fileSize: 4,
      },
    })
    const file = new File([new Uint8Array([1, 2, 3, 4])], 'a.png', { type: 'image/png' })

    await uploadAgentImage(file)

    const [url, form] = post.mock.calls[0]
    expect(url).toBe('/lost-found/agent/upload-image')
    expect(form).toBeInstanceOf(FormData)
    expect((form as FormData).get('image')).toBe(file)
  })
})
