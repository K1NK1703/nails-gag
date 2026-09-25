import type { PrivacySection } from '../../content/privacy'
import page from '../../pages/Page.module.css'
import styles from './LegalDocument.module.css'

export type LegalDocumentProps = {
  title: string
  version: string
  updated: string
  sections: PrivacySection[]
}

const anchor = (id: string) => `p-${id}`

/** Юридический документ: заголовок, версия, оглавление и разделы с якорями. */
export function LegalDocument({ title, version, updated, sections }: LegalDocumentProps) {
  return (
    <main className={`container ${page.page}`}>
      <h1>{title}</h1>
      <p className={page.muted}>
        Версия {version} · обновлено {updated}
      </p>
      <nav aria-label="Содержание" className={`glass ${styles.toc}`}>
        <p className={styles.tocTitle}>Содержание</p>
        <ol className={styles.tocList}>
          {sections.map((s) => (
            <li key={s.id}>
              <a href={`#${anchor(s.id)}`}>{s.title}</a>
            </li>
          ))}
        </ol>
      </nav>
      {sections.map((s) => (
        <section key={s.id} className={styles.section} aria-labelledby={anchor(s.id)}>
          <h2 id={anchor(s.id)}>{s.title}</h2>
          {s.paragraphs.map((p) => (
            <p key={p}>{p}</p>
          ))}
          {s.list && (
            <ul className={styles.list}>
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
