import { render } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { useReveal } from './useReveal'

function Demo() {
  useReveal()
  return (
    <div>
      <div className="reveal" data-testid="a" />
      <div className="reveal" data-testid="b" />
    </div>
  )
}

afterEach(() => document.documentElement.classList.remove('js'))

describe('useReveal', () => {
  it('включает js и показывает уже видимые элементы', () => {
    const { getByTestId } = render(<Demo />)
    expect(document.documentElement).toHaveClass('js')
    expect(getByTestId('a')).toHaveClass('is-visible')
    expect(getByTestId('b')).toHaveClass('is-visible')
  })
})
