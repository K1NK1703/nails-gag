import { reviews } from '../../content/reviews'
import styles from './Reviews.module.css'

export function Reviews() {
  return (
    <section id="reviews" className="section">
      <div className="container">
        <span className="kicker">Что говорят</span>
        <h2 className={styles.title}>Отзывы</h2>
        <ul className={styles.list}>
          {reviews.map((r) => (
            <li key={r.id} className={`card reveal ${styles.card}`}>
              <blockquote className={styles.quote}>{r.text}</blockquote>
              <footer className={styles.meta}>
                <span className={styles.name}>{r.name}</span>
                <span className={styles.date}>{r.date}</span>
                {r.isExample && <span className={styles.example}>пример</span>}
              </footer>
            </li>
          ))}
        </ul>
      </div>
    </section>
  )
}
