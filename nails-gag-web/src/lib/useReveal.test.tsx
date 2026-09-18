import { render } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { useReveal } from './useReveal'

type IoEntry = { isIntersecting: boolean; target: Element }
type IoCallback = (entries: IoEntry[]) => void

function Demo() {
  useReveal()
  return (
    <div>
      <div className="reveal" data-testid="a" />
      <div className="reveal" data-testid="b" />
    </div>
  )
}

const originalIo = globalThis.IntersectionObserver
const originalRect = Element.prototype.getBoundingClientRect

afterEach(() => {
  document.documentElement.classList.remove('js')
  globalThis.IntersectionObserver = originalIo
  Element.prototype.getBoundingClientRect = originalRect
})

describe('useReveal', () => {
  it('включает js и показывает уже видимые элементы', () => {
    const { getByTestId } = render(<Demo />)
    expect(document.documentElement).toHaveClass('js')
    expect(getByTestId('a')).toHaveClass('is-visible')
    expect(getByTestId('b')).toHaveClass('is-visible')
  })

  it('элемент ниже вьюпорта показывается только по сигналу IntersectionObserver', () => {
    // jsdom всегда отдаёт нулевой rect, поэтому уводим один элемент под вьюпорт вручную.
    Element.prototype.getBoundingClientRect = function (this: Element) {
      const far = this instanceof HTMLElement && this.dataset['testid'] === 'b'
      return { top: far ? 10000 : 0 } as DOMRect
    }

    const observed: Element[] = []
    let callback: IoCallback | undefined
    class FakeIo {
      constructor(cb: IoCallback) {
        callback = cb
      }
      observe(el: Element) {
        observed.push(el)
      }
      unobserve() {}
      disconnect() {}
    }
    globalThis.IntersectionObserver = FakeIo as unknown as typeof IntersectionObserver

    const { getByTestId } = render(<Demo />)
    const near = getByTestId('a')
    const far = getByTestId('b')

    expect(near).toHaveClass('is-visible')
    expect(far).not.toHaveClass('is-visible')
    expect(observed).toEqual([far])

    callback?.([{ isIntersecting: true, target: far }])
    expect(far).toHaveClass('is-visible')
  })
})
