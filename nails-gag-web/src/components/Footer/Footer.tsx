import { Link } from 'react-router'
import { site } from '../../content/site'
import { track } from '../../lib/analytics'
import styles from './Footer.module.css'

export function Footer() {
  return (
    <footer className={styles.footer}>
      <div className={`container ${styles.inner}`}>
        <span>
          © {site.foundedYear}–{__BUILD_YEAR__} {site.name}
        </span>
        <nav className={styles.links} aria-label="Подвал">
          <Link to="/privacy">Политика конфиденциальности</Link>
          <a
            href={site.bookingUrl}
            target="_blank"
            rel="noopener noreferrer"
            onClick={() => track({ name: 'cta_click', place: 'footer' })}
          >
            Telegram
          </a>
        </nav>
      </div>
    </footer>
  )
}
