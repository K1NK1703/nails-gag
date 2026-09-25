import { TransitionLink } from '../TransitionLink/TransitionLink'
import { site } from '../../content/site'
import styles from './Footer.module.css'

export function Footer() {
  return (
    <footer className={styles.footer}>
      <div className={`container ${styles.inner}`}>
        <span>
          © {site.foundedYear}–{__BUILD_YEAR__} {site.name}
        </span>
        <nav className={styles.links} aria-label="Подвал">
          <TransitionLink to="/privacy">Политика конфиденциальности</TransitionLink>
        </nav>
      </div>
    </footer>
  )
}
