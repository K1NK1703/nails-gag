import { Link } from 'react-router'
import { prices } from '../../content/prices'
import { site } from '../../content/site'
import { track } from '../../lib/analytics'
import styles from './Price.module.css'

export function Price() {
  return (
    <section id="price" className="section">
      <div className="container">
        <span className="kicker">{site.priceKicker}</span>
        <h2 className={styles.title}>Прайс</h2>
        <ul className={styles.grid}>
          {prices.map((p) => (
            <li
              key={p.id}
              className={['card', 'reveal', styles.card, p.primary && styles.primary]
                .filter(Boolean)
                .join(' ')}
            >
              <span className="label">{p.category}</span>
              <h3>{p.title}</h3>
              <p className={styles.price}>{p.price}</p>
              <p className={styles.description}>{p.description}</p>
            </li>
          ))}
        </ul>
        <p className={styles.note}>
          <Link
            to={site.bookingPath}
            className={styles.link}
            onClick={() => track({ name: 'cta_click', place: 'price' })}
          >
            {site.priceNote.linkText}
          </Link>
          {site.priceNote.rest}
        </p>
      </div>
    </section>
  )
}
