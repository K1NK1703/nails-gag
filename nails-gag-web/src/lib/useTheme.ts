import { useCallback, useEffect, useSyncExternalStore } from 'react'
import {
  applyThemeChoice,
  DARK_QUERY,
  readThemeChoice,
  subscribePrefs,
  systemPrefersDark,
  writeThemeChoice,
} from './prefs'
import type { EffectiveTheme, ThemeChoice } from './prefs'

const serverChoice = (): ThemeChoice => 'system'
const serverDark = () => false
const noSubscription = () => () => {}

function subscribeSystem(onChange: () => void): () => void {
  if (typeof window.matchMedia !== 'function') return () => {}
  const query = window.matchMedia(DARK_QUERY)
  query.addEventListener?.('change', onChange)
  return () => query.removeEventListener?.('change', onChange)
}

/**
 * Выбор темы: 'system' | 'light' | 'dark'. На сервере и при гидрации — 'system'/'light'
 * (разметка от темы не зависит), сохранённый выбор подхватывается сразу после гидрации.
 * Пока выбрано «Как в системе», слушает prefers-color-scheme; data-theme и theme-color
 * применяются эффектом.
 */
export function useTheme() {
  const choice = useSyncExternalStore(subscribePrefs, readThemeChoice, serverChoice)
  const systemDark = useSyncExternalStore(
    choice === 'system' ? subscribeSystem : noSubscription,
    systemPrefersDark,
    serverDark,
  )
  const effective: EffectiveTheme = choice === 'system' ? (systemDark ? 'dark' : 'light') : choice

  useEffect(() => {
    applyThemeChoice(choice, effective)
  }, [choice, effective])

  const setChoice = useCallback((next: ThemeChoice) => writeThemeChoice(next), [])

  return { choice, setChoice, effective }
}
