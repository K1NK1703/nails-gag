import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { prices } from '../../content/prices'
import { site } from '../../content/site'
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

  it('has a single booking link in the note, tracked with place=price', () => {
    const { container } = render(<Price />)
    const link = screen.getByRole('link', { name: site.priceNote.linkText })
    expect(link).toHaveAttribute('href', site.bookingUrl)
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
    fireEvent.click(link)
    expect(track).toHaveBeenCalledWith({ name: 'cta_click', place: 'price' })
    expect(container.querySelectorAll('.button').length).toBe(0)
  })
})
