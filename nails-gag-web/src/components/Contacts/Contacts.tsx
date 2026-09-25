import { site } from '../../content/site'
import { track } from '../../lib/analytics'
import styles from './Contacts.module.css'

export function Contacts() {
  return (
    <section id="contacts" className="section">
      <div className="container">
        <span className="kicker">Контакты</span>
        <h2>
          Найти
          <br />
          <span className={styles.accent}>и записаться</span>
        </h2>
        <p className={styles.lead}>Два способа дойти до маникюра. Самый быстрый — Telegram.</p>
        <ul className={styles.grid}>
          <li className="card reveal">
            <span className="label">Telegram</span>
            <a
              href={site.bookingUrl}
              target="_blank"
              rel="noopener noreferrer"
              className={styles.value}
              onClick={() => track({ name: 'cta_click', place: 'contacts' })}
            >
              {site.bookingHandle}
            </a>
            <span className={styles.hint}>Запись и общение — здесь</span>
          </li>
          <li className="card reveal">
            <span className="label">Канал</span>
            <a
              href={site.channelUrl}
              target="_blank"
              rel="noopener noreferrer"
              className={styles.value}
            >
              {site.channelHandle}
            </a>
            <span className={styles.hint}>Свежие работы и настроение</span>
          </li>
          <li className="card reveal">
            <span className="label">Адрес</span>
            <a
              href={site.mapsUrl}
              target="_blank"
              rel="noopener noreferrer"
              className={styles.value}
              aria-label={`${site.address.street} — открыть в Яндекс.Картах`}
              onClick={() => track({ name: 'map_open' })}
            >
              {site.address.street}
            </a>
            <span className={styles.hint}>{site.address.details}</span>
          </li>
        </ul>
      </div>
    </section>
  )
}
