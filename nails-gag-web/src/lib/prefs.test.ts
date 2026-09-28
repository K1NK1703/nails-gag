import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  applyThemeChoice,
  CURSOR_KEY,
  effectiveTheme,
  PREFS_EVENT,
  readCursorPref,
  readThemeChoice,
  THEME_KEY,
  writeCursorPref,
  writeThemeChoice,
} from './prefs'

function stubDark(dark: boolean) {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: query.includes('dark') ? dark : false,
    media: query,
    addEventListener: () => {},
    removeEventListener: () => {},
  }))
}

let meta: HTMLMetaElement

beforeEach(() => {
  meta = document.createElement('meta')
  meta.name = 'theme-color'
  meta.content = '#ebe3e5'
  document.head.append(meta)
})

afterEach(() => {
  meta.remove()
  localStorage.clear()
  document.documentElement.removeAttribute('data-theme')
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('theme choice', () => {
  it('по умолчанию «как в системе», мусор в хранилище игнорируется', () => {
    expect(readThemeChoice()).toBe('system')
    localStorage.setItem(THEME_KEY, 'purple')
    expect(readThemeChoice()).toBe('system')
  })

  it('сохраняет явный выбор, а «как в системе» удаляет ключ', () => {
    writeThemeChoice('dark')
    expect(localStorage.getItem(THEME_KEY)).toBe('dark')
    expect(readThemeChoice()).toBe('dark')
    writeThemeChoice('system')
    expect(localStorage.getItem(THEME_KEY)).toBeNull()
    expect(readThemeChoice()).toBe('system')
  })

  it('переживает недоступный localStorage', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('denied')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('denied')
    })
    expect(readThemeChoice()).toBe('system')
    expect(() => writeThemeChoice('dark')).not.toThrow()
    expect(readCursorPref()).toBe('on')
  })

  it('effectiveTheme для «как в системе» следует prefers-color-scheme', () => {
    stubDark(true)
    expect(effectiveTheme('system')).toBe('dark')
    expect(effectiveTheme('light')).toBe('light')
    stubDark(false)
    expect(effectiveTheme('system')).toBe('light')
    expect(effectiveTheme('dark')).toBe('dark')
  })

  it('без matchMedia «как в системе» — светлая', () => {
    vi.stubGlobal('matchMedia', undefined)
    expect(effectiveTheme('system')).toBe('light')
  })

  it('applyThemeChoice ставит data-theme и theme-color', () => {
    stubDark(false)
    applyThemeChoice('dark')
    expect(document.documentElement).toHaveAttribute('data-theme', 'dark')
    expect(meta.content).toBe('#1f181c')
    applyThemeChoice('light')
    expect(document.documentElement).toHaveAttribute('data-theme', 'light')
    expect(meta.content).toBe('#ebe3e5')
    stubDark(true)
    applyThemeChoice('system')
    expect(document.documentElement).not.toHaveAttribute('data-theme')
    expect(meta.content).toBe('#1f181c')
  })
})

describe('cursor pref', () => {
  it('по умолчанию включён, запись сохраняется', () => {
    expect(readCursorPref()).toBe('on')
    writeCursorPref('off')
    expect(localStorage.getItem(CURSOR_KEY)).toBe('off')
    expect(readCursorPref()).toBe('off')
    writeCursorPref('on')
    expect(readCursorPref()).toBe('on')
  })

  it('после записи шлёт PREFS_EVENT на window', () => {
    const listener = vi.fn()
    window.addEventListener(PREFS_EVENT, listener)
    writeCursorPref('off')
    writeThemeChoice('light')
    window.removeEventListener(PREFS_EVENT, listener)
    expect(listener).toHaveBeenCalledTimes(2)
  })
})
