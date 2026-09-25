import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AppRoutes } from '../App'

vi.mock('./analytics', () => ({ track: vi.fn() }))

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AppRoutes />
    </MemoryRouter>,
  )
}

describe('useScrollToTop', () => {
  afterEach(() => {
    // @ts-expect-error jsdom не реализует scrollTo, тест сам его подставляет
    delete window.scrollTo
  })

  it('прокручивает наверх при переходе на новый маршрут по ссылке', () => {
    window.scrollTo = vi.fn()
    renderAt('/')
    ;(window.scrollTo as ReturnType<typeof vi.fn>).mockClear()

    fireEvent.click(screen.getByText('Политика обработки персональных данных'))

    expect(window.scrollTo).toHaveBeenCalledWith({ top: 0, left: 0, behavior: 'instant' })
  })

  it('прокручивает наверх и при заходе через initialEntries', () => {
    window.scrollTo = vi.fn()
    renderAt('/privacy')

    expect(window.scrollTo).toHaveBeenCalledWith({ top: 0, left: 0, behavior: 'instant' })
  })

  it('не прокручивает, если в адресе есть хэш', () => {
    window.scrollTo = vi.fn()
    renderAt('/#contacts')

    expect(window.scrollTo).not.toHaveBeenCalled()
  })
})
