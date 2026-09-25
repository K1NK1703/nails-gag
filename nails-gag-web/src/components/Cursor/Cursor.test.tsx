import { act, fireEvent, render } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Cursor } from './Cursor'

function stubMatchMedia(fine: boolean, reducedMotion = false) {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: query.includes('reduce') ? reducedMotion : fine,
    media: query,
    addEventListener: () => {},
    removeEventListener: () => {},
  }))
}

function cursorEl(container: HTMLElement) {
  return container.querySelector<HTMLElement>('[aria-hidden="true"]')
}

function pointer(type: string, target: EventTarget, init: MouseEventInit = {}) {
  // jsdom не реализует PointerEvent полноценно — MouseEvent с тем же type достаточно.
  act(() => {
    target.dispatchEvent(new MouseEvent(type, { bubbles: true, ...init }))
  })
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['requestAnimationFrame', 'cancelAnimationFrame'] })
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  document.documentElement.classList.remove('custom-cursor')
})

describe('Cursor', () => {
  it('без hover/точного указателя ничего не рендерит и не прячет системный курсор', () => {
    stubMatchMedia(false)
    const { container } = render(<Cursor />)
    expect(container).toBeEmptyDOMElement()
    fireEvent.pointerMove(document, { clientX: 10, clientY: 10 })
    expect(document.documentElement).not.toHaveClass('custom-cursor')
  })

  it('при prefers-reduced-motion ничего не рендерит', () => {
    stubMatchMedia(true, true)
    const { container } = render(<Cursor />)
    expect(container).toBeEmptyDOMElement()
  })

  it('появляется после pointermove, следует за указателем и меняет состояния', () => {
    stubMatchMedia(true)
    const { container } = render(<Cursor />)
    const el = cursorEl(container)
    expect(el).not.toBeNull()
    if (!el) return
    expect(el).toHaveAttribute('data-visible', 'false')
    expect(document.documentElement).not.toHaveClass('custom-cursor')

    pointer('pointermove', document, { clientX: 100, clientY: 50 })
    expect(el).toHaveAttribute('data-visible', 'true')
    expect(document.documentElement).toHaveClass('custom-cursor')
    expect(el.style.transform).toBe('translate3d(100px, 50px, 0)')

    // Инерция: после одного кадра курсор ещё в пути, затем догоняет цель и цикл останавливается.
    pointer('pointermove', document, { clientX: 200, clientY: 50 })
    act(() => vi.advanceTimersToNextFrame())
    expect(el.style.transform).toBe('translate3d(130px, 50px, 0)')
    act(() => vi.advanceTimersByTime(2000))
    expect(el.style.transform).toBe('translate3d(200px, 50px, 0)')
    expect(vi.getTimerCount()).toBe(0)

    const link = document.createElement('a')
    link.href = '#x'
    const span = document.createElement('span')
    link.append(span)
    document.body.append(link)
    pointer('pointerover', span)
    expect(el).toHaveAttribute('data-state', 'link')

    // Нажатие сразу ставит руку под указатель, без инерции.
    pointer('pointermove', document, { clientX: 400, clientY: 300 })
    pointer('pointerdown', span, { clientX: 400, clientY: 300 })
    expect(el).toHaveAttribute('data-state', 'press')
    expect(el.style.transform).toBe('translate3d(400px, 300px, 0)')
    expect(vi.getTimerCount()).toBe(0)
    pointer('pointerup', span, { clientX: 400, clientY: 300 })
    expect(el).toHaveAttribute('data-state', 'link')

    const tile = document.createElement('button')
    tile.dataset.cursor = 'photo'
    document.body.append(tile)
    pointer('pointerover', tile)
    expect(el).toHaveAttribute('data-state', 'photo')

    const input = document.createElement('input')
    document.body.append(input)
    pointer('pointerover', input)
    expect(el).toHaveAttribute('data-visible', 'false')
    pointer('pointerover', document.body)
    expect(el).toHaveAttribute('data-state', 'default')
    expect(el).toHaveAttribute('data-visible', 'true')

    // Уход указателя за окно прячет руку, возвращение — показывает.
    pointer('pointerout', document.body, { relatedTarget: null })
    expect(el).toHaveAttribute('data-visible', 'false')
    pointer('pointermove', document, { clientX: 10, clientY: 10 })
    expect(el).toHaveAttribute('data-visible', 'true')

    link.remove()
    tile.remove()
    input.remove()
  })

  it('при размонтировании снимает класс и слушатели', () => {
    stubMatchMedia(true)
    const remove = vi.spyOn(document, 'removeEventListener')
    const { unmount } = render(<Cursor />)
    pointer('pointermove', document, { clientX: 5, clientY: 5 })
    expect(document.documentElement).toHaveClass('custom-cursor')

    unmount()
    expect(document.documentElement).not.toHaveClass('custom-cursor')
    const removed = remove.mock.calls.map(([type]) => type)
    for (const type of ['pointermove', 'pointerover', 'pointerout', 'pointerdown', 'pointerup']) {
      expect(removed).toContain(type)
    }
  })
})
