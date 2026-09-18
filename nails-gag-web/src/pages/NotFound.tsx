import { useEffect } from 'react'
import { Link } from 'react-router'
import styles from './Page.module.css'

export function NotFound() {
  useEffect(() => {
    document.title = 'Страница не найдена — nails.gag'
  }, [])
  return (
    <main className={`container ${styles.page}`}>
      <h1>Страница не найдена</h1>
      <p className={styles.muted}>Такой страницы нет. Вернитесь на главную.</p>
      <Link to="/" className="button">
        На главную
      </Link>
    </main>
  )
}
