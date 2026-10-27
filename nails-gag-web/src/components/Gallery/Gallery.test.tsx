import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { StrictMode } from 'react'
import { hydrateRoot, type Root } from 'react-dom/client'
import { renderToString } from 'react-dom/server'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { components } from '../../api/schema'
import { ui } from '../../content/ui'
import { render as prerender } from '../../entry-server'
import { Gallery } from './Gallery'

vi.mock('../../lib/analytics', () => ({ track: vi.fn() }))
import { track } from '../../lib/analytics'

type PublicWork = components['schemas']['PublicWork']
type PagedPublicWork = components['schemas']['PagedPublicWork']

/** Ответ сервера на запрос: тело страницы, код ошибки или ответ, который тест отдаст сам. */
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

/** Работы с номерами from..to включительно. */
function works(from: number, to: number): PublicWork[] {
  return Array.from({ length: to - from + 1 }, (_, i) => work(from + i))
}

/** Страница списка (номера с нуля), как её отдаёт GET /api/portfolio. */
function page(number: number, total: number, size = 12): PagedPublicWork {
  const from = number * size
  const count = Math.max(0, Math.min(size, total - from))
  return { items: works(from + 1, from + count), page: number, size, total }
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

/** Ответ, который тест отдаёт сам, когда нужно посмотреть на раздел во время загрузки. */
function deferred() {
  let resolve: (response: Response) => void = () => {}
  const promise = new Promise<Response>((r) => (resolve = r))
  return { promise, respond: (body: PagedPublicWork) => act(async () => resolve(json(body))) }
}

/** Ответы, которые тест ещё не раздал, и запросы сверх них: после теста оба списка пусты. */
let pending: Reply[] = []
let unexpected: string[] = []

/** Подменяет fetch: ответы — по очереди запросов. */
function serve(...replies: Reply[]) {
  pending = [...replies]
  const fetchMock = vi.fn((input: RequestInfo | URL, _init?: RequestInit) => {
    const reply = pending.shift()
    if (reply === undefined) {
      unexpected.push(String(input))
      return Promise.reject(new TypeError(`unexpected request ${String(input)}`))
    }
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

function placeholders(root: ParentNode = document) {
  return root.querySelectorAll('[data-placeholder]')
}

function grid() {
  return document.querySelector('#gallery ul')
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

/** Активация с клавиатуры (Enter, пробел): кнопка в фокусе, у click detail === 0. */
function press(button: HTMLElement) {
  button.focus()
  fireEvent.click(button, { detail: 0 })
}

/** Клик мышью: кнопка в фокусе, у click detail === 1. */
function clickWithMouse(button: HTMLElement) {
  button.focus()
  fireEvent.click(button, { detail: 1 })
}

/** Живой регион ошибки: есть в разметке всегда, меняется только текст. */
function errorRegion() {
  return screen.getByRole('status')
}

async function failedToLoad() {
  await waitFor(() => expect(errorRegion()).toHaveTextContent(ui.gallery.loadError))
  return errorRegion()
}

beforeEach(() => {
  vi.mocked(track).mockClear()
  pending = []
  unexpected = []
})

afterEach(() => {
  expect(unexpected).toEqual([])
  expect(pending).toEqual([])
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('Gallery', () => {
  it('rendersFirstTwelveWorks', async () => {
    const fetchMock = serve(page(0, 30))
    render(<Gallery />)

    await loaded(12)

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(requestedUrl(fetchMock, 0)).toBe('/api/portfolio?page=0&size=12')
    expect(screen.getByRole('heading', { level: 2 })).toHaveTextContent('Работы')
    expect(screen.getByText(ui.gallery.kicker(30))).toBeInTheDocument()
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
    // Работы приходят после загрузки страницы: их картинки не спорят с первым экраном.
    for (const img of grid()!.querySelectorAll('img')) {
      expect(img).toHaveAttribute('loading', 'lazy')
      expect(img).toHaveAttribute('decoding', 'async')
    }
    expect(tiles()[0]).toHaveAccessibleName('Открыть фото: Работа 1')
    expect(loadMoreButton()).toBeInTheDocument()
    expect(grid()).not.toHaveAttribute('aria-busy')
  })

  it('showsPlaceholdersWhileTheFirstPageLoads', async () => {
    const first = deferred()
    serve(first.promise)
    render(<Gallery />)

    expect(placeholders()).toHaveLength(12)
    expect(grid()).toHaveAttribute('aria-busy', 'true')
    expect(tiles()).toHaveLength(0)

    await first.respond(page(0, 3))

    expect(tiles()).toHaveLength(3)
    expect(placeholders()).toHaveLength(0)
  })

  it('loadMoreAppendsNextPageAndHidesAtTotal', async () => {
    const fetchMock = serve(page(0, 15), page(1, 15))
    render(<Gallery />)
    await loaded(12)

    press(loadMoreButton())
    await loaded(15)

    expect(requestedUrl(fetchMock, 1)).toBe('/api/portfolio?page=1&size=12')
    expect(queryLoadMoreButton()).toBeNull()
    expect(screen.getByAltText('Работа 15')).toBeInTheDocument()
    // Кнопка «Показать ещё» исчезла: фокус переходит на первую новую работу, а не теряется.
    expect(tiles()[12]).toHaveFocus()
  })

  it('movesFocusToNewWorksOnlyForKeyboardActivation', async () => {
    serve(page(0, 40), page(1, 40), page(2, 40))
    render(<Gallery />)
    await loaded(12)

    // Мышью: фокус остаётся на кнопке, страница не прыгает к новым работам.
    clickWithMouse(loadMoreButton())
    await loaded(24)
    expect(loadMoreButton()).toHaveFocus()

    // С клавиатуры: фокус — на первой новой работе, чтобы продолжить с неё.
    press(loadMoreButton())
    await loaded(36)
    expect(tiles()[24]).toHaveFocus()
  })

  it('loadsExactlyTwentyFourOfTwentyFour', async () => {
    const fetchMock = serve(page(0, 24), page(1, 24))
    render(<Gallery />)
    await loaded(12)

    press(loadMoreButton())
    await loaded(24)

    expect(queryLoadMoreButton()).toBeNull()
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('marksTheButtonAndGridWhileTheNextPageLoads', async () => {
    const next = deferred()
    serve(page(0, 40), next.promise)
    render(<Gallery />)
    await loaded(12)

    press(loadMoreButton())

    expect(loadMoreButton()).toHaveAttribute('aria-disabled', 'true')
    expect(loadMoreButton()).toHaveFocus()
    expect(grid()).toHaveAttribute('aria-busy', 'true')
    expect(placeholders()).toHaveLength(12)

    await next.respond(page(1, 40))

    expect(loadMoreButton()).not.toHaveAttribute('aria-disabled')
    expect(grid()).not.toHaveAttribute('aria-busy')
  })

  it('ignoresSecondClickWhileNextPageLoads', async () => {
    const next = deferred()
    const fetchMock = serve(page(0, 40), next.promise, page(2, 40))
    render(<Gallery />)
    await loaded(12)

    press(loadMoreButton())
    press(loadMoreButton())
    expect(fetchMock).toHaveBeenCalledTimes(2)

    await next.respond(page(1, 40))
    expect(tiles()).toHaveLength(24)

    press(loadMoreButton())
    await loaded(36)
    expect(requestedUrl(fetchMock, 2)).toBe('/api/portfolio?page=2&size=12')
  })

  it('appendsOnlyNewWorksWhenPagesOverlap', async () => {
    // Мастер убрал одну работу: вторая страница начинается с уже показанной двенадцатой.
    serve(page(0, 36), { items: works(12, 23), page: 1, size: 12, total: 35 })
    render(<Gallery />)
    await loaded(12)

    press(loadMoreButton())
    await loaded(23)

    expect(screen.getAllByAltText('Работа 12')).toHaveLength(1)
    expect(loadMoreButton()).toBeInTheDocument()
    expect(tiles()[12]).toHaveFocus()
  })

  it('stopsWhenAPageAddsNothingNew', async () => {
    // Между запросами работы сдвинулись: вторая страница повторяет уже показанные.
    serve(page(0, 36), { ...page(0, 36), page: 1 })
    render(<Gallery />)
    await loaded(12)

    press(loadMoreButton())

    await waitFor(() => expect(queryLoadMoreButton()).toBeNull())
    expect(tiles()).toHaveLength(12)
    expect(errorRegion()).toBeEmptyDOMElement()
    // Кнопка исчезла, новых работ нет: фокус — на последней работе, а не на body.
    expect(tiles()[11]).toHaveFocus()
  })

  it('showsErrorAndRetries', async () => {
    const retried = deferred()
    const fetchMock = serve(500, retried.promise)
    render(<Gallery />)

    const region = await failedToLoad()
    expect(tiles()).toHaveLength(0)
    // Пустой сетки после ошибки нет: только текст и кнопка.
    expect(grid()).toBeNull()
    const retry = screen.getByRole('button', { name: ui.gallery.retry })
    expect(retry).toHaveAccessibleDescription(ui.gallery.loadError)

    press(retry)

    // На время повтора кнопка та же и с фокусом; регион тот же, текст ошибки убран.
    expect(retry).toHaveAttribute('aria-disabled', 'true')
    expect(retry).toHaveFocus()
    expect(errorRegion()).toBe(region)
    expect(region).toBeEmptyDOMElement()

    await retried.respond(page(0, 5))

    expect(tiles()).toHaveLength(5)
    expect(requestedUrl(fetchMock, 1)).toBe('/api/portfolio?page=0&size=12')
    expect(queryLoadMoreButton()).toBeNull()
    expect(tiles()[0]).toHaveFocus()
  })

  it('keepsLoadedWorksWhenLoadMoreFails', async () => {
    const fetchMock = serve(page(0, 20), 503, page(1, 20))
    render(<Gallery />)
    await loaded(12)
    const button = loadMoreButton()

    const region = errorRegion()
    expect(region).toBeEmptyDOMElement()

    press(button)

    // Ошибка появляется текстом в том же регионе, который был в разметке до неё.
    expect(await failedToLoad()).toBe(region)
    expect(tiles()).toHaveLength(12)
    // Та же кнопка стала «Повторить» и не потеряла фокус.
    expect(button).toHaveTextContent(ui.gallery.retry)
    expect(button).toHaveFocus()

    press(button)
    expect(region).toBeEmptyDOMElement()
    await loaded(20)

    expect(requestedUrl(fetchMock, 2)).toBe('/api/portfolio?page=1&size=12')
    expect(errorRegion()).toBe(region)
    expect(region).toBeEmptyDOMElement()
    expect(tiles()[12]).toHaveFocus()
  })

  it('survivesAResponseOfUnexpectedShape', async () => {
    serve({ page: 0 } as unknown as PagedPublicWork, page(0, 2))
    render(<Gallery />)

    await failedToLoad()
    expect(placeholders()).toHaveLength(0)

    press(screen.getByRole('button', { name: ui.gallery.retry }))
    await loaded(2)
  })

  it('showsCalmEmptyState', async () => {
    serve(page(0, 0))
    render(<Gallery />)

    expect(await screen.findByText(ui.gallery.empty, { exact: false })).toBeInTheDocument()
    expect(errorRegion()).toBeEmptyDOMElement()
    expect(queryLoadMoreButton()).toBeNull()
    expect(placeholders()).toHaveLength(0)
    expect(screen.getByText(ui.gallery.kicker(0))).toBeInTheDocument()
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

  it('leavesFocusInTheLightboxWhenAPageArrives', async () => {
    const next = deferred()
    serve(page(0, 40), next.promise)
    render(<Gallery />)
    await loaded(12)

    press(loadMoreButton())
    fireEvent.click(tiles()[0])
    const close = screen.getByRole('button', { name: /закрыть/i })
    expect(close).toHaveFocus()

    await next.respond(page(1, 40))

    expect(tiles()).toHaveLength(24)
    expect(close).toHaveFocus()
  })

  it('abortsTheRequestOnUnmount', async () => {
    const fetchMock = serve(new Promise<Response>(() => {}))
    const { unmount } = render(<Gallery />)
    await waitFor(() => expect(fetchMock).toHaveBeenCalled())

    unmount()

    expect(fetchMock.mock.calls[0][1]?.signal?.aborted).toBe(true)
  })

  it('requestsAgainInStrictModeAndKeepsOnePage', async () => {
    const fetchMock = serve(page(0, 30), page(0, 30))
    render(
      <StrictMode>
        <Gallery />
      </StrictMode>,
    )

    await loaded(12)

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(fetchMock.mock.calls[0][1]?.signal?.aborted).toBe(true)
    expect(fetchMock.mock.calls[1][1]?.signal?.aborted).toBe(false)
    expect(tiles()).toHaveLength(12)
  })

  it('hydratesThePrerenderWithoutMismatch', async () => {
    const html = renderToString(<Gallery />)
    serve(page(0, 5))
    const errors = vi.spyOn(console, 'error').mockImplementation(() => {})
    const recoverable = vi.fn()
    const container = document.createElement('div')
    container.innerHTML = html
    document.body.append(container)
    let root: Root | undefined
    try {
      root = await act(async () =>
        hydrateRoot(container, <Gallery />, { onRecoverableError: recoverable }),
      )
      await loaded(5)

      expect(recoverable).not.toHaveBeenCalled()
      expect(errors).not.toHaveBeenCalled()
    } finally {
      errors.mockRestore()
      const hydrated = root
      if (hydrated) act(() => hydrated.unmount())
      container.remove()
    }
  })

  it('prerenderHasHeadingWithoutFetch', () => {
    const fetchMock = serve()

    const { html } = prerender('/', 'https://nails-gag.test')

    expect(fetchMock).not.toHaveBeenCalled()
    const section = document.createElement('div')
    section.innerHTML = /<section id="gallery"[\s\S]*?<\/section>/.exec(html)?.[0] ?? ''
    expect(section.querySelector('h2')).toHaveTextContent('Работы')
    expect(placeholders(section)).toHaveLength(12)
    expect(section.querySelector('ul')).toHaveAttribute('aria-busy', 'true')
    expect(section.querySelector('noscript')?.innerHTML).toContain(ui.gallery.noScript)
    // Живой регион ошибки уже в пререндере, пустой: ошибку объявят, когда в нём появится текст.
    expect(section.querySelector('output')).toBeEmptyDOMElement()
    expect(html).not.toContain('/api/portfolio/images/')
  })
})
