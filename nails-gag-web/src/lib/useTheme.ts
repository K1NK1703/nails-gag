import { useCallback, useEffect, useSyncExternalStore } from 'react'
import { applyThemeChoice, readThemeChoice, subscribePrefs, writeThemeChoice } from './prefs'
import type { ThemeChoice } from './prefs'

const serverTheme = (): ThemeChoice => 'light'

/**
 * Тема: 'light' (по умолчанию) | 'dark'. На сервере и при гидрации — 'light' (разметка от
 * темы не зависит), сохранённый выбор подхватывается сразу после гидрации; data-theme и
 * theme-color применяются эффектом. Экземпляры и вкладки синхронизируются через subscribePrefs.
 */
export function useTheme() {
  const theme = useSyncExternalStore(subscribePrefs, readThemeChoice, serverTheme)

  useEffect(() => {
    applyThemeChoice(theme)
  }, [theme])

  const setTheme = useCallback((next: ThemeChoice) => writeThemeChoice(next), [])
  const toggle = useCallback(
    () => writeThemeChoice(readThemeChoice() === 'dark' ? 'light' : 'dark'),
    [],
  )

  return { theme, setTheme, toggle }
}
