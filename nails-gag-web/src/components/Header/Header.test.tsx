import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { Header } from './Header'

vi.mock('../../lib/analytics', () => ({ track: vi.fn() }))
import { track } from '../../lib/analytics'

describe('Header', () => {
  afterEach(() => vi.unstubAllEnvs())

  it('hides cabinet link when flag is off', () => {
    vi.stubEnv('VITE_FEATURE_CABINET', '')
    render(<Header />)
    expect(screen.queryByText('Личный кабинет')).not.toBeInTheDocument()
  })

  it('shows cabinet link when flag is on', () => {
    vi.stubEnv('VITE_FEATURE_CABINET', 'true')
    render(<Header />)
    expect(screen.getByText('Личный кабинет')).toHaveAttribute('href', '/cabinet')
  })

  it('tracks cta click with place=header', () => {
    render(<Header />)
    fireEvent.click(screen.getByRole('link', { name: /записаться/i }))
    expect(track).toHaveBeenCalledWith({ name: 'cta_click', place: 'header' })
  })

  it('toggles mobile menu', () => {
    render(<Header />)
    const button = screen.getByRole('button', { name: /меню/i })
    expect(button).toHaveAttribute('aria-expanded', 'false')
    fireEvent.click(button)
    expect(button).toHaveAttribute('aria-expanded', 'true')
  })

  it('keeps the menu mounted and toggles data-open for transitions', () => {
    render(<Header />)
    const button = screen.getByRole('button', { name: /меню/i })
    const nav = document.getElementById('site-nav')
    expect(nav).toHaveAttribute('data-open', 'false')
    fireEvent.click(button)
    expect(nav).toHaveAttribute('data-open', 'true')
    expect(screen.getByText('Прайс')).toHaveStyle({ '--i': '1' })
  })

  it('closes mobile menu on Escape', () => {
    render(<Header />)
    const button = screen.getByRole('button', { name: /меню/i })
    fireEvent.click(button)
    expect(button).toHaveAttribute('aria-expanded', 'true')
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(button).toHaveAttribute('aria-expanded', 'false')
  })

  it('closes mobile menu when a nav link is clicked', () => {
    render(<Header />)
    const button = screen.getByRole('button', { name: /меню/i })
    fireEvent.click(button)
    expect(button).toHaveAttribute('aria-expanded', 'true')
    fireEvent.click(screen.getByText('Галерея'))
    expect(button).toHaveAttribute('aria-expanded', 'false')
  })
})
