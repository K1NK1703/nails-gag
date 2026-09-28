import { act, renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useCustomCursor } from './useCustomCursor'

function stubMatchMedia(fine: boolean, reducedMotion = false, forcedColors = false) {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: query.includes('reduce')
      ? reducedMotion
      : query.includes('forced-colors')
        ? forcedColors
        : fine,
    media: query,
    addEventListener: () => {},
    removeEventListener: () => {},
  }))
}

afterEach(() => {
  localStorage.clear()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('useCustomCursor', () => {
  it('на тач-устройстве выключен и не вешает слушатели', () => {
    stubMatchMedia(false)
    const add = vi.spyOn(document, 'addEventListener')
    const { result } = renderHook(() => useCustomCursor())
    expect(result.current.enabled).toBe(false)
    expect(add.mock.calls.some(([type]) => String(type).startsWith('pointer'))).toBe(false)
  })

  it('при prefers-reduced-motion выключен', () => {
    stubMatchMedia(true, true)
    const { result } = renderHook(() => useCustomCursor())
    expect(result.current.enabled).toBe(false)
  })

  it('без matchMedia (jsdom/SSR-подобная среда) выключен', () => {
    vi.stubGlobal('matchMedia', undefined)
    const { result } = renderHook(() => useCustomCursor())
    expect(result.current.enabled).toBe(false)
  })

  it('на устройстве с мышью включён, пока без движения невидим', () => {
    stubMatchMedia(true)
    const { result } = renderHook(() => useCustomCursor())
    expect(result.current.enabled).toBe(true)
    expect(result.current.visible).toBe(false)
    expect(result.current.state).toBe('default')
    act(() => {
      document.dispatchEvent(new MouseEvent('pointerdown', { bubbles: true }))
    })
    // Элемент не привязан к ref — слушатели не вешаются, состояние не меняется.
    expect(result.current.state).toBe('default')
  })

  it('в режиме forced-colors выключен', () => {
    stubMatchMedia(true, false, true)
    const { result } = renderHook(() => useCustomCursor())
    expect(result.current.enabled).toBe(false)
  })
})
