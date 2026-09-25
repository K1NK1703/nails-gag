import { useEffect } from 'react'
import { TransitionLink } from '../components/TransitionLink/TransitionLink'
import { ui } from '../content/ui'
import { titleFor } from '../lib/seo'
import styles from './Page.module.css'

export function NotFound() {
  useEffect(() => {
    // Путь неизвестен titleFor, поэтому вернётся заголовок 404.
    document.title = titleFor('/404')
  }, [])
  return (
    <main className={`container ${styles.page}`}>
      <h1>{ui.notFound.title}</h1>
      <p className={styles.muted}>{ui.notFound.text}</p>
      <TransitionLink to="/" className="button">
        {ui.notFound.cta}
      </TransitionLink>
    </main>
  )
}
