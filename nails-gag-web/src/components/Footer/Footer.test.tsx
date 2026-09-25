import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { MemoryRouter } from 'react-router'
import { site } from '../../content/site'
import { Footer } from './Footer'

function renderFooter() {
  return render(
    <MemoryRouter>
      <Footer />
    </MemoryRouter>,
  )
}

describe('Footer', () => {
  it('links to both legal documents and shows years', () => {
    renderFooter()
    expect(
      screen.getByRole('link', { name: 'Политика обработки персональных данных' }),
    ).toHaveAttribute('href', '/privacy')
    expect(screen.getByRole('link', { name: 'Пользовательское соглашение' })).toHaveAttribute(
      'href',
      '/terms',
    )
    expect(screen.getByText(/© 2023–\d{4} nails\.gag/)).toBeInTheDocument()
  })

  it('shows the performer line', () => {
    renderFooter()
    expect(site.performerLine).toMatch(/^Исполнитель: самозанятая .+, ИНН .+$/)
    expect(screen.getByText(site.performerLine)).toBeInTheDocument()
  })

  it('has only the two document links and no telegram link', () => {
    const { container } = renderFooter()
    expect(container.querySelector('a[href*="t.me"]')).toBeNull()
    expect(screen.getAllByRole('link')).toHaveLength(2)
  })
})
