import { useEffect } from 'react'
import { titleFor } from '../lib/seo'
import styles from './Page.module.css'

export function Booking() {
  useEffect(() => {
    document.title = titleFor('/booking')
  }, [])
  return (
    <main className={`container ${styles.page}`}>
      <h1>Онлайн-запись</h1>
      <p className={styles.muted}>
        Здесь можно будет выбрать услугу, дату и время. Раздел появится вместе с личным кабинетом.
      </p>
    </main>
  )
}
