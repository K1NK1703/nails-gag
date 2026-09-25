import { useRef } from 'react'
import type { SyntheticEvent } from 'react'
import { faq } from '../../content/faq'
import type { FaqItem } from '../../content/faq'
import { track } from '../../lib/analytics'
import { useAnimatedDetails } from '../../lib/useAnimatedDetails'
import styles from './Faq.module.css'

function FaqEntry({ item }: { item: FaqItem }) {
  const ref = useRef<HTMLDetailsElement>(null)
  const onSummaryClick = useAnimatedDetails(ref)
  const onToggle = (e: SyntheticEvent<HTMLDetailsElement>) => {
    if (e.currentTarget.open) track({ name: 'faq_open', id: item.id })
  }

  return (
    <details ref={ref} className={`reveal ${styles.item}`} onToggle={onToggle}>
      <summary className={styles.summary} onClick={onSummaryClick}>
        {item.question}
      </summary>
      <p className={styles.answer}>{item.answer}</p>
    </details>
  )
}

export function Faq() {
  return (
    <section id="faq" className="section">
      <div className={`container ${styles.inner}`}>
        <div>
          <span className="kicker">Перед визитом</span>
          <h2>Частые вопросы</h2>
        </div>
        <div className={styles.list}>
          {faq.map((q) => (
            <FaqEntry key={q.id} item={q} />
          ))}
        </div>
      </div>
    </section>
  )
}
