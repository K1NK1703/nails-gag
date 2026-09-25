import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AppRoutes } from './App'

vi.mock('./lib/analytics', () => ({ track: vi.fn() }))
import { track } from './lib/analytics'

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AppRoutes />
    </MemoryRouter>,
  )
}

describe('routes', () => {
  afterEach(() => vi.unstubAllEnvs())

  it('renders home with all sections', () => {
    renderAt('/')
    for (const id of ['hero', 'gallery', 'price', 'about', 'reviews', 'faq', 'contacts']) {
      expect(document.getElementById(id)).not.toBeNull()
    }
  })

  it('renders privacy page', () => {
    renderAt('/privacy')
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(
      'Политика конфиденциальности',
    )
  })

  it('renders booking stub page', () => {
    renderAt('/booking')
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Онлайн-запись')
    expect(document.title).toBe('Онлайн-запись — nails.gag')
  })

  it('sets document title per route', () => {
    renderAt('/privacy')
    expect(document.title).toContain('Политика конфиденциальности')
    cleanup()
    renderAt('/')
    expect(document.title).toContain('маникюр')
  })

  it('tracks page_view with the current path', () => {
    renderAt('/privacy')
    expect(track).toHaveBeenCalledWith({ name: 'page_view', path: '/privacy' })
  })

  it('renders 404 for cabinet when flag is off', () => {
    vi.stubEnv('VITE_FEATURE_CABINET', '')
    renderAt('/cabinet')
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Страница не найдена')
  })

  it('renders cabinet stub when flag is on', () => {
    vi.stubEnv('VITE_FEATURE_CABINET', 'true')
    renderAt('/cabinet')
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Личный кабинет')
  })
})
