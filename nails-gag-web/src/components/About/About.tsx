import { about } from '../../content/about'
import { src, srcSet } from '../../content/gallery'
import { useTilt } from '../../lib/useTilt'
import styles from './About.module.css'

export function About() {
  const tiltRef = useTilt<HTMLDivElement>()
  return (
    <section id="about" className="section">
      <div className={`container ${styles.inner}`}>
        <div ref={tiltRef} className={`reveal ${styles.photo}`}>
          <img
            src={src(about.photo.slug, 960)}
            srcSet={srcSet(about.photo.slug)}
            sizes="(min-width: 1264px) 480px, (min-width: 768px) 40vw, 100vw"
            alt={about.photo.alt}
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
          <dl className={styles.facts}>
            {about.facts.map((f) => (
              <div key={f.label}>
                <dt>{f.label}</dt>
                <dd>{f.value}</dd>
              </div>
            ))}
          </dl>
        </div>
      </div>
    </section>
  )
}
