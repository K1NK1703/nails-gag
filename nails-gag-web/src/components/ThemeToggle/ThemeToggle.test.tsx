import { fireEvent, render, screen } from '@testing-library/react'
import { renderToString } from 'react-dom/server'
import { afterEach, describe, expect, it } from 'vitest'
import { THEME_KEY } from '../../lib/prefs'
import { ThemeToggle } from './ThemeToggle'

afterEach(() => {
  localStorage.clear()
  document.documentElement.removeAttribute('data-theme')
})

describe('ThemeToggle', () => {
  it('рендерит переключатель «Тёмная тема», по умолчанию выключен', () => {
    render(<ThemeToggle />)
    const toggle = screen.getByRole('switch', { name: 'Тёмная тема' })
    expect(toggle).toHaveAttribute('type', 'button')
    expect(toggle).toHaveAttribute('aria-checked', 'false')
    expect(document.documentElement).not.toHaveAttribute('data-theme')
  })

  it('клик включает тёмную: data-theme на <html>, localStorage, aria-checked', () => {
    render(<ThemeToggle />)
    const toggle = screen.getByRole('switch', { name: 'Тёмная тема' })
    fireEvent.click(toggle)
    expect(toggle).toHaveAttribute('aria-checked', 'true')
    expect(document.documentElement).toHaveAttribute('data-theme', 'dark')
    expect(localStorage.getItem(THEME_KEY)).toBe('dark')
    fireEvent.click(toggle)
    expect(toggle).toHaveAttribute('aria-checked', 'false')
    expect(document.documentElement).not.toHaveAttribute('data-theme')
    expect(localStorage.getItem(THEME_KEY)).toBeNull()
  })

  it('после монтирования отражает сохранённую тёмную', () => {
    localStorage.setItem(THEME_KEY, 'dark')
    render(<ThemeToggle />)
    expect(screen.getByRole('switch', { name: 'Тёмная тема' })).toHaveAttribute(
      'aria-checked',
      'true',
    )
  })

  it('на сервере рендерит светлое состояние независимо от хранилища', () => {
    localStorage.setItem(THEME_KEY, 'dark')
    const html = renderToString(<ThemeToggle />)
    expect(html).toContain('aria-checked="false"')
    expect(html).toContain('role="switch"')
  })

  it('иконки декоративные', () => {
    const { container } = render(<ThemeToggle />)
    for (const svg of container.querySelectorAll('svg')) {
      expect(svg).toHaveAttribute('aria-hidden', 'true')
    }
  })
})
