import { about } from '../../content/about'
import { useTilt } from '../../lib/useTilt'
import styles from './About.module.css'

const { photo } = about

function photoSrc(width: number): string {
  return `/${photo.name}-${width}.webp`
}

export function About() {
  const tiltRef = useTilt<HTMLDivElement>()
  return (
    <section id="about" className="section">
      <div className={`container ${styles.inner}`}>
        <div ref={tiltRef} className={`reveal ${styles.photo}`}>
          <img
            src={photoSrc(960)}
            srcSet={photo.widths.map((w) => `${photoSrc(w)} ${w}w`).join(', ')}
            sizes="(min-width: 1264px) 480px, (min-width: 768px) 40vw, 92vw"
            alt={photo.alt}
            loading="lazy"
            width={960}
            height={1280}
          />
        </div>
        <div className={`reveal ${styles.text}`}>
          <span className="kicker">Кто делает</span>
          <h2>{about.title}</h2>
          {about.paragraphs.map((p) => (
            <p key={p}>{p}</p>
          ))}
        </div>
      </div>
    </section>
  )
}
