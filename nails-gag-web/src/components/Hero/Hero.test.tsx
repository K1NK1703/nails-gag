import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { site } from '../../content/site'
import { Hero } from './Hero'

vi.mock('../../lib/analytics', () => ({ track: vi.fn() }))
import { track } from '../../lib/analytics'

describe('Hero', () => {
  it('renders h1 with tagline', () => {
    render(<Hero />)
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Чёткие квадраты')
  })

  it('tracks cta click with place=hero', () => {
    render(<Hero />)
    fireEvent.click(screen.getByRole('link', { name: /записаться/i }))
    expect(track).toHaveBeenCalledWith({ name: 'cta_click', place: 'hero' })
  })

  it('master card links to the about section and shows the master name', () => {
    render(<Hero />)
    const card = screen.getByRole('link', { name: 'Подробнее о мастере' })
    expect(card).toHaveAttribute('href', '#about')
    expect(card).toHaveTextContent(site.masterName)
  })
})
