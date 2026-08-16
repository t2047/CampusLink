// useSpeechRecognition hook 测试（mock window.SpeechRecognition）
import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useSpeechRecognition } from './useSpeechRecognition'

class FakeRecognition {
  lang = ''
  continuous = false
  interimResults = false
  maxAlternatives = 1
  onresult: ((e: unknown) => void) | null = null
  onend: (() => void) | null = null
  onerror: ((e: { error?: string }) => void) | null = null
  start = vi.fn()
  stop = vi.fn()
  abort = vi.fn()
}

function fireResult(rec: FakeRecognition, transcripts: { final: boolean; text: string }[]) {
  rec.onresult?.({
    resultIndex: 0,
    results: transcripts.map((t) => ({ isFinal: t.final, 0: { transcript: t.text } })),
  } as never)
}

// Window → Record<string, unknown> 需先经 unknown（TS2352），统一用 win
const win = window as unknown as Record<string, unknown>

describe('useSpeechRecognition', () => {
  const original = win.SpeechRecognition

  afterEach(() => {
    vi.restoreAllMocks()
    if (original === undefined) delete win.SpeechRecognition
    else win.SpeechRecognition = original
  })

  beforeEach(() => {
    delete win.SpeechRecognition
    delete win.webkitSpeechRecognition
  })

  it('supported=false when SpeechRecognition is unavailable', () => {
    const { result } = renderHook(() => useSpeechRecognition('zh-CN'))
    expect(result.current.supported).toBe(false)
    expect(result.current.listening).toBe(false)
    // start 不抛错（静默 no-op）
    act(() => result.current.start({ onFinal: vi.fn() }))
    expect(result.current.listening).toBe(false)
  })

  it('start sets listening and passes lang/options to the recognition instance', () => {
    const ctor = vi.fn(function () { return new FakeRecognition() })
    ;win.SpeechRecognition = ctor as never

    const onFinal = vi.fn()
    const { result } = renderHook(() => useSpeechRecognition('zh-CN'))

    expect(result.current.supported).toBe(true)
    act(() => result.current.start({ onFinal }))

    expect(ctor).toHaveBeenCalledTimes(1)
    const rec = ctor.mock.results[0].value as FakeRecognition
    expect(rec.lang).toBe('zh-CN')
    expect(rec.continuous).toBe(false)
    expect(rec.interimResults).toBe(true)
    expect(rec.start).toHaveBeenCalled()
    expect(result.current.listening).toBe(true)
  })

  it('commits final transcript via onFinal on end', () => {
    const ctor = vi.fn(function () { return new FakeRecognition() })
    ;win.SpeechRecognition = ctor as never

    const onFinal = vi.fn()
    const { result } = renderHook(() => useSpeechRecognition('en-US'))
    act(() => result.current.start({ onFinal }))

    const rec = ctor.mock.results[0].value as FakeRecognition
    act(() => {
      fireResult(rec, [
        { final: true, text: 'where is my ' },
        { final: true, text: 'calculator' },
      ])
      rec.onend?.()
    })

    expect(onFinal).toHaveBeenCalledWith('where is my calculator')
    expect(result.current.listening).toBe(false)
  })

  it('stop delegates to recognition.stop()', () => {
    const ctor = vi.fn(function () { return new FakeRecognition() })
    ;win.SpeechRecognition = ctor as never

    const { result } = renderHook(() => useSpeechRecognition('en-US'))
    act(() => result.current.start({ onFinal: vi.fn() }))
    const rec = ctor.mock.results[0].value as FakeRecognition

    act(() => result.current.stop())
    expect(rec.stop).toHaveBeenCalled()
  })

  it('unmount aborts the active recognition', () => {
    const ctor = vi.fn(function () { return new FakeRecognition() })
    ;win.SpeechRecognition = ctor as never

    const { result, unmount } = renderHook(() => useSpeechRecognition('en-US'))
    act(() => result.current.start({ onFinal: vi.fn() }))
    const rec = ctor.mock.results[0].value as FakeRecognition

    unmount()
    expect(rec.abort).toHaveBeenCalled()
  })

  it('stale onend from an aborted session does not clobber the new session', () => {
    const ctor = vi.fn(function () { return new FakeRecognition() })
    ;win.SpeechRecognition = ctor as never

    const onFinal = vi.fn()
    const { result } = renderHook(() => useSpeechRecognition('en-US'))

    act(() => result.current.start({ onFinal }))
    const first = ctor.mock.results[0].value as FakeRecognition
    act(() => result.current.start({ onFinal })) // 第二次 start（会 abort 第一次）
    const second = ctor.mock.results[1].value as FakeRecognition
    expect(result.current.listening).toBe(true)

    // 第一次（已 abort）的 onend 迟到触发：不得清理新会话状态
    act(() => first.onend?.())
    expect(result.current.listening).toBe(true)
    expect(onFinal).not.toHaveBeenCalled()

    // 新会话产生 final 后正常结束才提交并复位状态
    act(() => {
      fireResult(second, [{ final: true, text: 'hello' }])
      second.onend?.()
    })
    expect(result.current.listening).toBe(false)
    expect(onFinal).toHaveBeenCalledWith('hello')
  })
})
