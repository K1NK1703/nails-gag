import { Fragment } from 'react'
import { useCustomCursor } from '../../lib/useCustomCursor'
import styles from './Cursor.module.css'

/** Дизайн маникюра на курсоре: нюдовый френч или магнитный «кошачий глаз». */
type NailStyle = 'french' | 'cat-eye'
// `as` не даёт TS сузить тип до литерала — обе ветки остаются проверяемыми.
const NAIL_STYLE = 'french' as NailStyle

const CAT_EYE_GRADIENT_ID = 'nails-gag-cursor-cat-eye'

/**
 * Силуэт тонкой кисти (вид с тыльной стороны, указательный палец вытянут). Координаты —
 * в системе «палец смотрит вверх», кончик указательного в (0, 0); наклон и масштаб задаёт
 * transform группы.
 */
const HAND =
  'M-1.9 1.9A1.9 1.9 0 0 1 1.9 1.9L2.2 16.5Q2.35 17.7 2.5 16.5L2.8 7A1.8 1.8 0 0 1 6.4 7L6.7 17.5Q6.82 18.7 6.95 17.5L7.25 9.25A1.65 1.65 0 0 1 10.55 9.25L10.85 19Q10.98 20.2 11.1 19L11.4 12.8A1.4 1.4 0 0 1 14.2 12.8L14.5 21C14.7 26 13.4 29 11.6 31.5L11 39C11 41.5 3.4 41.5 3.4 39L3.2 33.5C0.8 31.8-3.4 27.6-5.5 23.2A1.5 1.5 0 0 1-2.8 21.9C-2.5 22.6-2.4 22.8-2.25 22.4Z'

type Nail = { x: number; y: number; w: number; h: number; rot?: number }

/** Ногти: указательный, средний, безымянный, мизинец, большой. Высота ≈ 1.6 × ширина. */
const NAILS: Nail[] = [
  { x: -1.45, y: 0.45, w: 2.9, h: 4.8 },
  { x: 3.25, y: 5.6, w: 2.7, h: 4.4 },
  { x: 7.65, y: 7.95, w: 2.5, h: 4.1 },
  { x: 11.7, y: 11.75, w: 2.2, h: 3.55 },
  { x: -5.25, y: 21.2, w: 2.1, h: 3.4, rot: -26 },
]

const round = (n: number) => Math.round(n * 100) / 100

const nailTransform = (n: Nail) =>
  n.rot ? `rotate(${n.rot} ${round(n.x + n.w / 2)} ${round(n.y + n.h / 2)})` : undefined

/** Белый кончик френча: ~30% высоты у свободного края, нижняя граница — «улыбка». */
function frenchTip({ x, y, w, h }: Nail) {
  const edge = round(y + h * 0.32)
  const smile = round(y + h * 0.2)
  const c = Math.min(1.2, w / 2)
  return (
    `M${x} ${edge}V${round(y + c)}Q${x} ${y} ${round(x + c)} ${y}` +
    `H${round(x + w - c)}Q${round(x + w)} ${y} ${round(x + w)} ${round(y + c)}` +
    `V${edge}Q${round(x + w / 2)} ${smile} ${x} ${edge}Z`
  )
}

/** Световая полоса «кошачьего глаза» — по диагонали ногтя. */
function catEyeStreak({ x, y, w, h }: Nail) {
  return `M${round(x + w * 0.22)} ${round(y + h * 0.78)}L${round(x + w * 0.78)} ${round(y + h * 0.22)}`
}

function Nails() {
  if (NAIL_STYLE === 'cat-eye') {
    return (
      <g className={`${styles.nails} ${styles.catEye}`}>
        {NAILS.map((n, i) => (
          <Fragment key={i}>
            <rect
              transform={nailTransform(n)}
              x={n.x}
              y={n.y}
              width={n.w}
              height={n.h}
              rx="1.1"
              fill={`url(#${CAT_EYE_GRADIENT_ID})`}
              stroke="#a52a63"
              strokeWidth=".3"
            />
            <path
              transform={nailTransform(n)}
              d={catEyeStreak(n)}
              stroke="rgba(255,255,255,.75)"
              strokeWidth=".45"
              strokeLinecap="round"
            />
          </Fragment>
        ))}
      </g>
    )
  }
  return (
    <g className={`${styles.nails} ${styles.french}`}>
      {NAILS.map((n, i) => (
        <Fragment key={i}>
          <rect
            transform={nailTransform(n)}
            x={n.x}
            y={n.y}
            width={n.w}
            height={n.h}
            rx="1.1"
            fill="#f6d9d3"
            stroke="rgba(43,37,40,.25)"
            strokeWidth=".35"
          />
          <path transform={nailTransform(n)} d={frenchTip(n)} fill="#ffffff" />
        </Fragment>
      ))}
    </g>
  )
}

/**
 * Курсор-«рука с маникюром» для мыши/трекпада. Кончик указательного ногтя — хотспот:
 * он в точке (4, 2) viewBox, и .hand сдвинут на эти же пиксели.
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
          {NAIL_STYLE === 'cat-eye' && (
            <defs>
              <linearGradient id={CAT_EYE_GRADIENT_ID} gradientTransform="rotate(60 .5 .5)">
                <stop offset="0" stopColor="#7a1e4c" />
                <stop offset=".5" stopColor="#d84f8f" />
                <stop offset="1" stopColor="#7a1e4c" />
              </linearGradient>
            </defs>
          )}
          <g transform="translate(4 2) rotate(-24) scale(1.12)">
            <path
              d={HAND}
              fill="#f3d6c6"
              stroke="#2b2528"
              strokeOpacity=".35"
              strokeWidth="1.1"
              strokeLinejoin="round"
            />
            <Nails />
          </g>
        </svg>
      </div>
    </div>
  )
}
