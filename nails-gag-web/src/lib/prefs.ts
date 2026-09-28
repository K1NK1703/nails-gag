/**
 * Настройки оформления, которые посетитель выбирает в шапке («Оформление»): тема и
 * фирменный курсор. Хранятся в localStorage; все обращения к window/document — только
 * в обработчиках и эффектах, на сервере функции чтения возвращают значения по умолчанию.
 */

export type ThemeChoice = 'system' | 'light' | 'dark'
export type EffectiveTheme = 'light' | 'dark'
export type CursorPref = 'on' | 'off'

/** Ключ темы продублирован в инлайн-скрипте index.html — меняйте вместе. */
export const THEME_KEY = 'nails-gag-theme'
export const CURSOR_KEY = 'nails-gag-cursor'
/** Событие на window после любой записи настроек: подписчики перечитывают значения. */
export const PREFS_EVENT = 'nails-gag:prefs'
export const DARK_QUERY = '(prefers-color-scheme: dark)'
/** Цвет адресной строки мобильных браузеров — совпадает с --bg-start темы. */
export const THEME_COLORS: Record<EffectiveTheme, string> = { light: '#ebe3e5', dark: '#1f181c' }

/** Запасная копия на случай недоступного localStorage: выбор живёт до перезагрузки. */
const memory = new Map<string, string | null>()

function read(key: string): string | null {
  if (typeof window === 'undefined') return null
  try {
    return window.localStorage.getItem(key)
  } catch {
    // Приватный режим, запрет хранилища — берём значение из памяти.
    return memory.get(key) ?? null
  }
}

function write(key: string, value: string | null) {
  if (typeof window === 'undefined') return
  memory.set(key, value)
  try {
    if (value === null) window.localStorage.removeItem(key)
    else window.localStorage.setItem(key, value)
  } catch {
    // Не сохранилось — действует копия в памяти.
  }
  window.dispatchEvent(new CustomEvent(PREFS_EVENT))
}

/** Подписка на изменения настроек (эта вкладка — PREFS_EVENT, другие — storage). */
export function subscribePrefs(onChange: () => void): () => void {
  window.addEventListener(PREFS_EVENT, onChange)
  window.addEventListener('storage', onChange)
  return () => {
    window.removeEventListener(PREFS_EVENT, onChange)
    window.removeEventListener('storage', onChange)
  }
}

export function isThemeChoice(value: unknown): value is ThemeChoice {
  return value === 'system' || value === 'light' || value === 'dark'
}

export function readThemeChoice(): ThemeChoice {
  const value = read(THEME_KEY)
  return value === 'light' || value === 'dark' ? value : 'system'
}

/** «Как в системе» не хранится: ключ удаляется, чтобы инлайн-скрипт не ставил data-theme. */
export function writeThemeChoice(choice: ThemeChoice) {
  write(THEME_KEY, choice === 'system' ? null : choice)
}

export function systemPrefersDark(): boolean {
  return (
    typeof window !== 'undefined' &&
    typeof window.matchMedia === 'function' &&
    window.matchMedia(DARK_QUERY).matches
  )
}

export function effectiveTheme(choice: ThemeChoice): EffectiveTheme {
  if (choice !== 'system') return choice
  return systemPrefersDark() ? 'dark' : 'light'
}

/**
 * Ставит/снимает data-theme на <html> и обновляет meta theme-color по действующей схеме
 * (для 'system' — по prefers-color-scheme, если effective не передана).
 */
export function applyThemeChoice(
  choice: ThemeChoice,
  effective: EffectiveTheme = effectiveTheme(choice),
) {
  if (typeof document === 'undefined') return
  const root = document.documentElement
  if (choice === 'system') root.removeAttribute('data-theme')
  else root.setAttribute('data-theme', choice)
  document
    .querySelector('meta[name="theme-color"]')
    ?.setAttribute('content', THEME_COLORS[effective])
}

export function readCursorPref(): CursorPref {
  return read(CURSOR_KEY) === 'off' ? 'off' : 'on'
}

export function writeCursorPref(pref: CursorPref) {
  write(CURSOR_KEY, pref)
}
