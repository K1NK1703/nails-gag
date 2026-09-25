import { useCustomCursor } from '../../lib/useCustomCursor'
import styles from './Cursor.module.css'

/** Дизайн маникюра на курсоре: нюдовый френч или магнитный «кошачий глаз». */
type NailStyle = 'french' | 'cat-eye'
// `as` не даёт TS сузить тип до литерала — обе ветки остаются проверяемыми.
const NAIL_STYLE = 'cat-eye' as NailStyle

const CAT_EYE_GRADIENT_ID = 'nails-gag-cursor-cat-eye'

/**
 * Тонкая кисть в духе эмодзи 💅 (вид с тыльной стороны): пять пальцев слегка веером,
 * указательный — самый длинный. Координаты — в системе, где кончик указательного в (0, 0);
 * наклон и масштаб задаёт transform группы.
 */
const HAND =
  'M0.96 15.82L-1.18 1.68A1.45 1.45 0 0 1 1.68 1.18L4.51 15.2Q4.65 17.54 4.79 16.24L4.91 3.24A1.4 1.4 0 0 1 7.71 3.19L8.28 16.18Q8.37 18.59 8.45 17.29L10.33 6.04A1.3 1.3 0 0 1 12.91 6.4L11.62 17.73Q11.71 20.3 11.8 19L14.71 10.48A1.1 1.1 0 0 1 16.82 11.13L14.47 19.82C14.83 24.01 13.93 27.01 12.43 29.11L11.93 33.51C11.93 35.31 6.13 35.31 6.13 33.51L5.93 29.71C4.53 28.71 0.21 24.05 0.21 24.05L-4.68 19.3A1.25 1.25 0 0 1 -3.07 17.38L2.46 21.37Q2.01 19 0.96 15.82Z'

/** Мягкая тень вдоль ребра ладони — для объёма. */
const SHADE = 'M13.93 21.01C14.33 24.51 13.33 27.31 11.63 29.11L11.23 33.51'

/** Ноготь в локальных координатах пальца: центр подушечки (cx, cy), наклон a, размер w×h. */
type Nail = { cx: number; cy: number; a: number; w: number; h: number }

/** Ногти ≈ 1.7 × ширину в высоту, почти во всю ширину подушечки. */
const NAILS: Nail[] = [
  { cx: 0.25, cy: 1.43, a: -10, w: 2.44, h: 4.15 }, // указательный
  { cx: 6.31, cy: 3.21, a: -1, w: 2.35, h: 4 }, // средний
  { cx: 11.62, cy: 6.22, a: 8, w: 2.18, h: 3.71 }, // безымянный
  { cx: 15.77, cy: 10.8, a: 17, w: 1.85, h: 3.15 }, // мизинец
  { cx: -3.87, cy: 18.34, a: -50, w: 2.1, h: 3.57 }, // большой
]

const round = (n: number) => Math.round(n * 100) / 100

/** Верх ногтя — чуть ниже края пальца (подушечка радиусом ≈ 0.6 × ширины ногтя). */
const nailTop = (n: Nail) => round(-(n.w * 0.6 - 0.3))

/** Белый кончик френча: ~30% высоты у свободного края, нижняя граница — «улыбка». */
function frenchTip(n: Nail) {
  const x = round(-n.w / 2)
  const y = nailTop(n)
  const c = Math.min(1, n.w / 2)
  const edge = round(y + n.h * 0.3)
  const smile = round(y + n.h * 0.18)
  return (
    `M${x} ${edge}V${round(y + c)}Q${x} ${y} ${round(x + c)} ${y}` +
    `H${round(-x - c)}Q${-x} ${y} ${-x} ${round(y + c)}V${edge}Q0 ${smile} ${x} ${edge}Z`
  )
}

/** Световая полоса «кошачьего глаза» — по диагонали ногтя. */
function catEyeStreak(n: Nail) {
  const y = nailTop(n)
  return `M${round(-n.w * 0.05)} ${round(y + n.h * 0.62)}L${round(n.w * 0.3)} ${round(y + n.h * 0.2)}`
}

/** Глянцевый блик у основания ногтя. */
function gloss(n: Nail) {
  const y = nailTop(n)
  return `M${round(-n.w * 0.27)} ${round(y + n.h * 0.55)}V${round(y + n.h - 0.6)}`
}

function Nails() {
  const catEye = NAIL_STYLE === 'cat-eye'
  return (
    <g className={`${styles.nails} ${catEye ? styles.catEye : styles.french}`}>
      {NAILS.map((n, i) => (
        <g key={i} transform={`translate(${n.cx} ${n.cy}) rotate(${n.a})`}>
          <rect
            x={round(-n.w / 2)}
            y={nailTop(n)}
            width={n.w}
            height={n.h}
            rx={round(Math.min(1, n.w * 0.38))}
            fill={catEye ? `url(#${CAT_EYE_GRADIENT_ID})` : '#f6d9d3'}
            stroke={catEye ? '#a52a63' : 'rgba(43,37,40,.25)'}
            strokeWidth={catEye ? '.25' : '.3'}
          />
          {catEye ? (
            <path
              d={catEyeStreak(n)}
              stroke="rgba(255,255,255,.75)"
              strokeWidth=".4"
              strokeLinecap="round"
            />
          ) : (
            <path d={frenchTip(n)} fill="#ffffff" />
          )}
          <path
            d={gloss(n)}
            stroke="#ffffff"
            strokeOpacity=".5"
            strokeWidth=".45"
            strokeLinecap="round"
          />
        </g>
      ))}
    </g>
  )
}

/**
 * Курсор-«рука с маникюром» для мыши/трекпада. Кончик указательного ногтя — хотспот:
 * он в точке (3, 1.4) viewBox, и .hand сдвинут на эти же пиксели.
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
        <svg width="28" height="32" viewBox="0 0 28 32" focusable="false">
          {NAIL_STYLE === 'cat-eye' && (
            <defs>
              <linearGradient id={CAT_EYE_GRADIENT_ID} gradientTransform="rotate(60 .5 .5)">
                <stop offset="0" stopColor="#7a1e4c" />
                <stop offset=".5" stopColor="#d84f8f" />
                <stop offset="1" stopColor="#7a1e4c" />
              </linearGradient>
            </defs>
          )}
          <g transform="translate(3 1.4) rotate(-16) scale(.8)">
            <path
              d={HAND}
              fill="#f3d6c6"
              stroke="rgba(43,37,40,.25)"
              strokeWidth=".6"
              strokeLinejoin="round"
            />
            <path
              d={SHADE}
              fill="none"
              stroke="#e9c3b0"
              strokeOpacity=".5"
              strokeWidth="1.6"
              strokeLinecap="round"
            />
            <Nails />
          </g>
        </svg>
      </div>
    </div>
  )
}
