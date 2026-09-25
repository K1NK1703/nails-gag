import { fireEvent, render } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useTilt } from './useTilt'

function Demo() {
  const ref = useTilt<HTMLDivElement>()
  return <div ref={ref} data-testid="el" />
}

const originalRect = Element.prototype.getBoundingClientRect

function stubMatchMedia(hover: boolean) {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: query.includes('hover') ? hover : false,
    media: query,
    addEventListener: () => {},
    removeEventListener: () => {},
  }))
}

afterEach(() => {
  vi.unstubAllGlobals()
  Element.prototype.getBoundingClientRect = originalRect
})

describe('useTilt', () => {
  it('pointermove задаёт --rx/--ry, pointerleave сбрасывает', () => {
    stubMatchMedia(true)
    Element.prototype.getBoundingClientRect = () =>
      ({ left: 0, top: 0, width: 200, height: 200 }) as DOMRect
    const { getByTestId } = render(<Demo />)
    const el = getByTestId('el')

    fireEvent.pointerMove(el, { clientX: 200, clientY: 0 })
    expect(el.style.getPropertyValue('--ry')).toBe('5.00deg')
    expect(el.style.getPropertyValue('--rx')).toBe('5.00deg')
    expect(el.style.getPropertyValue('--mx')).toBe('100.0%')

    fireEvent.pointerLeave(el)
    expect(el.style.getPropertyValue('--rx')).toBe('')
  })

  it('без hover ничего не делает', () => {
    stubMatchMedia(false)
    const { getByTestId } = render(<Demo />)
    const el = getByTestId('el')
    fireEvent.pointerMove(el, { clientX: 10, clientY: 10 })
    expect(el.style.getPropertyValue('--rx')).toBe('')
  })
})
