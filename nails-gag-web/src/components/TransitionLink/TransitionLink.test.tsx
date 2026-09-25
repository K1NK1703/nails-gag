import { createEvent, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { TransitionLink } from './TransitionLink'

type StartViewTransition = (cb: () => void) => object

function renderApp() {
  return render(
    <MemoryRouter initialEntries={['/']}>
      <Routes>
        <Route
          path="/"
          element={
            <>
              <h1>Главная</h1>
              <TransitionLink to="/next">Дальше</TransitionLink>
            </>
          }
        />
        <Route path="/next" element={<h1>Следующая</h1>} />
      </Routes>
    </MemoryRouter>,
  )
}

function setStartViewTransition(fn: StartViewTransition | undefined) {
  Object.defineProperty(document, 'startViewTransition', {
    value: fn,
    configurable: true,
    writable: true,
  })
}

describe('TransitionLink', () => {
  afterEach(() => {
    // @ts-expect-error jsdom не реализует startViewTransition, тест сам его подставляет
    delete document.startViewTransition
  })

  it('навигирует без View Transitions API', () => {
    setStartViewTransition(undefined)
    renderApp()
    fireEvent.click(screen.getByRole('link', { name: 'Дальше' }))
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Следующая')
  })

  it('оборачивает навигацию в document.startViewTransition', () => {
    const start = vi.fn<StartViewTransition>((cb) => {
      cb()
      return {}
    })
    setStartViewTransition(start)
    renderApp()
    fireEvent.click(screen.getByRole('link', { name: 'Дальше' }))
    expect(start).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Следующая')
  })

  it('помечает <html> на время перехода и снимает метку по его окончании', async () => {
    let finish = () => {}
    const finished = new Promise<void>((resolve) => {
      finish = resolve
    })
    setStartViewTransition((cb) => {
      expect(document.documentElement).toHaveAttribute('data-nav-transition')
      cb()
      return { finished }
    })
    renderApp()
    fireEvent.click(screen.getByRole('link', { name: 'Дальше' }))
    expect(document.documentElement).toHaveAttribute('data-nav-transition')
    finish()
    await waitFor(() => expect(document.documentElement).not.toHaveAttribute('data-nav-transition'))
  })

  it('клик с metaKey отдаётся браузеру: без перехода и без preventDefault', () => {
    const start = vi.fn<StartViewTransition>(() => ({}))
    setStartViewTransition(start)
    renderApp()
    const link = screen.getByRole('link', { name: 'Дальше' })
    const event = createEvent.click(link, { metaKey: true, button: 0 })
    fireEvent(link, event)
    expect(start).not.toHaveBeenCalled()
    expect(event.defaultPrevented).toBe(false)
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Главная')
  })

  it('пользовательский onClick с preventDefault отменяет переход', () => {
    setStartViewTransition(undefined)
    render(
      <MemoryRouter>
        <Routes>
          <Route
            path="/"
            element={
              <TransitionLink to="/next" onClick={(e) => e.preventDefault()}>
                Дальше
              </TransitionLink>
            }
          />
          <Route path="/next" element={<h1>Следующая</h1>} />
        </Routes>
      </MemoryRouter>,
    )
    fireEvent.click(screen.getByRole('link', { name: 'Дальше' }))
    expect(screen.queryByRole('heading')).not.toBeInTheDocument()
  })
})
