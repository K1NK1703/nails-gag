import { useEffect } from 'react'
import { privacy } from '../content/privacy'
import { titleFor } from '../lib/seo'
import styles from './Page.module.css'
import own from './Privacy.module.css'

const anchor = (id: string) => `p-${id}`

export function Privacy() {
  useEffect(() => {
    document.title = titleFor('/privacy')
  }, [])
  return (
    <main className={`container ${styles.page}`}>
      <h1>{privacy.title}</h1>
      <p className={styles.muted}>
        Версия {privacy.version} · обновлено {privacy.updated}
      </p>
      <nav aria-label="Содержание" className={`glass ${own.toc}`}>
        <p className={own.tocTitle}>Содержание</p>
        <ol className={own.tocList}>
          {privacy.sections.map((s) => (
            <li key={s.id}>
              <a href={`#${anchor(s.id)}`}>{s.title}</a>
            </li>
          ))}
        </ol>
      </nav>
      {privacy.sections.map((s) => (
        <section key={s.id} className={own.section} aria-labelledby={anchor(s.id)}>
          <h2 id={anchor(s.id)}>{s.title}</h2>
          {s.paragraphs.map((p) => (
            <p key={p}>{p}</p>
          ))}
          {s.list && (
            <ul className={own.list}>
              {s.list.map((item) => (
                <li key={item}>{item}</li>
              ))}
            </ul>
          )}
        </section>
      ))}
    </main>
  )
}
