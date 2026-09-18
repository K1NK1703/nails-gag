import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { about } from '../../content/about'
import { About } from './About'

describe('About', () => {
  it('renders all paragraphs and facts', () => {
    render(<About />)
    for (const p of about.paragraphs) expect(screen.getByText(p)).toBeInTheDocument()
    for (const f of about.facts) expect(screen.getByText(f.value)).toBeInTheDocument()
  })
})
