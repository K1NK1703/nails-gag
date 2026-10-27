import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { Lightbox, type LightboxPhoto } from './Lightbox'

function photo(id: string, alt: string, caption?: string): LightboxPhoto {
  return {
    id,
    alt,
    caption,
    src: `/${id}-1600.jpg`,
    srcSet: `/${id}-1600.jpg 1600w`,
    width: 1200,
    height: 1600,
  }
}

const photos: LightboxPhoto[] = [
  photo('a', 'Первое', 'Молочный'),
  photo('b', 'Второе'),
  photo('c', 'Третье', 'Шиммер'),
  photo('d', 'Четвёртое'),
  photo('e', 'Пятое'),
]

const noop = () => {}

function track() {
  const el = screen.getByRole('dialog', { hidden: true }).firstElementChild
  if (!(el instanceof HTMLElement)) throw new Error('track not found')
  return el
}

/** jsdom не считает раскладку: задаём ширину ленты, чтобы работала логика прокрутки. */
function fakeWidth(width: number) {
  vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(width)
}

const originalScrollTo = HTMLElement.prototype.scrollTo

afterEach(() => {
  HTMLElement.prototype.scrollTo = originalScrollTo
  vi.restoreAllMocks()
  vi.useRealTimers()
})

describe('Lightbox', () => {
  it('renders nothing when closed', () => {
    const { container } = render(
      <Lightbox photos={photos} index={null} onClose={noop} onNavigate={noop} />,
    )
    expect(container).toBeEmptyDOMElement()
    expect(document.querySelector('[role="dialog"]')).toBeNull()
  })

  it('opens with focus on the close button', () => {
    render(<Lightbox photos={photos} index={1} onClose={noop} onNavigate={noop} />)
    expect(screen.getByRole('dialog')).toHaveAccessibleName('Второе')
    expect(screen.getByRole('button', { name: /закрыть/i })).toHaveFocus()
  })

  it('renders real images only for the current photo and its neighbours', () => {
    render(<Lightbox photos={photos} index={2} onClose={noop} onNavigate={noop} />)
    const imgs = [...track().querySelectorAll('img')].map((img) => img.getAttribute('alt'))
    expect(imgs).toEqual(['Второе', 'Третье', 'Четвёртое'])
    expect(track().children).toHaveLength(photos.length)
    expect(screen.getByAltText('Третье')).toHaveAttribute('loading', 'eager')
    expect(screen.getByAltText('Четвёртое')).toHaveAttribute('loading', 'lazy')
  })

  it('shows the active photo in the indicator, caption and live status', () => {
    render(<Lightbox photos={photos} index={2} onClose={noop} onNavigate={noop} />)
    const dots = screen.getByTestId('lightbox-dots').children
    expect(dots).toHaveLength(photos.length)
    expect([...dots].map((d) => d.hasAttribute('data-active'))).toEqual([
      false,
      false,
      true,
      false,
      false,
    ])
    expect(screen.getByText('3 из 5 · Шиммер')).toBeInTheDocument()
  })

  it('navigates with arrow keys and wraps around at the ends', () => {
    const onNavigate = vi.fn()
    const { rerender } = render(
      <Lightbox photos={photos} index={4} onClose={noop} onNavigate={onNavigate} />,
    )
    fireEvent.keyDown(window, { key: 'ArrowRight' })
    expect(onNavigate).toHaveBeenLastCalledWith(0)
    fireEvent.keyDown(window, { key: 'ArrowLeft' })
    expect(onNavigate).toHaveBeenLastCalledWith(3)
    rerender(<Lightbox photos={photos} index={0} onClose={noop} onNavigate={onNavigate} />)
    fireEvent.keyDown(window, { key: 'ArrowLeft' })
    expect(onNavigate).toHaveBeenLastCalledWith(4)
  })

  it('navigates with the chevron buttons', () => {
    const onNavigate = vi.fn()
    render(<Lightbox photos={photos} index={0} onClose={noop} onNavigate={onNavigate} />)
    fireEvent.click(screen.getByRole('button', { name: /следующее фото/i }))
    expect(onNavigate).toHaveBeenLastCalledWith(1)
    fireEvent.click(screen.getByRole('button', { name: /предыдущее фото/i }))
    expect(onNavigate).toHaveBeenLastCalledWith(4)
  })

  it('closes on Escape and on the close button', () => {
    const onClose = vi.fn()
    render(<Lightbox photos={photos} index={0} onClose={onClose} onNavigate={noop} />)
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(onClose).toHaveBeenCalledTimes(1)
    fireEvent.click(screen.getByRole('button', { name: /закрыть/i }))
    expect(onClose).toHaveBeenCalledTimes(2)
  })

  it('closes on a click beside the photo but not on the photo itself', () => {
    const onClose = vi.fn()
    render(<Lightbox photos={photos} index={0} onClose={onClose} onNavigate={noop} />)
    fireEvent.click(screen.getByAltText('Первое'))
    expect(onClose).not.toHaveBeenCalled()
    fireEvent.click(track().children[0])
    expect(onClose).toHaveBeenCalledTimes(1)
    fireEvent.click(screen.getByRole('presentation'))
    expect(onClose).toHaveBeenCalledTimes(2)
  })

  it('scrolls the track instantly on open and smoothly to a neighbour', () => {
    fakeWidth(100)
    const scrollTo = vi.fn()
    HTMLElement.prototype.scrollTo = scrollTo
    const { rerender } = render(
      <Lightbox photos={photos} index={2} onClose={noop} onNavigate={noop} />,
    )
    expect(scrollTo).toHaveBeenLastCalledWith({ left: 200, behavior: 'instant' })
    track().scrollLeft = 200
    rerender(<Lightbox photos={photos} index={3} onClose={noop} onNavigate={noop} />)
    expect(scrollTo).toHaveBeenLastCalledWith({ left: 300, behavior: 'smooth' })
  })

  it('reports the slide the user swiped to once scrolling settles', () => {
    vi.useFakeTimers()
    fakeWidth(100)
    HTMLElement.prototype.scrollTo = vi.fn()
    const onNavigate = vi.fn()
    render(<Lightbox photos={photos} index={0} onClose={noop} onNavigate={onNavigate} />)
    track().scrollLeft = 100
    fireEvent.scroll(track())
    fireEvent(track(), new Event('scrollend'))
    act(() => {
      vi.advanceTimersByTime(500)
    })
    expect(onNavigate).toHaveBeenCalledTimes(1)
    expect(onNavigate).toHaveBeenCalledWith(1)
    expect(screen.getByText('2 из 5')).toBeInTheDocument()
  })

  it('traps Tab focus inside the dialog', () => {
    render(<Lightbox photos={photos} index={0} onClose={noop} onNavigate={noop} />)
    const close = screen.getByRole('button', { name: /закрыть/i })
    const last = screen.getByRole('button', { name: /следующее фото/i })
    last.focus()
    fireEvent.keyDown(window, { key: 'Tab' })
    expect(close).toHaveFocus()
    fireEvent.keyDown(window, { key: 'Tab', shiftKey: true })
    expect(last).toHaveFocus()
  })

  it('locks body scroll while open', () => {
    const { unmount } = render(
      <Lightbox photos={photos} index={0} onClose={noop} onNavigate={noop} />,
    )
    expect(document.body.style.overflow).toBe('hidden')
    unmount()
    expect(document.body.style.overflow).toBe('')
  })
})
