import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { site } from '../../content/site'
import { Contacts } from './Contacts'

vi.mock('../../lib/analytics', () => ({ track: vi.fn() }))
import { track } from '../../lib/analytics'

describe('Contacts', () => {
  it('renders telegram, channel and address cards', () => {
    const { container } = render(<Contacts />)
    expect(container.querySelectorAll('li')).toHaveLength(3)
    expect(screen.getByRole('link', { name: site.telegramHandle })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: site.channelHandle })).toBeInTheDocument()
    expect(screen.getByText(site.address.street)).toBeInTheDocument()
    expect(screen.getAllByRole('link')).toHaveLength(3)
    expect(screen.queryByText(/instagram/i)).not.toBeInTheDocument()
  })

  it('telegram link opens in a new tab and tracks contact_click', () => {
    render(<Contacts />)
    const link = screen.getByRole('link', { name: site.telegramHandle })
    expect(link).toHaveAttribute('href', site.telegramUrl)
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
    fireEvent.click(link)
    expect(track).toHaveBeenCalledWith({ name: 'contact_click', channel: 'telegram' })
  })

  it('channel card links to the telegram channel and tracks contact_click', () => {
    render(<Contacts />)
    const link = screen.getByRole('link', { name: site.channelHandle })
    expect(link).toHaveAttribute('href', site.channelUrl)
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
    fireEvent.click(link)
    expect(track).toHaveBeenCalledWith({ name: 'contact_click', channel: 'channel' })
  })

  it('address opens Yandex Maps and is tracked', () => {
    render(<Contacts />)
    const link = screen.getByRole('link', { name: /открыть в Яндекс\.Картах/ })
    expect(link).toHaveTextContent(site.address.street)
    const href = link.getAttribute('href') ?? ''
    expect(new URL(href).hostname).toBe('yandex.ru')
    expect(href).toMatch(/^https:\/\/yandex\.ru\/maps\/\?text=/)
    expect(href).not.toMatch(/\s/)
    expect(link).toHaveAttribute('target', '_blank')
    fireEvent.click(link)
    expect(track).toHaveBeenCalledWith({ name: 'map_open' })
  })
})
