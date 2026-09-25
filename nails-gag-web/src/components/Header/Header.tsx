import { useEffect, useRef, useState } from 'react'
import type { CSSProperties } from 'react'
import { site } from '../../content/site'
import { track } from '../../lib/analytics'
import { useAuth } from '../../lib/auth'
import type { User } from '../../lib/auth'
import { isCabinetEnabled } from '../../lib/features'
import { TransitionLink } from '../TransitionLink/TransitionLink'
import styles from './Header.module.css'

const links = [
  { href: '/#gallery', label: 'Галерея' },
  { href: '/#price', label: 'Прайс' },
  { href: '/#contacts', label: 'Контакты' },
]

function UserIcon() {
  return (
    <svg
      className={styles.icon}
      width="16"
      height="16"
      viewBox="0 0 16 16"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinecap="round"
      aria-hidden="true"
      focusable="false"
    >
      <circle cx="8" cy="5.5" r="2.75" />
      <path d="M2.75 14c.6-2.6 2.7-4.1 5.25-4.1s4.65 1.5 5.25 4.1" />
    </svg>
  )
}

function Avatar({ user }: { user: User }) {
  if (user.avatarUrl) {
    return <img className={styles.avatar} src={user.avatarUrl} alt="" width={24} height={24} />
  }
  return (
    <span className={styles.avatar} aria-hidden="true">
      {user.name.trim().charAt(0).toUpperCase()}
    </span>
  )
}

function firstName(name: string): string {
  return name.trim().split(/\s+/)[0] ?? name
}

export function Header() {
  const [open, setOpen] = useState(false)
  const cabinet = isCabinetEnabled()
  const { user } = useAuth()

  const headerRef = useRef<HTMLElement>(null)
  const burgerRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    if (!open) return
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return
      setOpen(false)
      // Фокус мог остаться в закрытом меню — возвращаем его на кнопку, которая меню открыла.
      burgerRef.current?.focus()
    }
    const onPointerDown = (e: PointerEvent) => {
      if (e.target instanceof Node && headerRef.current?.contains(e.target)) return
      setOpen(false)
    }
    window.addEventListener('keydown', onKeyDown)
    document.addEventListener('pointerdown', onPointerDown)
    return () => {
      window.removeEventListener('keydown', onKeyDown)
      document.removeEventListener('pointerdown', onPointerDown)
    }
  }, [open])

  return (
    <header ref={headerRef} className={styles.header}>
      <div className={`container ${styles.inner}`}>
        <a href="/" className={styles.logo} aria-label="nails.gag, на главную">
          <span className={styles.dot} aria-hidden="true" />
          nails.gag
        </a>

        <button
          ref={burgerRef}
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
          {links.map((l, i) => (
            <a
              key={l.href}
              href={l.href}
              style={{ '--i': i } as CSSProperties}
              onClick={() => setOpen(false)}
            >
              {l.label}
            </a>
          ))}
          {cabinet && (
            <>
              <span className={styles.divider} aria-hidden="true" />
              <TransitionLink
                to={site.cabinetPath}
                className={styles.menuAccount}
                style={{ '--i': links.length } as CSSProperties}
                onClick={() => setOpen(false)}
              >
                {user ? <Avatar user={user} /> : <UserIcon />}
                {user ? site.cabinetLabel : site.loginLabel}
              </TransitionLink>
            </>
          )}
        </nav>

        {cabinet && (
          <TransitionLink
            to={site.cabinetPath}
            className={styles.account}
            data-user={user ? 'true' : 'false'}
            title={user ? site.cabinetLabel : undefined}
          >
            {user ? <Avatar user={user} /> : <UserIcon />}
            <span className={styles.accountLabel}>
              {user ? firstName(user.name) : site.loginLabel}
            </span>
          </TransitionLink>
        )}

        <TransitionLink
          to={site.bookingPath}
          className={`button ${styles.cta}`}
          onClick={() => track({ name: 'cta_click', place: 'header' })}
        >
          Записаться <span aria-hidden="true">→</span>
        </TransitionLink>
      </div>
    </header>
  )
}
