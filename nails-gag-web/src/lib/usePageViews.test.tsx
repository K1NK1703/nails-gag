import { act, render } from '@testing-library/react'
import { StrictMode, useEffect } from 'react'
import { MemoryRouter, useNavigate } from 'react-router'
import type { NavigateFunction } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPageViewTracker, usePageViews } from './usePageViews'
import type { PageViewTracker } from './usePageViews'

vi.mock('./analytics', () => ({ track: vi.fn() }))
import { track } from './analytics'

const trackMock = vi.mocked(track)

function setReferrer(value: string) {
  Object.defineProperty(document, 'referrer', { value, configurable: true })
}

describe('createPageViewTracker', () => {
  beforeEach(() => trackMock.mockClear())
  afterEach(() => setReferrer(''))

  it('первый просмотр — вход: entry и только хост источника, без пути и параметров', () => {
    setReferrer('https://www.yandex.ru:8443/search/?text=маникюр#top')
    const pageView = createPageViewTracker()
    pageView('/')
    expect(trackMock).toHaveBeenCalledExactlyOnceWith({
      name: 'page_view',
      path: '/',
      entry: true,
      referrer: 'www.yandex.ru',
    })
  })

  it('следующие просмотры — без entry и referrer', () => {
    setReferrer('https://yandex.ru/')
    const pageView = createPageViewTracker()
    pageView('/')
    pageView('/terms')
    pageView('/')
    expect(trackMock.mock.calls.map(([event]) => event)).toEqual([
      { name: 'page_view', path: '/', entry: true, referrer: 'yandex.ru' },
      { name: 'page_view', path: '/terms' },
      { name: 'page_view', path: '/' },
    ])
  })

  it('пустой document.referrer — referrer пустой строкой', () => {
    setReferrer('')
    createPageViewTracker()('/booking')
    expect(trackMock).toHaveBeenCalledWith({
      name: 'page_view',
      path: '/booking',
      entry: true,
      referrer: '',
    })
  })

  it('свой сайт в источнике отправляется как есть: direct из него делает бэкенд', () => {
    setReferrer('https://nails-gag.ru/privacy')
    createPageViewTracker()('/')
    expect(trackMock).toHaveBeenCalledWith(expect.objectContaining({ referrer: 'nails-gag.ru' }))
  })

  it.each(['/cabinet', '/admin', '/admin/schedule', '/no-such-page', '/booking/x'])(
    '%s не отправляется',
    (path) => {
      createPageViewTracker()(path)
      expect(trackMock).not.toHaveBeenCalled()
    },
  )

  it('вход получает первый отправленный просмотр, а не первый путь после загрузки', () => {
    setReferrer('https://t.me/')
    const pageView = createPageViewTracker()
    pageView('/cabinet')
    pageView('/')
    expect(trackMock).toHaveBeenCalledExactlyOnceWith({
      name: 'page_view',
      path: '/',
      entry: true,
      referrer: 't.me',
    })
  })

  it('путь приводится к виду маршрута: без завершающего слеша и регистра, как сопоставляет роутер', () => {
    const pageView = createPageViewTracker()
    pageView('/Terms/')
    pageView('/privacy//')
    expect(trackMock.mock.calls.map(([event]) => event.name === 'page_view' && event.path)).toEqual(
      ['/terms', '/privacy'],
    )
  })
})

describe('usePageViews', () => {
  const router: { navigate?: NavigateFunction } = {}

  function Probe({ pageView }: { pageView: PageViewTracker }) {
    usePageViews(pageView)
    const navigate = useNavigate()
    useEffect(() => {
      router.navigate = navigate
    }, [navigate])
    return null
  }

  function renderAt(path: string, pageView: PageViewTracker, strict = false) {
    const tree = (
      <MemoryRouter initialEntries={[path]}>
        <Probe pageView={pageView} />
      </MemoryRouter>
    )
    return render(strict ? <StrictMode>{tree}</StrictMode> : tree)
  }

  const go = (to: string) => act(() => router.navigate!(to))

  afterEach(() => setReferrer(''))

  it('отправляет просмотр при каждой смене пути, но не при смене хэша', async () => {
    const pageView = vi.fn<PageViewTracker>()
    renderAt('/', pageView)
    await go('/#faq')
    await go('/terms')
    await go('/')
    expect(pageView.mock.calls).toEqual([['/'], ['/terms'], ['/']])
  })

  it('StrictMode не задваивает просмотр и не даёт второго входа', async () => {
    trackMock.mockClear()
    setReferrer('https://google.com/')
    renderAt('/', createPageViewTracker(), true)
    await go('/privacy')
    expect(trackMock.mock.calls.map(([event]) => event)).toEqual([
      { name: 'page_view', path: '/', entry: true, referrer: 'google.com' },
      { name: 'page_view', path: '/privacy' },
    ])
  })
})
