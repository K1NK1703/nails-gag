/**
 * Тема оформления, которую посетитель выбирает переключателем в шапке. По умолчанию —
 * всегда светлая (системная настройка не учитывается); тёмная хранится в localStorage.
 * Все обращения к window/document — только в обработчиках и эффектах, на сервере
 * функции чтения возвращают значения по умолчанию.
 */

export type ThemeChoice = 'light' | 'dark'

/** Ключ темы продублирован в инлайн-скрипте index.html — меняйте вместе. */
export const THEME_KEY = 'nails-gag-theme'
/** Событие на window после записи темы: подписчики перечитывают значение. */
export const PREFS_EVENT = 'nails-gag:prefs'
/**
 * Цвет адресной строки мобильных браузеров. Должен совпадать с --bg-start соответствующей
 * темы в src/styles/tokens.css (светлый — и с meta theme-color в index.html).
 */
export const THEME_COLORS: Record<ThemeChoice, string> = { light: '#ebe3e5', dark: '#171717' }

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

/** Подписка на изменения темы (эта вкладка — PREFS_EVENT, другие — storage). */
export function subscribePrefs(onChange: () => void): () => void {
  window.addEventListener(PREFS_EVENT, onChange)
  window.addEventListener('storage', onChange)
  return () => {
    window.removeEventListener(PREFS_EVENT, onChange)
    window.removeEventListener('storage', onChange)
  }
}

export function readThemeChoice(): ThemeChoice {
  return read(THEME_KEY) === 'dark' ? 'dark' : 'light'
}

/** Светлая — значение по умолчанию и не хранится: ключ удаляется. */
export function writeThemeChoice(choice: ThemeChoice) {
  write(THEME_KEY, choice === 'dark' ? 'dark' : null)
}

/** Ставит/снимает data-theme="dark" на <html> и обновляет meta theme-color. */
export function applyThemeChoice(choice: ThemeChoice) {
  if (typeof document === 'undefined') return
  const root = document.documentElement
  if (choice === 'dark') root.setAttribute('data-theme', 'dark')
  else root.removeAttribute('data-theme')
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', THEME_COLORS[choice])
}
