import { useCustomCursor } from '../../lib/useCustomCursor'
import styles from './Cursor.module.css'

/**
 * Курсор-«рука с маникюром» для мыши/трекпада. Кончик указательного ногтя — хотспот:
 * он в точке (4.5, 2) viewBox, и .hand сдвинут на эти же пиксели.
 */
export function Cursor() {
  const { enabled, ref, state, visible } = useCustomCursor()
  if (!enabled) return null

  return (
    <div
      ref={ref}
      aria-hidden="true"
      className={styles.cursor}
      data-state={state}
      data-visible={visible}
    >
      <div className={styles.hand}>
        <svg width="44" height="52" viewBox="0 0 44 52" focusable="false">
          <g transform="translate(4.5 2) rotate(-22) scale(1.1)">
            <path
              d="M-3.5 3.5A3.5 3.5 0 0 1 3.5 3.5L3.5 12.5A3.5 3.5 0 0 1 10.5 12.5L10.5 15.5A3.25 3.25 0 0 1 17 15.5L17 19.5A2.75 2.75 0 0 1 22.5 19.5L22.5 30C22.5 38 17 42 10 42C4 42-1 39-3 35C-6.5 33.5-10.5 29.5-12.3 26.3A3.2 3.2 0 0 1-7 22.6C-5.8 24.4-4.6 25.6-3.5 26.2Z"
              fill="#f3d6c6"
              stroke="#2b2528"
              strokeOpacity=".35"
              strokeWidth="1.25"
              strokeLinejoin="round"
            />
            <path
              d="M3.5 17V23M10.5 19.5V24.5M17 23V26.5"
              fill="none"
              stroke="#2b2528"
              strokeOpacity=".16"
              strokeWidth=".9"
              strokeLinecap="round"
            />
            <g className={styles.nails} stroke="#f7a8c8" strokeWidth=".7">
              <rect x="-2.4" y=".7" width="4.8" height="6.4" rx="1.5" />
              <rect x="4.8" y="9.9" width="4.4" height="5" rx="1.5" />
              <rect x="11.8" y="13.1" width="4" height="4.6" rx="1.5" />
              <rect x="18.3" y="17.5" width="3.4" height="4" rx="1.5" />
              <rect
                x="-11.4"
                y="21.6"
                width="3.8"
                height="4.4"
                rx="1.5"
                transform="rotate(-50 -9.5 23.8)"
              />
            </g>
          </g>
        </svg>
      </div>
    </div>
  )
}
