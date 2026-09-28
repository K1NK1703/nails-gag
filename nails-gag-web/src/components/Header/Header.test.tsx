import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { Header } from './Header'

vi.mock('../../lib/analytics', () => ({ track: vi.fn() }))
import { track } from '../../lib/analytics'

function renderHeader() {
  return render(
    <MemoryRouter>
      <Header />
    </MemoryRouter>,
  )
}

describe('Header', () => {
  afterEach(() => vi.unstubAllEnvs())

  it('guest sees «Войти» links to the cabinet in the header and in the menu', () => {
    vi.stubEnv('VITE_PREVIEW_USER', '')
    renderHeader()
    const logins = screen.getAllByRole('link', { name: 'Войти' })
    expect(logins).toHaveLength(2)
    for (const link of logins) expect(link).toHaveAttribute('href', '/cabinet')
    expect(screen.queryByText('Личный кабинет')).not.toBeInTheDocument()
  })

  it('logged-in user sees the first name and a «Личный кабинет» entry', () => {
    vi.stubEnv('VITE_PREVIEW_USER', 'Алина')
    renderHeader()
    const nav = screen.getByRole('navigation', { name: 'Основное меню' })
    const entry = within(nav).getByRole('link', { name: 'Личный кабинет' })
    expect(entry).toHaveAttribute('href', '/cabinet')
    // Доступное имя плашки совпадает с видимым текстом (WCAG 2.5.3), подсказка — в title.
    const pill = screen.getByRole('link', { name: 'Алина' })
    expect(pill).toHaveAttribute('href', '/cabinet')
    expect(pill).not.toHaveAttribute('aria-label')
    expect(pill).toHaveAttribute('title', 'Личный кабинет')
    expect(pill.querySelector('.avatar')).toHaveTextContent(/^А$/)
    expect(screen.queryByRole('link', { name: 'Войти' })).not.toBeInTheDocument()
  })

  it('keeps the cabinet out of the nav anchors', () => {
    renderHeader()
    const nav = screen.getByRole('navigation', { name: 'Основное меню' })
    const anchors = within(nav)
      .getAllByRole('link')
      .filter((a) => a.getAttribute('href')?.startsWith('/#'))
    expect(anchors.map((a) => a.textContent)).toEqual(['Галерея', 'Прайс', 'Контакты'])
  })

  it('hides the account entry and menu item when the cabinet flag is "false"', () => {
    vi.stubEnv('VITE_FEATURE_CABINET', 'false')
    renderHeader()
    expect(screen.queryByRole('link', { name: /войти|личный кабинет/i })).not.toBeInTheDocument()
    expect(document.querySelector('a[href="/cabinet"]')).toBeNull()
  })

  it('cta leads to the booking page and is tracked with place=header', () => {
    renderHeader()
    const link = screen.getByRole('link', { name: /записаться/i })
    expect(link).toHaveAttribute('href', '/booking')
    expect(link).not.toHaveAttribute('target')
    expect(link).not.toHaveAttribute('rel')
    fireEvent.click(link)
    expect(track).toHaveBeenCalledWith({ name: 'cta_click', place: 'header' })
  })

  it('toggles mobile menu', () => {
    renderHeader()
    const button = screen.getByRole('button', { name: /меню/i })
    expect(button).toHaveAttribute('aria-expanded', 'false')
    fireEvent.click(button)
    expect(button).toHaveAttribute('aria-expanded', 'true')
  })

  it('keeps the menu mounted and toggles data-open for transitions', () => {
    renderHeader()
    const button = screen.getByRole('button', { name: /меню/i })
    const nav = document.getElementById('site-nav')
    expect(nav).toHaveAttribute('data-open', 'false')
    fireEvent.click(button)
    expect(nav).toHaveAttribute('data-open', 'true')
    expect(screen.getByText('Прайс')).toHaveStyle({ '--i': '1' })
  })

  it('closes mobile menu on Escape', () => {
    renderHeader()
    const button = screen.getByRole('button', { name: /меню/i })
    fireEvent.click(button)
    expect(button).toHaveAttribute('aria-expanded', 'true')
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(button).toHaveAttribute('aria-expanded', 'false')
  })

  it('returns focus to the burger when the menu is closed with Escape', () => {
    renderHeader()
    const button = screen.getByRole('button', { name: /меню/i })
    fireEvent.click(button)
    const nav = screen.getByRole('navigation', { name: 'Основное меню' })
    within(nav).getByText('Прайс').focus()
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(button).toHaveAttribute('aria-expanded', 'false')
    expect(button).toHaveFocus()
  })

  it('closes mobile menu on pointerdown outside the header, but not inside', () => {
    renderHeader()
    const button = screen.getByRole('button', { name: /меню/i })
    fireEvent.click(button)
    fireEvent.pointerDown(screen.getByText('Прайс'))
    expect(button).toHaveAttribute('aria-expanded', 'true')
    fireEvent.pointerDown(document.body)
    expect(button).toHaveAttribute('aria-expanded', 'false')
  })

  it('closes mobile menu when a nav link is clicked', () => {
    renderHeader()
    const button = screen.getByRole('button', { name: /меню/i })
    fireEvent.click(button)
    expect(button).toHaveAttribute('aria-expanded', 'true')
    fireEvent.click(screen.getByText('Галерея'))
    expect(button).toHaveAttribute('aria-expanded', 'false')
  })

  it('closes mobile menu when the account item in the menu is clicked', () => {
    renderHeader()
    const button = screen.getByRole('button', { name: /меню/i })
    fireEvent.click(button)
    const nav = screen.getByRole('navigation', { name: 'Основное меню' })
    fireEvent.click(within(nav).getByRole('link', { name: 'Войти' }))
    expect(button).toHaveAttribute('aria-expanded', 'false')
  })

  it('puts the «Оформление» button before the account entry', () => {
    vi.stubEnv('VITE_PREVIEW_USER', '')
    renderHeader()
    const appearance = screen.getByRole('button', { name: 'Оформление' })
    const account = screen.getAllByRole('link', { name: 'Войти' }).at(-1)
    expect(account).toBeDefined()
    if (!account) return
    expect(
      appearance.compareDocumentPosition(account) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy()
  })
})
