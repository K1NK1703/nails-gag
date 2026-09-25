import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { privacy } from '../content/privacy'
import { Privacy } from './Privacy'

describe('Privacy', () => {
  it('renders every section title as a heading', () => {
    render(<Privacy />)
    for (const s of privacy.sections) {
      expect(screen.getByRole('heading', { level: 2, name: s.title })).toHaveAttribute(
        'id',
        `p-${s.id}`,
      )
    }
  })

  it('has a table of contents with one link per section', () => {
    render(<Privacy />)
    const toc = screen.getByRole('navigation', { name: 'Содержание' })
    const links = within(toc).getAllByRole('link')
    expect(links).toHaveLength(privacy.sections.length)
    expect(links[0]).toHaveAttribute('href', `#p-${privacy.sections[0].id}`)
  })

  it('shows version and update date', () => {
    render(<Privacy />)
    expect(screen.getByText('Версия 1.0 · обновлено 25 сентября 2026')).toBeInTheDocument()
  })
})
