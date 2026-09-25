import { createEvent, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { BrowserRouter, MemoryRouter, Route, Routes } from 'react-router'
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

function renderBrowserApp(useTransitions?: boolean) {
  return render(
    <BrowserRouter useTransitions={useTransitions}>
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
    </BrowserRouter>,
  )
}

describe('TransitionLink', () => {
  afterEach(() => {
    // @ts-expect-error jsdom не реализует startViewTransition, тест сам его подставляет
    delete document.startViewTransition
    delete document.documentElement.dataset['navTransition']
    window.history.replaceState(null, '', '/')
  })

  it('с настоящим BrowserRouter (useTransitions={false}) новый маршрут уже в DOM внутри колбэка перехода', () => {
    let headingInsideCallback: string | null | undefined
    setStartViewTransition((cb) => {
      cb()
      headingInsideCallback = document.querySelector('h1')?.textContent
      return { finished: Promise.resolve() }
    })
    renderBrowserApp(false)
    fireEvent.click(screen.getByRole('link', { name: 'Дальше' }))
    expect(headingInsideCallback).toBe('Следующая')
    expect(window.location.pathname).toBe('/next')
  })

  it('метку снимает только последний переход, а не вытесненный им', async () => {
    const finishers: Array<() => void> = []
    setStartViewTransition((cb) => {
      cb()
      const finished = new Promise<void>((resolve) => finishers.push(resolve))
      return { finished }
    })
    render(
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route path="/" element={<TransitionLink to="/next">Дальше</TransitionLink>} />
          <Route path="/next" element={<TransitionLink to="/">Назад</TransitionLink>} />
        </Routes>
      </MemoryRouter>,
    )
    fireEvent.click(screen.getByRole('link', { name: 'Дальше' }))
    fireEvent.click(screen.getByRole('link', { name: 'Назад' }))
    expect(finishers).toHaveLength(2)

    finishers[0]?.()
    await Promise.resolve()
    await Promise.resolve()
    expect(document.documentElement).toHaveAttribute('data-nav-transition')

    finishers[1]?.()
    await waitFor(() => expect(document.documentElement).not.toHaveAttribute('data-nav-transition'))
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
