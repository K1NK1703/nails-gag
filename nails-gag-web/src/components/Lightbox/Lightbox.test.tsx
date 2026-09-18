import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { Photo } from '../../content/gallery'
import { Lightbox } from './Lightbox'

const photos: Photo[] = [
  { slug: 'a', alt: 'Первое' },
  { slug: 'b', alt: 'Второе' },
  { slug: 'c', alt: 'Третье' },
]

describe('Lightbox', () => {
  it('renders nothing when closed', () => {
    const { container } = render(
      <Lightbox photos={photos} index={null} onClose={() => {}} onNavigate={() => {}} />,
    )
    expect(container).toBeEmptyDOMElement()
    expect(document.querySelector('[role="dialog"]')).toBeNull()
  })

  it('shows current photo and navigates with arrow keys', () => {
    const onNavigate = vi.fn()
    render(<Lightbox photos={photos} index={1} onClose={() => {}} onNavigate={onNavigate} />)
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(screen.getByAltText('Второе')).toBeInTheDocument()
    fireEvent.keyDown(window, { key: 'ArrowRight' })
    expect(onNavigate).toHaveBeenLastCalledWith(2)
    fireEvent.keyDown(window, { key: 'ArrowLeft' })
    expect(onNavigate).toHaveBeenLastCalledWith(0)
  })

  it('wraps around at the ends', () => {
    const onNavigate = vi.fn()
    render(<Lightbox photos={photos} index={2} onClose={() => {}} onNavigate={onNavigate} />)
    fireEvent.keyDown(window, { key: 'ArrowRight' })
    expect(onNavigate).toHaveBeenLastCalledWith(0)
  })

  it('closes on Escape and on close button', () => {
    const onClose = vi.fn()
    render(<Lightbox photos={photos} index={0} onClose={onClose} onNavigate={() => {}} />)
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(onClose).toHaveBeenCalledTimes(1)
    fireEvent.click(screen.getByRole('button', { name: /закрыть/i }))
    expect(onClose).toHaveBeenCalledTimes(2)
  })

  it('closes on backdrop click but not on the image', () => {
    const onClose = vi.fn()
    render(<Lightbox photos={photos} index={0} onClose={onClose} onNavigate={() => {}} />)
    fireEvent.click(screen.getByAltText('Первое'))
    expect(onClose).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('presentation'))
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('traps Tab focus inside the dialog', () => {
    render(<Lightbox photos={photos} index={0} onClose={() => {}} onNavigate={() => {}} />)
    const close = screen.getByRole('button', { name: /закрыть/i })
    const last = screen.getByRole('button', { name: /следующее фото/i })
    last.focus()
    expect(last).toHaveFocus()
    fireEvent.keyDown(window, { key: 'Tab' })
    expect(close).toHaveFocus()
    fireEvent.keyDown(window, { key: 'Tab', shiftKey: true })
    expect(last).toHaveFocus()
  })

  it('locks body scroll while open', () => {
    const { unmount } = render(
      <Lightbox photos={photos} index={0} onClose={() => {}} onNavigate={() => {}} />,
    )
    expect(document.body.style.overflow).toBe('hidden')
    unmount()
    expect(document.body.style.overflow).toBe('')
  })
})
