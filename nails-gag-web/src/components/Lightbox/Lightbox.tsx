import { useEffect, useRef } from 'react'
import { createPortal } from 'react-dom'
import { src, type Photo } from '../../content/gallery'
import styles from './Lightbox.module.css'

type Props = {
  photos: Photo[]
  index: number | null
  onClose: () => void
  onNavigate: (index: number) => void
}

export function Lightbox({ photos, index, onClose, onNavigate }: Props) {
  const closeRef = useRef<HTMLButtonElement>(null)
  const touchX = useRef<number | null>(null)
  const count = photos.length
  const isOpen = index !== null

  useEffect(() => {
    if (index === null) return
    const next = () => onNavigate((index + 1) % count)
    const prev = () => onNavigate((index - 1 + count) % count)
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
      else if (e.key === 'ArrowRight') next()
      else if (e.key === 'ArrowLeft') prev()
    }
    window.addEventListener('keydown', onKey)
    const prevOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    closeRef.current?.focus()
    return () => {
      window.removeEventListener('keydown', onKey)
      document.body.style.overflow = prevOverflow
    }
  }, [index, count, onClose, onNavigate])

  if (!isOpen) return null
  const photo = photos[index]
  const next = () => onNavigate((index + 1) % count)
  const prev = () => onNavigate((index - 1 + count) % count)

  return createPortal(
    <div
      className={styles.backdrop}
      role="presentation"
      onClick={onClose}
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
      <dialog open className={styles.dialog} aria-modal="true" aria-label={photo.alt}>
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
        <figure className={styles.figure} role="presentation" onClick={(e) => e.stopPropagation()}>
          <img src={src(photo.slug, 1600)} alt={photo.alt} />
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
