import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { prices } from '../../content/prices'
import { site } from '../../content/site'
import { Price } from './Price'

vi.mock('../../lib/analytics', () => ({ track: vi.fn() }))
import { track } from '../../lib/analytics'

function renderPrice() {
  return render(
    <MemoryRouter>
      <Price />
    </MemoryRouter>,
  )
}

describe('Price', () => {
  it('renders every price from content', () => {
    renderPrice()
    for (const p of prices) {
      expect(screen.getByText(p.title)).toBeInTheDocument()
      expect(screen.getByText(p.price)).toBeInTheDocument()
    }
  })

  it('links to the booking page from the note, tracked with place=price', () => {
    const { container } = renderPrice()
    const link = screen.getByRole('link', { name: site.priceNote.linkText })
    expect(link).toHaveAttribute('href', '/booking')
    expect(link).not.toHaveAttribute('target')
    fireEvent.click(link)
    expect(track).toHaveBeenCalledWith({ name: 'cta_click', place: 'price' })
    expect(container.querySelectorAll('.button').length).toBe(0)
  })
})
