import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { privacy } from '../../content/privacy'
import { terms } from '../../content/terms'
import { LegalDocument } from './LegalDocument'

describe('LegalDocument', () => {
  it('renders the title and every section title as a heading', () => {
    render(<LegalDocument {...privacy} />)
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(privacy.title)
    for (const s of privacy.sections) {
      expect(screen.getByRole('heading', { level: 2, name: s.title })).toHaveAttribute(
        'id',
        `p-${s.id}`,
      )
    }
  })

  it('has a table of contents with one link per section', () => {
    for (const doc of [privacy, terms]) {
      const { unmount } = render(<LegalDocument {...doc} />)
      const toc = screen.getByRole('navigation', { name: 'Содержание' })
      const links = within(toc).getAllByRole('link')
      expect(links).toHaveLength(doc.sections.length)
      expect(links[0]).toHaveAttribute('href', `#p-${doc.sections[0].id}`)
      unmount()
    }
  })

  it('shows version and update date', () => {
    render(<LegalDocument {...terms} />)
    expect(screen.getByText('Версия 1.0 · обновлено 25 сентября 2026')).toBeInTheDocument()
  })
})
