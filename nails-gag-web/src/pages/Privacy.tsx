import { useEffect } from 'react'
import { privacy } from '../content/privacy'
import { titleFor } from '../lib/seo'
import styles from './Page.module.css'
import own from './Privacy.module.css'

export function Privacy() {
  useEffect(() => {
    document.title = titleFor('/privacy')
  }, [])
  return (
    <main className={`container ${styles.page}`}>
      <h1>{privacy.title}</h1>
      <p className={styles.muted}>Обновлено: {privacy.updated}</p>
      {privacy.sections.map((s) => (
        <section key={s.title} className={own.section}>
          <h2>{s.title}</h2>
          {s.paragraphs.map((p) => (
            <p key={p}>{p}</p>
          ))}
        </section>
      ))}
    </main>
  )
}
