import { act, renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { CURSOR_KEY, PREFS_EVENT, writeCursorPref } from './prefs'
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

  it('выключен, если посетитель отключил фирменный курсор', () => {
    stubMatchMedia(true)
    localStorage.setItem(CURSOR_KEY, 'off')
    const { result } = renderHook(() => useCustomCursor())
    expect(result.current.enabled).toBe(false)
  })

  it('перепроверяет настройку по PREFS_EVENT', () => {
    stubMatchMedia(true)
    const { result } = renderHook(() => useCustomCursor())
    expect(result.current.enabled).toBe(true)
    act(() => writeCursorPref('off'))
    expect(result.current.enabled).toBe(false)
    act(() => {
      localStorage.setItem(CURSOR_KEY, 'on')
      window.dispatchEvent(new CustomEvent(PREFS_EVENT))
    })
    expect(result.current.enabled).toBe(true)
  })
})
