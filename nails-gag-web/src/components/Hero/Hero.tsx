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
          <a
            href={site.bookingUrl}
            target="_blank"
            rel="noopener noreferrer"
            className="button button--accent"
            onClick={() => track({ name: 'cta_click', place: 'hero' })}
          >
            Записаться <span aria-hidden="true">→</span>
          </a>
          <aside className={styles.masterCard}>
            <span className={styles.portrait}>
              <img
                src="/portrait-240.webp"
                srcSet="/portrait-240.webp 240w, /portrait-480.webp 480w"
                sizes="88px"
                width={240}
                height={276}
                alt={`${site.masterName}, мастер маникюра`}
                loading="lazy"
              />
            </span>
            <span className={styles.masterText}>
              <strong className={styles.masterName}>{site.masterName}</strong>
              <span className={styles.masterMeta}>мастер · с {site.foundedYear}</span>
              <span className={styles.masterAddress}>{site.address.street}</span>
            </span>
          </aside>
        </div>
        <div className={styles.figure}>
          <img
            src="/hero-720.webp"
            srcSet="/hero-480.webp 480w, /hero-720.webp 720w, /hero-960.webp 879w"
            sizes="(min-width: 768px) 360px, 100px"
            alt={`${site.masterName}, мастер маникюра`}
            width={879}
            height={2163}
            fetchPriority="high"
          />
        </div>
        <span className={styles.signature}>{site.heroSignature}</span>
      </div>
    </section>
  )
}
