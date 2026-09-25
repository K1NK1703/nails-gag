import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { about } from '../../content/about'
import { About } from './About'

describe('About', () => {
  it('renders all paragraphs', () => {
    render(<About />)
    for (const p of about.paragraphs) expect(screen.getByText(p)).toBeInTheDocument()
  })

  it('offers a 480w source and sizes that fit phones', () => {
    render(<About />)
    const img = screen.getByAltText(about.photo.alt)
    expect(img.getAttribute('srcset')).toContain(' 480w')
    expect(img).toHaveAttribute('sizes', '(min-width: 1264px) 480px, (min-width: 768px) 40vw, 92vw')
  })
})
