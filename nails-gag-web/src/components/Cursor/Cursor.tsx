import { Fragment } from 'react'
import { useCustomCursor } from '../../lib/useCustomCursor'
import styles from './Cursor.module.css'

/** Дизайн маникюра на курсоре: нюдовый френч или магнитный «кошачий глаз». */
type NailStyle = 'french' | 'cat-eye'
// `as` не даёт TS сузить тип до литерала — обе ветки остаются проверяемыми.
const NAIL_STYLE = 'french' as NailStyle

const CAT_EYE_GRADIENT_ID = 'nails-gag-cursor-cat-eye'

/**
 * Силуэт «указующей» кисти (вид с тыльной стороны): указательный палец вытянут, средний,
 * безымянный и мизинец согнуты — видны только костяшки-бугорки по верхнему краю ладони,
 * большой палец прижат слева снизу. Координаты — в системе «палец смотрит вверх», кончик
 * указательного в (0, 0); наклон и масштаб задаёт transform группы.
 */
const HAND =
  'M-1.9 1.9A1.9 1.9 0 0 1 1.9 1.9L2.25 13.6C2.4 12 6.1 11.9 6.2 13.9C6.6 12.6 9.6 13 9.7 15C10.1 14 12.8 14.6 12.7 16.9C13.5 20.5 13.1 24.5 11 27.5L10.4 34.5C10.4 36.6 3.6 36.6 3.6 34.5L3.3 29.5C1 28.3-3.3 24.6-5.5 20.4A1.6 1.6 0 0 1-2.7 19C-2.5 19.6-2.4 19.9-2.25 19.6Z'

/** Складки согнутых пальцев под костяшками. */
const CURLS = 'M2.6 15.6Q4.3 16.9 6.1 15.9M6.4 16.9Q8 18 9.7 17M9.9 18.3Q11.1 19.1 12.2 18.7'

type Nail = { x: number; y: number; w: number; h: number; rot?: number }

/** Видны только два ногтя — указательный и большой; крупнее относительно пальца. */
const NAILS: Nail[] = [
  { x: -1.6, y: 0.35, w: 3.2, h: 5.2 },
  { x: -5.45, y: 18.35, w: 2.5, h: 4, rot: -28 },
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
 * он в точке (3.2, 1.6) viewBox, и .hand сдвинут на эти же пиксели.
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
        <svg width="34" height="40" viewBox="0 0 34 40" focusable="false">
          {NAIL_STYLE === 'cat-eye' && (
            <defs>
              <linearGradient id={CAT_EYE_GRADIENT_ID} gradientTransform="rotate(60 .5 .5)">
                <stop offset="0" stopColor="#7a1e4c" />
                <stop offset=".5" stopColor="#d84f8f" />
                <stop offset="1" stopColor="#7a1e4c" />
              </linearGradient>
            </defs>
          )}
          <g transform="translate(3.2 1.6) rotate(-24) scale(1.02)">
            <path
              d={HAND}
              fill="#f3d6c6"
              stroke="#2b2528"
              strokeOpacity=".35"
              strokeWidth="1.1"
              strokeLinejoin="round"
            />
            <path
              d={CURLS}
              fill="none"
              stroke="#2b2528"
              strokeOpacity=".2"
              strokeWidth=".7"
              strokeLinecap="round"
            />
            <Nails />
          </g>
        </svg>
      </div>
    </div>
  )
}
