import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { Hero } from './Hero'

vi.mock('../../lib/analytics', () => ({ track: vi.fn() }))
import { track } from '../../lib/analytics'

describe('Hero', () => {
  it('renders h1 with tagline and price pill', () => {
    render(<Hero />)
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Чёткие квадраты')
    expect(screen.getByText(/2 500 ₽/)).toBeInTheDocument()
  })

  it('tracks cta click with place=hero', () => {
    render(<Hero />)
    fireEvent.click(screen.getByRole('link', { name: /записаться/i }))
    expect(track).toHaveBeenCalledWith({ name: 'cta_click', place: 'hero' })
  })
})
