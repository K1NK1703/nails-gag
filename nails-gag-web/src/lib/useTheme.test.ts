import { act, renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { THEME_KEY } from './prefs'
import { useTheme } from './useTheme'

afterEach(() => {
  localStorage.clear()
  document.documentElement.removeAttribute('data-theme')
})

describe('useTheme', () => {
  it('по умолчанию светлая, data-theme не ставится', () => {
    const { result } = renderHook(() => useTheme())
    expect(result.current.theme).toBe('light')
    expect(document.documentElement).not.toHaveAttribute('data-theme')
  })

  it('после монтирования применяет сохранённую тёмную', () => {
    localStorage.setItem(THEME_KEY, 'dark')
    const { result } = renderHook(() => useTheme())
    expect(result.current.theme).toBe('dark')
    expect(document.documentElement).toHaveAttribute('data-theme', 'dark')
  })

  it('toggle переключает и сохраняет, setTheme задаёт явно', () => {
    const { result } = renderHook(() => useTheme())
    act(() => result.current.toggle())
    expect(result.current.theme).toBe('dark')
    expect(localStorage.getItem(THEME_KEY)).toBe('dark')
    expect(document.documentElement).toHaveAttribute('data-theme', 'dark')
    act(() => result.current.toggle())
    expect(result.current.theme).toBe('light')
    expect(localStorage.getItem(THEME_KEY)).toBeNull()
    expect(document.documentElement).not.toHaveAttribute('data-theme')
    act(() => result.current.setTheme('dark'))
    expect(result.current.theme).toBe('dark')
  })

  it('экземпляры хука синхронизируются через PREFS_EVENT', () => {
    const a = renderHook(() => useTheme())
    const b = renderHook(() => useTheme())
    act(() => a.result.current.setTheme('dark'))
    expect(b.result.current.theme).toBe('dark')
  })

  it('подхватывает изменение из другой вкладки (storage)', () => {
    const { result } = renderHook(() => useTheme())
    act(() => {
      localStorage.setItem(THEME_KEY, 'dark')
      window.dispatchEvent(new StorageEvent('storage', { key: THEME_KEY }))
    })
    expect(result.current.theme).toBe('dark')
  })
})
