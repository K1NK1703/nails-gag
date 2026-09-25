import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, useNavigate } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { MockInstance } from 'vitest'
import { AppRoutes } from '../App'

vi.mock('./analytics', () => ({ track: vi.fn() }))

function BackButton() {
  const navigate = useNavigate()
  return (
    <button type="button" onClick={() => void navigate(-1)}>
      Назад
    </button>
  )
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AppRoutes />
      <BackButton />
    </MemoryRouter>,
  )
}

const TOP = { top: 0, left: 0, behavior: 'instant' }

describe('useScrollToTop', () => {
  let scrollTo: MockInstance<typeof window.scrollTo>

  beforeEach(() => {
    scrollTo = vi.spyOn(window, 'scrollTo')
  })

  afterEach(() => {
    scrollTo.mockRestore()
  })

  it('прокручивает наверх при переходе на новый маршрут по ссылке', () => {
    renderAt('/')
    fireEvent.click(screen.getByText('Политика обработки персональных данных'))
    expect(scrollTo).toHaveBeenCalledWith(TOP)
  })

  it('не прокручивает при первом монтировании', () => {
    renderAt('/privacy')
    expect(scrollTo).not.toHaveBeenCalled()
  })

  it('не прокручивает на POP (назад), позицию восстанавливает браузер', () => {
    renderAt('/')
    fireEvent.click(screen.getByText('Политика обработки персональных данных'))
    scrollTo.mockClear()
    fireEvent.click(screen.getByRole('button', { name: 'Назад' }))
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Чёткие квадраты')
    expect(scrollTo).not.toHaveBeenCalled()
  })

  it('не прокручивает, если в адресе есть хэш', () => {
    renderAt('/#contacts')
    expect(scrollTo).not.toHaveBeenCalled()
  })
})
