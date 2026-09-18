import { site } from '../../content/site'
import { track } from '../../lib/analytics'
import styles from './Hero.module.css'

export function Hero() {
  return (
    <section id="hero" className={styles.hero}>
      <div className={`container ${styles.inner}`}>
        <div className={styles.text}>
          <span className="kicker">{site.kicker}</span>
          <h1>
            {site.taglineLines[0]} <br />
            {site.taglineLines[1]}
            <span className={styles.accent}>.</span>
          </h1>
          <p className={styles.subtitle}>{site.subtitle}</p>
          <p className={styles.pill}>{site.heroPricePill}</p>
          <a
            href={site.bookingUrl}
            target="_blank"
            rel="noopener noreferrer"
            className="button button--accent"
            onClick={() => track({ name: 'cta_click', place: 'hero' })}
          >
            Записаться <span aria-hidden="true">→</span>
          </a>
        </div>
        <div className={styles.photo}>
          <img
            src="/hero-720.webp"
            srcSet="/hero-360.webp 360w, /hero-720.webp 720w, /hero-1440.webp 879w"
            sizes="(min-width: 768px) 240px, 180px"
            alt={`${site.masterName}, мастер маникюра`}
            width={879}
            height={3181}
            fetchPriority="high"
          />
        </div>
        <span className={styles.signature}>{site.heroSignature}</span>
      </div>
    </section>
  )
}
