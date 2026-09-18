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
})
