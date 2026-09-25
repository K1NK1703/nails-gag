import { useEffect } from 'react'
import { TransitionLink } from '../components/TransitionLink/TransitionLink'
import { cabinet } from '../content/cabinet'
import { site } from '../content/site'
import { titleFor } from '../lib/seo'
import styles from './Page.module.css'

export function Cabinet() {
  useEffect(() => {
    document.title = titleFor('/cabinet')
  }, [])
  return (
    <main className={`container ${styles.page}`}>
      <h1>{cabinet.title}</h1>
      <p className={styles.muted}>{cabinet.text}</p>
      <TransitionLink to={site.bookingPath} className="button button--accent">
        {cabinet.cta} <span aria-hidden="true">→</span>
      </TransitionLink>
    </main>
  )
}
