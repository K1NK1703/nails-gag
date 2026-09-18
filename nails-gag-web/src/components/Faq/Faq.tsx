import type { SyntheticEvent } from 'react'
import { faq } from '../../content/faq'
import { track } from '../../lib/analytics'
import styles from './Faq.module.css'

export function Faq() {
  const onToggle = (id: string) => (e: SyntheticEvent<HTMLDetailsElement>) => {
    if (e.currentTarget.open) track({ name: 'faq_open', id })
  }

  return (
    <section id="faq" className="section">
      <div className={`container ${styles.inner}`}>
        <div>
          <span className="kicker">Перед визитом</span>
          <h2>Частые вопросы</h2>
        </div>
        <div className={styles.list}>
          {faq.map((q) => (
            <details key={q.id} className={`reveal ${styles.item}`} onToggle={onToggle(q.id)}>
              <summary className={styles.summary}>{q.question}</summary>
              <p className={styles.answer}>{q.answer}</p>
            </details>
          ))}
        </div>
      </div>
    </section>
  )
}
