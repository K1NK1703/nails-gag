import { useEffect, useState } from 'react'
import type { CSSProperties } from 'react'
import { Link } from 'react-router'
import { site } from '../../content/site'
import { track } from '../../lib/analytics'
import { isCabinetEnabled } from '../../lib/features'
import styles from './Header.module.css'

const links = [
  { href: '/#gallery', label: 'Галерея' },
  { href: '/#price', label: 'Прайс' },
  { href: '/#contacts', label: 'Контакты' },
]

export function Header() {
  const [open, setOpen] = useState(false)
  const cabinet = isCabinetEnabled()
  const navItems = cabinet ? [...links, { href: '/cabinet', label: 'Личный кабинет' }] : links

  useEffect(() => {
    if (!open) return
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [open])

  return (
    <header className={styles.header}>
      <div className={`container ${styles.inner}`}>
        <a href="/" className={styles.logo} aria-label="nails.gag, на главную">
          <span className={styles.dot} aria-hidden="true" />
          nails.gag
        </a>

        <button
          type="button"
          className={styles.burger}
          data-open={open ? 'true' : 'false'}
          aria-label="Меню"
          aria-expanded={open}
          aria-controls="site-nav"
          onClick={() => setOpen((v) => !v)}
        >
          <span aria-hidden="true" />
        </button>

        <nav
          id="site-nav"
          aria-label="Основное меню"
          className={styles.nav}
          data-open={open ? 'true' : 'false'}
        >
          {navItems.map((l, i) => (
            <a
              key={l.href}
              href={l.href}
              style={{ '--i': i } as CSSProperties}
              onClick={() => setOpen(false)}
            >
              {l.label}
            </a>
          ))}
        </nav>

        <Link
          to={site.bookingPath}
          className={`button ${styles.cta}`}
          onClick={() => track({ name: 'cta_click', place: 'header' })}
        >
          Записаться <span aria-hidden="true">→</span>
        </Link>
      </div>
    </header>
  )
}
