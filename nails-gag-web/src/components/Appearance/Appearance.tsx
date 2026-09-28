import { useEffect, useId, useRef, useSyncExternalStore } from 'react'
import type { FocusEvent } from 'react'
import { ui } from '../../content/ui'
import { readCursorPref, subscribePrefs, writeCursorPref } from '../../lib/prefs'
import type { ThemeChoice } from '../../lib/prefs'
import { FINE_POINTER } from '../../lib/useCustomCursor'
import { useTheme } from '../../lib/useTheme'
import styles from './Appearance.module.css'

const CHOICES: ThemeChoice[] = ['system', 'light', 'dark']

const serverCursor = () => 'on' as const
const serverFine = () => false

function subscribeFine(onChange: () => void): () => void {
  if (typeof window.matchMedia !== 'function') return () => {}
  const query = window.matchMedia(FINE_POINTER)
  query.addEventListener?.('change', onChange)
  return () => query.removeEventListener?.('change', onChange)
}

function hasFinePointer() {
  return typeof window.matchMedia === 'function' && window.matchMedia(FINE_POINTER).matches
}

/** Солнце и луна рендерятся оба, видимую выбирает CSS по data-theme/prefers-color-scheme —
 *  так иконка верна уже в пререндере, до гидрации. */
function SchemeIcon() {
  return (
    <>
      <svg
        className={styles.sun}
        width="18"
        height="18"
        viewBox="0 0 18 18"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.6"
        strokeLinecap="round"
        aria-hidden="true"
        focusable="false"
      >
        <circle cx="9" cy="9" r="3.4" />
        <path d="M9 1.5v1.8M9 14.7v1.8M1.5 9h1.8M14.7 9h1.8M3.7 3.7l1.3 1.3M13 13l1.3 1.3M3.7 14.3 5 13M13 5l1.3-1.3" />
      </svg>
      <svg
        className={styles.moon}
        width="18"
        height="18"
        viewBox="0 0 18 18"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.6"
        strokeLinejoin="round"
        aria-hidden="true"
        focusable="false"
      >
        <path d="M15.2 11.1A6.6 6.6 0 0 1 6.9 2.8a6.6 6.6 0 1 0 8.3 8.3Z" />
      </svg>
    </>
  )
}

type Props = {
  open: boolean
  onOpenChange: (open: boolean) => void
}

/**
 * «Оформление»: круглая стеклянная кнопка в шапке и поповер с выбором темы и переключателем
 * фирменного курсора (только на устройствах с мышью). Закрывается по Escape (фокус — обратно
 * на кнопку), по клику снаружи и когда фокус уходит за пределы поповера.
 */
export function Appearance({ open, onOpenChange }: Props) {
  const t = ui.appearance
  const id = useId()
  const popoverId = `${id}-appearance`
  const { choice, setChoice } = useTheme()
  const cursor = useSyncExternalStore(subscribePrefs, readCursorPref, serverCursor)
  const fine = useSyncExternalStore(subscribeFine, hasFinePointer, serverFine)

  const rootRef = useRef<HTMLDivElement>(null)
  const buttonRef = useRef<HTMLButtonElement>(null)
  const popoverRef = useRef<HTMLDialogElement>(null)

  // Фокус — на выбранный вариант темы: стрелками сразу можно переключать.
  useEffect(() => {
    if (open) popoverRef.current?.querySelector<HTMLInputElement>('input:checked')?.focus()
  }, [open])

  useEffect(() => {
    if (!open) return
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return
      onOpenChange(false)
      buttonRef.current?.focus()
    }
    const onPointerDown = (e: PointerEvent) => {
      if (e.target instanceof Node && rootRef.current?.contains(e.target)) return
      onOpenChange(false)
    }
    window.addEventListener('keydown', onKeyDown)
    document.addEventListener('pointerdown', onPointerDown)
    return () => {
      window.removeEventListener('keydown', onKeyDown)
      document.removeEventListener('pointerdown', onPointerDown)
    }
  }, [open, onOpenChange])

  const onBlur = (e: FocusEvent<HTMLDivElement>) => {
    const next = e.relatedTarget
    if (open && next instanceof Node && !rootRef.current?.contains(next)) onOpenChange(false)
  }

  return (
    <div ref={rootRef} className={styles.root} onBlur={onBlur}>
      <button
        ref={buttonRef}
        type="button"
        className={styles.button}
        aria-label={t.label}
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-controls={open ? popoverId : undefined}
        onClick={() => onOpenChange(!open)}
      >
        <SchemeIcon />
      </button>

      {open && (
        // Немодальный <dialog open>: страница остаётся доступной, закрытие — наши обработчики.
        <dialog
          ref={popoverRef}
          id={popoverId}
          open
          aria-label={t.label}
          className={styles.popover}
        >
          <fieldset className={styles.group}>
            <legend className={styles.legend}>{t.theme}</legend>
            <div className={styles.segments}>
              {CHOICES.map((c) => (
                <label key={c} className={styles.segment}>
                  <input
                    type="radio"
                    className={styles.radio}
                    name={`${id}-theme`}
                    value={c}
                    checked={choice === c}
                    onChange={() => setChoice(c)}
                  />
                  <span>{t.themes[c]}</span>
                </label>
              ))}
            </div>
          </fieldset>

          {fine && (
            <button
              type="button"
              role="switch"
              aria-checked={cursor === 'on'}
              className={styles.switch}
              onClick={() => writeCursorPref(cursor === 'on' ? 'off' : 'on')}
            >
              <span>{t.cursor}</span>
              <span className={styles.track} aria-hidden="true">
                <span className={styles.thumb} />
              </span>
            </button>
          )}
        </dialog>
      )}
    </div>
  )
}
