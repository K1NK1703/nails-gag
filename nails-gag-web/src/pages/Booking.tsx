import { useEffect } from 'react'
import { booking } from '../content/booking'
import { titleFor } from '../lib/seo'
import styles from './Page.module.css'

export function Booking() {
  useEffect(() => {
    document.title = titleFor('/booking')
  }, [])
  return (
    <main className={`container ${styles.page}`}>
      <h1>{booking.title}</h1>
      <p className={styles.muted}>{booking.text}</p>
    </main>
  )
}
