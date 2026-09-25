import { site } from '../../content/site'
import { track } from '../../lib/analytics'
import styles from './Contacts.module.css'

export function Contacts() {
  return (
    <section id="contacts" className="section">
      <div className="container">
        <span className="kicker">{site.contactsKicker}</span>
        <h2 className={styles.title}>
          Найти
          <br />
          <span className={styles.accent}>и записаться</span>
        </h2>
        <ul className={styles.grid}>
          <li className="card reveal">
            <span className="label">Telegram</span>
            <a
              href={site.telegramUrl}
              target="_blank"
              rel="noopener noreferrer"
              className={styles.value}
              onClick={() => track({ name: 'contact_click', channel: 'telegram' })}
            >
              {site.telegramHandle}
            </a>
            <span className={styles.hint}>{site.telegramHint}</span>
          </li>
          <li className="card reveal">
            <span className="label">Канал</span>
            <a
              href={site.channelUrl}
              target="_blank"
              rel="noopener noreferrer"
              className={styles.value}
              onClick={() => track({ name: 'contact_click', channel: 'channel' })}
            >
              {site.channelHandle}
            </a>
            <span className={styles.hint}>{site.channelHint}</span>
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
