import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { MemoryRouter } from 'react-router'
import { Footer } from './Footer'

describe('Footer', () => {
  it('links to privacy policy and shows years', () => {
    render(
      <MemoryRouter>
        <Footer />
      </MemoryRouter>,
    )
    expect(screen.getByRole('link', { name: /политика конфиденциальности/i })).toHaveAttribute(
      'href',
      '/privacy',
    )
    expect(screen.getByText(/© 2023–\d{4} nails\.gag/)).toBeInTheDocument()
  })

  it('has no telegram link', () => {
    const { container } = render(
      <MemoryRouter>
        <Footer />
      </MemoryRouter>,
    )
    expect(container.querySelector('a[href*="t.me"]')).toBeNull()
    expect(screen.getAllByRole('link')).toHaveLength(1)
  })
})
