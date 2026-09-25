import { useEffect, useRef } from 'react'
import { createPortal } from 'react-dom'
import { src, srcSet, type Photo } from '../../content/gallery'
import styles from './Lightbox.module.css'

type Props = {
  photos: Photo[]
  index: number | null
  onClose: () => void
  onNavigate: (index: number) => void
}

export function Lightbox({ photos, index, onClose, onNavigate }: Props) {
  const closeRef = useRef<HTMLButtonElement>(null)
  const dialogRef = useRef<HTMLDialogElement>(null)
  const figureRef = useRef<HTMLElement>(null)
  const touchX = useRef<number | null>(null)
  const count = photos.length
  const isOpen = index !== null

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
    const next = () => onNavigate((index + 1) % count)
    const prev = () => onNavigate((index - 1 + count) % count)
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
      else if (e.key === 'ArrowRight') next()
      else if (e.key === 'ArrowLeft') prev()
      else if (e.key === 'Tab') trapTab(e)
    }
    window.addEventListener('keydown', onKey)
    return () => {
      window.removeEventListener('keydown', onKey)
    }
  }, [index, count, onClose, onNavigate])

  if (!isOpen || typeof document === 'undefined') return null
  const photo = photos[index]
  const next = () => onNavigate((index + 1) % count)
  const prev = () => onNavigate((index - 1 + count) % count)

  return createPortal(
    <div
      className={styles.backdrop}
      data-cursor="photo"
      role="presentation"
      onClick={(e) => {
        if (!figureRef.current?.contains(e.target as Node)) onClose()
      }}
      onTouchStart={(e) => {
        touchX.current = e.touches[0].clientX
      }}
      onTouchEnd={(e) => {
        if (touchX.current === null) return
        const dx = e.changedTouches[0].clientX - touchX.current
        touchX.current = null
        if (dx > 40) prev()
        else if (dx < -40) next()
      }}
    >
      <dialog
        ref={dialogRef}
        open
        className={styles.dialog}
        aria-modal="true"
        aria-label={photo.alt}
      >
        <button
          ref={closeRef}
          type="button"
          className={styles.close}
          aria-label="Закрыть"
          onClick={(e) => {
            e.stopPropagation()
            onClose()
          }}
        >
          ×
        </button>
        <button
          type="button"
          className={`${styles.arrow} ${styles.arrowPrev}`}
          aria-label="Предыдущее фото"
          onClick={(e) => {
            e.stopPropagation()
            prev()
          }}
        >
          ←
        </button>
        <figure ref={figureRef} className={styles.figure}>
          <img
            src={src(photo.slug, 1600)}
            srcSet={srcSet(photo.slug)}
            sizes="100vw"
            alt={photo.alt}
          />
          <figcaption>
            {index + 1} / {count}
            {photo.caption ? ` · ${photo.caption}` : ''}
          </figcaption>
        </figure>
        <button
          type="button"
          className={`${styles.arrow} ${styles.arrowNext}`}
          aria-label="Следующее фото"
          onClick={(e) => {
            e.stopPropagation()
            next()
          }}
        >
          →
        </button>
      </dialog>
    </div>,
    document.body,
  )
}
