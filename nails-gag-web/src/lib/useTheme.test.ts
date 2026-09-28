import { act, renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { THEME_KEY } from './prefs'
import { useTheme } from './useTheme'

/** matchMedia с управляемым prefers-color-scheme и рассылкой change. */
function stubSystem(initialDark: boolean) {
  let dark = initialDark
  const listeners = new Set<() => void>()
  vi.stubGlobal('matchMedia', (query: string) => ({
    get matches() {
      return query.includes('dark') ? dark : false
    },
    media: query,
    addEventListener: (_: string, cb: () => void) => listeners.add(cb),
    removeEventListener: (_: string, cb: () => void) => listeners.delete(cb),
  }))
  return {
    listeners,
    set(next: boolean) {
      dark = next
      for (const cb of listeners) cb()
    },
  }
}

afterEach(() => {
  localStorage.clear()
  document.documentElement.removeAttribute('data-theme')
  vi.unstubAllGlobals()
})

describe('useTheme', () => {
  it('после монтирования применяет сохранённый выбор', () => {
    stubSystem(false)
    localStorage.setItem(THEME_KEY, 'dark')
    const { result } = renderHook(() => useTheme())
    expect(result.current.choice).toBe('dark')
    expect(result.current.effective).toBe('dark')
    expect(document.documentElement).toHaveAttribute('data-theme', 'dark')
  })

  it('«как в системе» следует за prefers-color-scheme', () => {
    const system = stubSystem(false)
    const { result } = renderHook(() => useTheme())
    expect(result.current.choice).toBe('system')
    expect(result.current.effective).toBe('light')
    expect(document.documentElement).not.toHaveAttribute('data-theme')
    act(() => system.set(true))
    expect(result.current.effective).toBe('dark')
  })

  it('setChoice сохраняет выбор и перестаёт слушать систему', () => {
    const system = stubSystem(true)
    const { result } = renderHook(() => useTheme())
    expect(system.listeners.size).toBe(1)
    act(() => result.current.setChoice('light'))
    expect(result.current.choice).toBe('light')
    expect(result.current.effective).toBe('light')
    expect(localStorage.getItem(THEME_KEY)).toBe('light')
    expect(document.documentElement).toHaveAttribute('data-theme', 'light')
    expect(system.listeners.size).toBe(0)
    act(() => result.current.setChoice('system'))
    expect(result.current.effective).toBe('dark')
    expect(document.documentElement).not.toHaveAttribute('data-theme')
    expect(localStorage.getItem(THEME_KEY)).toBeNull()
  })

  it('экземпляры хука синхронизируются через PREFS_EVENT', () => {
    stubSystem(false)
    const a = renderHook(() => useTheme())
    const b = renderHook(() => useTheme())
    act(() => a.result.current.setChoice('dark'))
    expect(b.result.current.choice).toBe('dark')
  })
})
