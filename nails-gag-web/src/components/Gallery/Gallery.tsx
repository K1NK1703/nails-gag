import { useRef, useState } from 'react'
import { gallery, src, srcSet } from '../../content/gallery'
import { track } from '../../lib/analytics'
import { Lightbox } from '../Lightbox/Lightbox'
import styles from './Gallery.module.css'

export function Gallery() {
  const [index, setIndex] = useState<number | null>(null)
  const lastButton = useRef<HTMLButtonElement | null>(null)

  const open = (i: number, button: HTMLButtonElement) => {
    lastButton.current = button
    setIndex(i)
    track({ name: 'gallery_open', index: i })
  }

  const close = () => {
    setIndex(null)
    lastButton.current?.focus()
  }

  return (
    <section id="gallery" className="section">
      <div className="container">
        <span className="kicker">{`Портфолио · ${gallery.length} фото`}</span>
        <h2 className={styles.title}>Работы</h2>
        <ul className={styles.grid}>
          {gallery.map((photo, i) => (
            <li key={photo.slug} className="reveal">
              <button
                type="button"
                className={styles.tile}
                data-cursor="photo"
                aria-label={`Открыть фото: ${photo.alt}`}
                onClick={(e) => open(i, e.currentTarget)}
              >
                <img
                  src={src(photo.slug, 480)}
                  srcSet={srcSet(photo.slug)}
                  sizes="(min-width: 1264px) 280px, (min-width: 1024px) 25vw, (min-width: 768px) 33vw, 50vw"
                  alt={photo.alt}
                  loading={i < 2 ? 'eager' : 'lazy'}
                  width={480}
                  height={640}
                />
              </button>
            </li>
          ))}
        </ul>
      </div>
      <Lightbox photos={gallery} index={index} onClose={close} onNavigate={setIndex} />
    </section>
  )
}
