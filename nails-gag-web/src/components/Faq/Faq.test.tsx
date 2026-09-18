import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { faq } from '../../content/faq'
import { Faq } from './Faq'

vi.mock('../../lib/analytics', () => ({ track: vi.fn() }))
import { track } from '../../lib/analytics'

describe('Faq', () => {
  it('renders every question', () => {
    render(<Faq />)
    for (const q of faq) expect(screen.getByText(q.question)).toBeInTheDocument()
  })

  it('tracks opening a question once per open', () => {
    render(<Faq />)
    const details = screen.getByText(faq[0].question).closest('details')!
    details.open = true
    fireEvent(details, new Event('toggle'))
    expect(track).toHaveBeenCalledWith({ name: 'faq_open', id: faq[0].id })
  })
})
