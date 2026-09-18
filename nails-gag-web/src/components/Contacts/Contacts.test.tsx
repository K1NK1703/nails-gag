import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { site } from '../../content/site'
import { Contacts } from './Contacts'

vi.mock('../../lib/analytics', () => ({ track: vi.fn() }))
import { track } from '../../lib/analytics'

describe('Contacts', () => {
  it('renders booking, channel and address', () => {
    render(<Contacts />)
    expect(screen.getByText(site.bookingHandle)).toBeInTheDocument()
    expect(screen.getByText(site.channelHandle)).toBeInTheDocument()
    expect(screen.getByText(site.address.street)).toBeInTheDocument()
    expect(screen.queryByText(/instagram/i)).not.toBeInTheDocument()
  })

  it('tracks booking click with place=contacts', () => {
    render(<Contacts />)
    fireEvent.click(screen.getByRole('link', { name: new RegExp(site.bookingHandle) }))
    expect(track).toHaveBeenCalledWith({ name: 'cta_click', place: 'contacts' })
  })
})
