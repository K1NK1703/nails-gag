import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import styles from './Lightbox.module.css'

/** Фото для просмотра: адреса вариантов собирает тот, кто открывает лайтбокс. */
export type LightboxPhoto = {
  id: string
  alt: string
  caption?: string | null
  /** Самый крупный вариант — для браузеров без srcset. */
  src: string
  srcSet: string
  /** Размеры фото: по ним браузер держит место под картинку, пока она грузится. */
  width: number
  height: number
}

type Props = {
  photos: LightboxPhoto[]
  index: number | null
  onClose: () => void
  onNavigate: (index: number) => void
}

/** Линейные иконки в стиле шапки: 1.5px, скруглённые концы, цвет — currentColor. */
function Icon({ d, size }: { d: string; size: number }) {
  return (
    <svg
      className={styles.icon}
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.5"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      <path d={d} />
    </svg>
  )
}

const CLOSE = 'M6 6l12 12M18 6 6 18'
const PREV = 'M15 4.5 7.5 12l7.5 7.5'
const NEXT = 'M9 4.5l7.5 7.5L9 19.5'

/** Сколько миллисекунд тишины в scroll считать концом прокрутки, если нет события scrollend. */
const SETTLE_MS = 140

function prefersReducedMotion() {
  return (
    typeof window.matchMedia === 'function' &&
    window.matchMedia('(prefers-reduced-motion: reduce)').matches
  )
}

/**
 * Полноэкранный просмотр фото. Слайды — горизонтальная лента со scroll-snap: на телефоне
 * свайп нативный (фото идёт за пальцем, с инерцией и доводкой), на десктопе — стрелки по
 * краям и клавиши. Текущий слайд определяется по положению ленты после остановки прокрутки;
 * смена index снаружи (клавиши, стрелки, открытие) прокручивает ленту к нужному слайду.
 */
export function Lightbox({ photos, index, onClose, onNavigate }: Props) {
  const closeRef = useRef<HTMLButtonElement>(null)
  const dialogRef = useRef<HTMLDialogElement>(null)
  const trackRef = useRef<HTMLDivElement>(null)
  const count = photos.length
  const isOpen = index !== null

  // Слайд под пальцем во время свайпа — индикатор и подпись меняются вместе с фото, не
  // дожидаясь конца инерции. Сбрасывается, когда index догоняет ленту.
  const [scrolled, setScrolled] = useState<number | null>(null)
  const [prevIndex, setPrevIndex] = useState(index)
  if (prevIndex !== index) {
    setPrevIndex(index)
    setScrolled(null)
  }

  const indexRef = useRef(index)
  const navigateRef = useRef(onNavigate)
  /** Идёт программная прокрутка к index — «живой» слайд не трогаем, чтобы индикатор не дёргался. */
  const autoScrollRef = useRef(false)
  useEffect(() => {
    indexRef.current = index
    navigateRef.current = onNavigate
  })

  useEffect(() => {
    if (!isOpen) return
    const prevOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    closeRef.current?.focus()
    return () => {
      document.body.style.overflow = prevOverflow
    }
  }, [isOpen])

  useEffect(() => {
    if (index === null) return
    const trapTab = (e: KeyboardEvent) => {
      const buttons = dialogRef.current?.querySelectorAll('button')
      if (!buttons || buttons.length === 0) return
      const first = buttons[0]
      const last = buttons[buttons.length - 1]
      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault()
        last.focus()
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault()
        first.focus()
      }
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
      else if (e.key === 'ArrowRight') onNavigate((index + 1) % count)
      else if (e.key === 'ArrowLeft') onNavigate((index - 1 + count) % count)
      else if (e.key === 'Tab') trapTab(e)
    }
    window.addEventListener('keydown', onKey)
    return () => {
      window.removeEventListener('keydown', onKey)
    }
  }, [index, count, onClose, onNavigate])

  // index → лента. При открытии — мгновенно; соседний слайд — плавно; прыжок через край
  // (последнее → первое) — мгновенно, чтобы не пролистывать всю ленту.
  const openedRef = useRef(false)
  useLayoutEffect(() => {
    const track = trackRef.current
    if (index === null || !track) {
      openedRef.current = false
      return
    }
    const first = !openedRef.current
    openedRef.current = true
    const width = track.clientWidth
    if (width === 0) return
    const distance = Math.abs(track.scrollLeft / width - index)
    if (distance < 0.01) return
    const smooth = !first && distance <= 1.01 && !prefersReducedMotion()
    autoScrollRef.current = smooth
    track.scrollTo({ left: index * width, behavior: smooth ? 'smooth' : 'instant' })
  }, [index])

  // лента → index. Во время прокрутки обновляем только «живой» слайд, onNavigate — после
  // остановки. Если лента встала на слайд, равный index, ничего не вызываем, поэтому
  // программная прокрутка не порождает обратной навигации.
  useEffect(() => {
    const track = trackRef.current
    if (!isOpen || !track) return
    let raf = 0
    let timer: ReturnType<typeof setTimeout> | undefined
    const nearest = () => {
      const width = track.clientWidth
      if (width === 0) return null
      return Math.min(count - 1, Math.max(0, Math.round(track.scrollLeft / width)))
    }
    const settle = () => {
      autoScrollRef.current = false
      const i = nearest()
      if (i !== null && i !== indexRef.current) navigateRef.current(i)
    }
    const hasScrollEnd = 'onscrollend' in window
    const onScroll = () => {
      if (!autoScrollRef.current) {
        cancelAnimationFrame(raf)
        raf = requestAnimationFrame(() => setScrolled(nearest()))
      }
      if (!hasScrollEnd) {
        clearTimeout(timer)
        timer = setTimeout(settle, SETTLE_MS)
      }
    }
    // Палец на ленте — значит, прокрутку ведёт человек, а не мы.
    const onTouch = () => {
      autoScrollRef.current = false
    }
    track.addEventListener('scroll', onScroll, { passive: true })
    track.addEventListener('touchstart', onTouch, { passive: true })
    if (hasScrollEnd) track.addEventListener('scrollend', settle)
    return () => {
      cancelAnimationFrame(raf)
      clearTimeout(timer)
      track.removeEventListener('scroll', onScroll)
      track.removeEventListener('touchstart', onTouch)
      track.removeEventListener('scrollend', settle)
    }
  }, [isOpen, count])

  if (!isOpen || typeof document === 'undefined') return null
  const shown = scrolled ?? index
  const photo = photos[shown]
  const next = () => onNavigate((index + 1) % count)
  const prev = () => onNavigate((index - 1 + count) % count)

  return createPortal(
    <div
      className={styles.backdrop}
      data-cursor="photo"
      role="presentation"
      onClick={(e) => {
        // Закрывает клик мимо фото: по тёмному полю вокруг него, по подписи, по фону.
        if (e.target instanceof Element && e.target.closest('img, button')) return
        onClose()
      }}
    >
      <dialog
        ref={dialogRef}
        open
        className={styles.dialog}
        aria-modal="true"
        aria-label={photos[index].alt}
      >
        <div ref={trackRef} className={styles.track}>
          {photos.map((p, i) => (
            <div key={p.id} className={styles.slide} aria-hidden={i === index ? undefined : true}>
              {/* настоящие картинки — только у текущего и соседей, остальные слайды пустые */}
              {Math.abs(i - index) <= 1 && (
                <img
                  src={p.src}
                  srcSet={p.srcSet}
                  sizes="(min-aspect-ratio: 3/4) 75vh, 100vw"
                  width={p.width}
                  height={p.height}
                  alt={p.alt}
                  decoding="async"
                  loading={i === index ? 'eager' : 'lazy'}
                  draggable={false}
                />
              )}
            </div>
          ))}
        </div>

        <button
          ref={closeRef}
          type="button"
          className={styles.close}
          aria-label="Закрыть"
          onClick={onClose}
        >
          <Icon d={CLOSE} size={22} />
        </button>
        <button
          type="button"
          className={`${styles.arrow} ${styles.arrowPrev}`}
          aria-label="Предыдущее фото"
          onClick={prev}
        >
          <Icon d={PREV} size={28} />
        </button>
        <button
          type="button"
          className={`${styles.arrow} ${styles.arrowNext}`}
          aria-label="Следующее фото"
          onClick={next}
        >
          <Icon d={NEXT} size={28} />
        </button>

        <div className={styles.info} aria-hidden="true">
          <p key={shown} className={styles.caption}>
            {photo.caption}
          </p>
          <ol className={styles.dots} data-testid="lightbox-dots">
            {photos.map((p, i) => (
              <li key={p.id} className={styles.dot} data-active={i === shown || undefined} />
            ))}
          </ol>
        </div>
        <p className={styles.srOnly} aria-live="polite" aria-atomic="true">
          {shown + 1} из {count}
          {photo.caption ? ` · ${photo.caption}` : ''}
        </p>
      </dialog>
    </div>,
    document.body,
  )
}
