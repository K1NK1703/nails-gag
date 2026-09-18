import { useEffect } from 'react'
import { titleFor } from '../lib/seo'
import styles from './Page.module.css'

export function Cabinet() {
  useEffect(() => {
    document.title = titleFor('/cabinet')
  }, [])
  return (
    <main className={`container ${styles.page}`}>
      <h1>Личный кабинет</h1>
      <p className={styles.muted}>
        Скоро здесь можно будет смотреть свои записи, переносить их и выбирать дизайн заранее.
      </p>
    </main>
  )
}
