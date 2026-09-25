import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { site } from '../../content/site'
import { Hero } from './Hero'

vi.mock('../../lib/analytics', () => ({ track: vi.fn() }))
import { track } from '../../lib/analytics'

function renderHero() {
  return render(
    <MemoryRouter>
      <Hero />
    </MemoryRouter>,
  )
}

describe('Hero', () => {
  it('renders h1 with tagline', () => {
    renderHero()
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Чёткие квадраты')
  })

  it('cta leads to the booking page and is tracked with place=hero', () => {
    renderHero()
    const link = screen.getByRole('link', { name: /записаться/i })
    expect(link).toHaveAttribute('href', '/booking')
    expect(link).not.toHaveAttribute('target')
    expect(link).not.toHaveAttribute('rel')
    fireEvent.click(link)
    expect(track).toHaveBeenCalledWith({ name: 'cta_click', place: 'hero' })
  })

  it('master card links to the about section and shows the master name', () => {
    renderHero()
    const card = screen.getByRole('link', { name: 'Подробнее о мастере' })
    expect(card).toHaveAttribute('href', '#about')
    expect(card).toHaveTextContent(site.masterName)
  })
})
