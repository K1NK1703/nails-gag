import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { prices } from '../../content/prices'
import { Price } from './Price'

vi.mock('../../lib/analytics', () => ({ track: vi.fn() }))
import { track } from '../../lib/analytics'

describe('Price', () => {
  it('renders every price from content', () => {
    render(<Price />)
    for (const p of prices) {
      expect(screen.getByText(p.title)).toBeInTheDocument()
      expect(screen.getByText(p.price)).toBeInTheDocument()
    }
  })

  it('tracks cta click with place=price', () => {
    render(<Price />)
    fireEvent.click(screen.getAllByRole('link', { name: /записаться/i })[0])
    expect(track).toHaveBeenCalledWith({ name: 'cta_click', place: 'price' })
  })
})
