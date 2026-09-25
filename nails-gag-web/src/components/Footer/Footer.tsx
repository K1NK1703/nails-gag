import { TransitionLink } from '../TransitionLink/TransitionLink'
import { site } from '../../content/site'
import styles from './Footer.module.css'

export function Footer() {
  return (
    <footer className={styles.footer}>
      <div className={`container ${styles.inner}`}>
        <div className={styles.legal}>
          <span>
            © {site.foundedYear}–{__BUILD_YEAR__} {site.name}
          </span>
          <span className={styles.performer}>{site.performerLine}</span>
        </div>
        <nav className={styles.links} aria-label="Подвал">
          <TransitionLink to="/privacy">Политика обработки персональных данных</TransitionLink>
          <TransitionLink to="/terms">Пользовательское соглашение</TransitionLink>
        </nav>
      </div>
    </footer>
  )
}
