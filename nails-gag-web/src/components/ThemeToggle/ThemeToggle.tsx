import { ui } from '../../content/ui'
import { useTheme } from '../../lib/useTheme'
import styles from './ThemeToggle.module.css'

function SunIcon() {
  return (
    <svg
      className={styles.sun}
      width="14"
      height="14"
      viewBox="0 0 14 14"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.5"
      strokeLinecap="round"
      aria-hidden="true"
      focusable="false"
    >
      <circle cx="7" cy="7" r="2.5" />
      <path d="M7 1v1.2M7 11.8V13M1 7h1.2M11.8 7H13M2.76 2.76l.85.85M10.39 10.39l.85.85M2.76 11.24l.85-.85M10.39 3.61l.85-.85" />
    </svg>
  )
}

function MoonIcon() {
  return (
    <svg
      className={styles.moon}
      width="14"
      height="14"
      viewBox="0 0 14 14"
      fill="currentColor"
      aria-hidden="true"
      focusable="false"
    >
      <path d="M12.3 8.8A5.5 5.5 0 0 1 5.2 1.7a5.5 5.5 0 1 0 7.1 7.1Z" />
    </svg>
  )
}

/**
 * Переключатель темы в шапке: пилюля с круглым бегунком (солнце — светлая, луна — тёмная).
 * Положение бегунка и иконку задаёт CSS по :root[data-theme='dark'] — так вид верен уже до
 * гидрации (инлайн-скрипт в index.html ставит атрибут до первой отрисовки); aria-checked
 * следует сохранённому выбору после гидрации.
 */
export function ThemeToggle({ className }: { className?: string }) {
  const { theme, toggle } = useTheme()
  return (
    <button
      type="button"
      role="switch"
      aria-checked={theme === 'dark'}
      aria-label={ui.darkTheme}
      className={className ? `${styles.toggle} ${className}` : styles.toggle}
      onClick={toggle}
    >
      <span className={styles.track} aria-hidden="true">
        <span className={styles.stars} />
        <span className={styles.knob}>
          <SunIcon />
          <MoonIcon />
        </span>
      </span>
    </button>
  )
}
