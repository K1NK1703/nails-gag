import { useEffect, useState } from 'react'
import { writeThemeChoice } from '../../lib/prefs'
import '../../styles/palette-preview.css'
import styles from './PalettePreview.module.css'

/**
 * ВРЕМЕННО: панель выбора тёмной палитры для владельца сайта. Рендерится только в dev
 * (см. App.tsx), в сборку не попадает. Удалить после выбора палитры.
 */

const KEY = 'nails-gag-palette'
const PALETTES = [
  { id: 'current', label: 'Текущая' },
  { id: 'a', label: 'A · Графит' },
  { id: 'b', label: 'B · Тёплая ночь' },
  { id: 'c', label: 'C · Индиго' },
] as const

type PaletteId = (typeof PALETTES)[number]['id']

function apply(id: PaletteId) {
  const root = document.documentElement
  if (id === 'current') root.removeAttribute('data-palette')
  else root.setAttribute('data-palette', id)
}

function readStored(): PaletteId {
  try {
    const stored = localStorage.getItem(KEY)
    return PALETTES.find((p) => p.id === stored)?.id ?? 'current'
  } catch {
    return 'current'
  }
}

export function PalettePreview() {
  // Компонент грузится только на клиенте (lazy в dev) — localStorage доступен сразу.
  const [palette, setPalette] = useState<PaletteId>(readStored)

  useEffect(() => apply(palette), [palette])

  const choose = (id: PaletteId) => {
    setPalette(id)
    try {
      localStorage.setItem(KEY, id)
    } catch {
      // не сохранилось — действует до перезагрузки
    }
    // палитра видна только в тёмной теме — включаем её
    writeThemeChoice('dark')
  }

  return (
    <fieldset className={styles.panel}>
      <legend className={styles.title}>Тёмная палитра</legend>
      {PALETTES.map((p) => (
        <button
          key={p.id}
          type="button"
          className={styles.option}
          aria-pressed={palette === p.id}
          onClick={() => choose(p.id)}
        >
          {p.label}
        </button>
      ))}
    </fieldset>
  )
}
