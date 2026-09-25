import { useEffect } from 'react'
import { Link } from 'react-router'
import { titleFor } from '../lib/seo'
import styles from './Page.module.css'

export function NotFound() {
  useEffect(() => {
    // Путь неизвестен titleFor, поэтому вернётся заголовок 404.
    document.title = titleFor('/404')
  }, [])
  return (
    <main className={`container ${styles.page}`}>
      <h1>Страница не найдена</h1>
      <p className={styles.muted}>Такой страницы нет. Вернитесь на главную.</p>
      <Link to="/" viewTransition className="button">
        На главную
      </Link>
    </main>
  )
}
