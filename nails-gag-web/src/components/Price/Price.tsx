import { prices } from '../../content/prices'
import { site } from '../../content/site'
import { track } from '../../lib/analytics'
import styles from './Price.module.css'

export function Price() {
  return (
    <section id="price" className="section">
      <div className="container">
        <span className="kicker">Стоимость</span>
        <h2>Прайс</h2>
        <p className={styles.lead}>Прозрачные цены без скрытых доплат.</p>
        <ul className={styles.grid}>
          {prices.map((p) => (
            <li
              key={p.id}
              className={`card reveal ${styles.card} ${p.primary ? styles.primary : ''}`}
            >
              <span className={styles.category}>{p.category}</span>
              <h3>{p.title}</h3>
              <p className={styles.price}>{p.price}</p>
              <p className={styles.description}>{p.description}</p>
              <a
                href={site.bookingUrl}
                target="_blank"
                rel="noopener noreferrer"
                className={`button ${p.primary ? 'button--accent' : ''}`}
                onClick={() => track({ name: 'cta_click', place: 'price' })}
              >
                Записаться <span aria-hidden="true">→</span>
              </a>
            </li>
          ))}
        </ul>
      </div>
    </section>
  )
}
