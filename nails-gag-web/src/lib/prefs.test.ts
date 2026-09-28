import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  applyThemeChoice,
  PREFS_EVENT,
  readThemeChoice,
  THEME_COLORS,
  THEME_KEY,
  writeThemeChoice,
} from './prefs'

let meta: HTMLMetaElement

beforeEach(() => {
  meta = document.createElement('meta')
  meta.name = 'theme-color'
  meta.content = THEME_COLORS.light
  document.head.append(meta)
})

afterEach(() => {
  meta.remove()
  localStorage.clear()
  document.documentElement.removeAttribute('data-theme')
  vi.restoreAllMocks()
})

describe('theme choice', () => {
  it('по умолчанию светлая, даже если система просит тёмную', () => {
    vi.stubGlobal('matchMedia', (query: string) => ({ matches: true, media: query }))
    expect(readThemeChoice()).toBe('light')
    vi.unstubAllGlobals()
  })

  it('мусор в хранилище (в т.ч. старое «system») читается как светлая', () => {
    for (const value of ['purple', 'system', 'DARK', '']) {
      localStorage.setItem(THEME_KEY, value)
      expect(readThemeChoice()).toBe('light')
    }
  })

  it('тёмная сохраняется, светлая удаляет ключ', () => {
    writeThemeChoice('dark')
    expect(localStorage.getItem(THEME_KEY)).toBe('dark')
    expect(readThemeChoice()).toBe('dark')
    writeThemeChoice('light')
    expect(localStorage.getItem(THEME_KEY)).toBeNull()
    expect(readThemeChoice()).toBe('light')
  })

  it('переживает недоступный localStorage: выбор живёт в памяти', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('denied')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('denied')
    })
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
      throw new Error('denied')
    })
    expect(readThemeChoice()).toBe('light')
    expect(() => writeThemeChoice('dark')).not.toThrow()
    expect(readThemeChoice()).toBe('dark')
    writeThemeChoice('light')
    expect(readThemeChoice()).toBe('light')
  })

  it('после записи шлёт PREFS_EVENT на window', () => {
    const listener = vi.fn()
    window.addEventListener(PREFS_EVENT, listener)
    writeThemeChoice('dark')
    writeThemeChoice('light')
    window.removeEventListener(PREFS_EVENT, listener)
    expect(listener).toHaveBeenCalledTimes(2)
  })

  it('applyThemeChoice ставит/снимает data-theme="dark" и меняет theme-color', () => {
    applyThemeChoice('dark')
    expect(document.documentElement).toHaveAttribute('data-theme', 'dark')
    expect(meta.content).toBe(THEME_COLORS.dark)
    applyThemeChoice('light')
    expect(document.documentElement).not.toHaveAttribute('data-theme')
    expect(meta.content).toBe('#ebe3e5')
  })
})
