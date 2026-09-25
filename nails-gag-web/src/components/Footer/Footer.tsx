import { TransitionLink } from '../TransitionLink/TransitionLink'
import { privacy } from '../../content/privacy'
import { site } from '../../content/site'
import { terms } from '../../content/terms'
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
          <TransitionLink to="/privacy">{privacy.title}</TransitionLink>
          <TransitionLink to="/terms">{terms.title}</TransitionLink>
        </nav>
      </div>
    </footer>
  )
}
