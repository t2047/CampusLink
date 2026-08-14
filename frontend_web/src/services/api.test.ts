import { afterEach, describe, expect, it, vi } from 'vitest'

import { readEventStream, type SseEvent } from './api'

describe('聊天 SSE 解析', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('保留 match_results 事件，供页面渲染候选物品卡片', async () => {
    const payload = {
      items: [{
        item_id: '14',
        report_type: 'FOUND',
        item_name: '紫色保温杯',
        image_urls: [],
        match_score: 0.81,
        match_reason: ['颜色相似'],
      }],
    }
    const body = [
      'event:match_results',
      `data:${JSON.stringify(payload)}`,
      '',
      'event:done',
      'data:{}',
      '',
      '',
    ].join('\n')
    const encoder = new TextEncoder()

    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(
      new ReadableStream({
        start(controller) {
          controller.enqueue(encoder.encode(body))
          controller.close()
        },
      }),
      { status: 200, headers: { 'Content-Type': 'text/event-stream' } },
    )))

    const events: SseEvent[] = []
    await new Promise<void>((resolve, reject) => {
      readEventStream('/api/chat/stream', {}, (event) => {
        events.push(event)
        if (event.type === 'done') resolve()
      }, reject)
    })

    expect(events).toContainEqual({ type: 'match_results', data: payload })
  })
})
