import { TransitionLink } from '../TransitionLink/TransitionLink'
import { site } from '../../content/site'
import { track } from '../../lib/analytics'
import styles from './Hero.module.css'

/** Прозрачный GIF 1×1: фолбэк <picture>, когда desktop-источник не подходит. */
const BLANK_PIXEL = 'data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7'

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
          <TransitionLink
            to={site.bookingPath}
            className="button button--accent"
            onClick={() => track({ name: 'cta_click', place: 'hero' })}
          >
            Записаться <span aria-hidden="true">→</span>
          </TransitionLink>
          <a href="#about" className={styles.masterCard}>
            <span className={styles.portrait}>
              <img
                src="/portrait-240.webp"
                srcSet="/portrait-240.webp 240w, /portrait-480.webp 480w"
                sizes="88px"
                width={240}
                height={276}
                alt=""
                loading="eager"
                fetchPriority="high"
              />
            </span>
            <span className={styles.masterText}>
              <strong className={styles.masterName}>{site.masterName}</strong>{' '}
              <span className={styles.masterMeta}>мастер маникюра</span>
            </span>
            <span className={styles.chevron} aria-hidden="true">
              →
            </span>
          </a>
        </div>
        <div className={styles.figure}>
          {/* Фигура видна только с 768px: на телефонах срабатывает прозрачная заглушка
              и фото не скачивается вовсе. */}
          <picture>
            <source
              media="(min-width: 768px)"
              srcSet="/hero-480.webp 480w, /hero-720.webp 720w, /hero-960.webp 879w"
              sizes="360px"
            />
            <img
              src={BLANK_PIXEL}
              alt={`${site.masterName}, мастер маникюра`}
              width={879}
              height={2163}
              fetchPriority="high"
            />
          </picture>
        </div>
        <span className={styles.signature}>{site.heroSignature}</span>
      </div>
    </section>
  )
}
