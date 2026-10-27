import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { components } from '../../api/schema'
import { ui } from '../../content/ui'
import { render as prerender } from '../../entry-server'
import { Gallery } from './Gallery'

vi.mock('../../lib/analytics', () => ({ track: vi.fn() }))
import { track } from '../../lib/analytics'

type PublicWork = components['schemas']['PublicWork']
type PagedPublicWork = components['schemas']['PagedPublicWork']

/** Ответ сервера на запрос: готовое тело страницы, код ошибки или ответ, который тест отдаст сам. */
type Reply = PagedPublicWork | number | Promise<Response>

function work(n: number): PublicWork {
  return {
    id: `work-${n}`,
    fileId: `file-${n}`,
    alt: `Работа ${n}`,
    caption: n % 2 === 0 ? `Подпись ${n}` : null,
    width: 1200,
    height: 1600,
  }
}

/** Страница списка (номера с нуля), как её отдаёт GET /api/portfolio. */
function page(number: number, total: number, size = 12): PagedPublicWork {
  const from = number * size
  const count = Math.max(0, Math.min(size, total - from))
  return {
    items: Array.from({ length: count }, (_, i) => work(from + i + 1)),
    page: number,
    size,
    total,
  }
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

/** Подменяет fetch: ответы — по очереди запросов. */
function serve(...replies: Reply[]) {
  const queue = [...replies]
  const fetchMock = vi.fn((input: RequestInfo | URL, _init?: RequestInit) => {
    const reply = queue.shift()
    if (reply === undefined) throw new Error(`unexpected request ${String(input)}`)
    if (typeof reply === 'number') {
      return Promise.resolve(json({ code: 'common.internal', status: reply }, reply))
    }
    if (reply instanceof Promise) return reply
    return Promise.resolve(json(reply))
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function requestedUrl(fetchMock: ReturnType<typeof serve>, call: number): string {
  return String(fetchMock.mock.calls[call][0])
}

function tiles() {
  return screen.queryAllByRole('button', { name: /открыть фото/i })
}

function placeholders() {
  return document.querySelectorAll('[data-placeholder]')
}

async function loaded(count: number) {
  await waitFor(() => expect(tiles()).toHaveLength(count))
}

function loadMoreButton() {
  return screen.getByRole('button', { name: ui.gallery.loadMore })
}

function queryLoadMoreButton() {
  return screen.queryByRole('button', { name: ui.gallery.loadMore })
}

beforeEach(() => {
  vi.mocked(track).mockClear()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('Gallery', () => {
  it('rendersFirstTwelveWorks', async () => {
    const fetchMock = serve(page(0, 30))
    render(<Gallery />)

    await loaded(12)

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(requestedUrl(fetchMock, 0)).toBe('/api/portfolio?page=0&size=12')
    expect(screen.getByRole('heading', { level: 2 })).toHaveTextContent('Работы')
    expect(screen.getByText(ui.galleryKicker(30))).toBeInTheDocument()
    const first = screen.getByAltText('Работа 1')
    expect(first).toHaveAttribute('src', '/api/portfolio/images/file-1/480.jpg')
    expect(first).toHaveAttribute(
      'srcset',
      '/api/portfolio/images/file-1/480.jpg 480w, /api/portfolio/images/file-1/960.jpg 960w, ' +
        '/api/portfolio/images/file-1/1600.jpg 1600w',
    )
    expect(first).toHaveAttribute(
      'sizes',
      '(min-width: 1264px) 280px, (min-width: 1024px) 25vw, (min-width: 768px) 33vw, 50vw',
    )
    expect(first).toHaveAttribute('width', '1200')
    expect(first).toHaveAttribute('height', '1600')
    expect(screen.getByAltText('Работа 2')).toHaveAttribute('loading', 'eager')
    expect(screen.getByAltText('Работа 3')).toHaveAttribute('loading', 'lazy')
    expect(tiles()[0]).toHaveAccessibleName('Открыть фото: Работа 1')
    expect(loadMoreButton()).toBeInTheDocument()
  })

  it('showsPlaceholdersWhileTheFirstPageLoads', async () => {
    let respond: (response: Response) => void = () => {}
    serve(new Promise<Response>((resolve) => (respond = resolve)))
    render(<Gallery />)

    expect(placeholders()).toHaveLength(12)
    expect(tiles()).toHaveLength(0)

    await act(async () => respond(json(page(0, 3))))

    expect(tiles()).toHaveLength(3)
    expect(placeholders()).toHaveLength(0)
  })

  it('loadMoreAppendsNextPageAndHidesAtTotal', async () => {
    const fetchMock = serve(page(0, 15), page(1, 15))
    render(<Gallery />)
    await loaded(12)

    fireEvent.click(loadMoreButton())
    await loaded(15)

    expect(requestedUrl(fetchMock, 1)).toBe('/api/portfolio?page=1&size=12')
    expect(queryLoadMoreButton()).toBeNull()
    expect(screen.getByAltText('Работа 15')).toBeInTheDocument()
    // Кнопка «Показать ещё» исчезла: фокус переходит на первую новую работу, а не теряется.
    expect(tiles()[12]).toHaveFocus()
  })

  it('ignoresSecondClickWhileNextPageLoads', async () => {
    let respond: (response: Response) => void = () => {}
    const fetchMock = serve(
      page(0, 40),
      new Promise<Response>((resolve) => (respond = resolve)),
      page(2, 40),
    )
    render(<Gallery />)
    await loaded(12)

    fireEvent.click(loadMoreButton())
    fireEvent.click(loadMoreButton())
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(placeholders()).toHaveLength(12)

    await act(async () => respond(json(page(1, 40))))
    expect(tiles()).toHaveLength(24)

    fireEvent.click(loadMoreButton())
    await loaded(36)
    expect(requestedUrl(fetchMock, 2)).toBe('/api/portfolio?page=2&size=12')
  })

  it('stopsWhenAPageAddsNothingNew', async () => {
    // Между запросами работы сдвинулись: вторая страница повторяет уже показанные.
    serve(page(0, 36), { ...page(0, 36), page: 1 })
    render(<Gallery />)
    await loaded(12)

    fireEvent.click(loadMoreButton())

    await waitFor(() => expect(queryLoadMoreButton()).toBeNull())
    expect(tiles()).toHaveLength(12)
  })

  it('showsErrorAndRetries', async () => {
    const fetchMock = serve(500, page(0, 5))
    render(<Gallery />)

    expect(await screen.findByRole('alert')).toHaveTextContent(ui.gallery.loadError)
    expect(tiles()).toHaveLength(0)
    expect(placeholders()).toHaveLength(0)

    fireEvent.click(screen.getByRole('button', { name: ui.gallery.retry }))
    await loaded(5)

    expect(requestedUrl(fetchMock, 1)).toBe('/api/portfolio?page=0&size=12')
    expect(screen.queryByRole('alert')).toBeNull()
    expect(queryLoadMoreButton()).toBeNull()
    expect(tiles()[0]).toHaveFocus()
  })

  it('keepsLoadedWorksWhenLoadMoreFails', async () => {
    const fetchMock = serve(page(0, 20), 503, page(1, 20))
    render(<Gallery />)
    await loaded(12)

    fireEvent.click(loadMoreButton())

    expect(await screen.findByRole('alert')).toHaveTextContent(ui.gallery.loadError)
    expect(tiles()).toHaveLength(12)
    const retry = screen.getByRole('button', { name: ui.gallery.retry })
    expect(retry).toHaveFocus()

    fireEvent.click(retry)
    await loaded(20)

    expect(requestedUrl(fetchMock, 2)).toBe('/api/portfolio?page=1&size=12')
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('showsCalmEmptyState', async () => {
    serve(page(0, 0))
    render(<Gallery />)

    expect(await screen.findByText(ui.gallery.empty, { exact: false })).toBeInTheDocument()
    expect(screen.queryByRole('alert')).toBeNull()
    expect(queryLoadMoreButton()).toBeNull()
    expect(placeholders()).toHaveLength(0)
    expect(screen.getByText(ui.gallery.kicker)).toBeInTheDocument()
  })

  it('lightboxBrowsesLoadedWorks', async () => {
    serve(page(0, 15))
    render(<Gallery />)
    await loaded(12)

    const last = tiles()[11]
    fireEvent.click(last)

    const dialog = screen.getByRole('dialog')
    expect(dialog).toHaveAccessibleName('Работа 12')
    expect(track).toHaveBeenCalledWith({ name: 'gallery_open', index: 11 })
    expect(screen.getByTestId('lightbox-dots').children).toHaveLength(12)
    expect(dialog.querySelector('img[alt="Работа 12"]')).toHaveAttribute(
      'src',
      '/api/portfolio/images/file-12/1600.jpg',
    )

    // Листает только загруженные: после двенадцатой — снова первая, а не тринадцатая.
    fireEvent.keyDown(window, { key: 'ArrowRight' })
    expect(screen.getByRole('dialog')).toHaveAccessibleName('Работа 1')

    fireEvent.keyDown(window, { key: 'Escape' })
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(last).toHaveFocus()
  })

  it('abortsTheRequestOnUnmount', async () => {
    const fetchMock = serve(new Promise<Response>(() => {}))
    const { unmount } = render(<Gallery />)
    await waitFor(() => expect(fetchMock).toHaveBeenCalled())

    unmount()

    expect(fetchMock.mock.calls[0][1]?.signal?.aborted).toBe(true)
  })

  it('prerenderHasHeadingWithoutFetch', () => {
    const fetchMock = serve()

    const { html } = prerender('/', 'https://nails-gag.test')

    expect(fetchMock).not.toHaveBeenCalled()
    expect(html).toContain('id="gallery"')
    expect(html).toMatch(/<h2[^>]*>Работы<\/h2>/)
    expect(html).not.toContain('/api/portfolio/images/')
  })
})
