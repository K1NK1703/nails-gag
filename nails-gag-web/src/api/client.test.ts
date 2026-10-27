import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, apiGet } from './client'

function respond(response: Response) {
  const fetchMock = vi.fn((_input: RequestInfo | URL, _init?: RequestInit) =>
    Promise.resolve(response),
  )
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('apiGet', () => {
  it('encodes query parameters and skips undefined and null', async () => {
    const fetchMock = respond(json({ items: [], page: 0, size: 20, total: 0 }))

    await apiGet('/api/clients', {
      query: { query: 'Анна & Ко', page: 0, size: undefined },
    })

    const url = String(fetchMock.mock.calls[0][0])
    expect(url).toBe('/api/clients?query=%D0%90%D0%BD%D0%BD%D0%B0+%26+%D0%9A%D0%BE&page=0')
    expect(new URLSearchParams(url.split('?')[1]).get('query')).toBe('Анна & Ко')
  })

  it('omits the question mark without parameters', async () => {
    const fetchMock = respond(json({ items: [], page: 0, size: 12, total: 0 }))

    // null — как значение из формы: в строку запроса не попадает (тип его не допускает).
    await apiGet('/api/portfolio', { query: { page: null as unknown as undefined } })

    expect(String(fetchMock.mock.calls[0][0])).toBe('/api/portfolio')
    expect(fetchMock.mock.calls[0][1]?.headers).toEqual({ Accept: 'application/json' })
  })

  it('returns the parsed body', async () => {
    const body = { items: [], page: 1, size: 12, total: 12 }
    respond(json(body))

    await expect(apiGet('/api/portfolio', { query: { page: 1 } })).resolves.toEqual(body)
  })

  it('throws ApiError with status and problem code', async () => {
    respond(json({ code: 'common.rate_limited', status: 429 }, 429))

    const error = await apiGet('/api/portfolio').catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 429, code: 'common.rate_limited' })
  })

  it('throws ApiError without code when the error body is not a problem', async () => {
    respond(new Response('<html>Bad Gateway</html>', { status: 502 }))

    const error = await apiGet('/api/portfolio').catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 502, code: undefined })
  })

  it('names the path when a successful response is not JSON', async () => {
    respond(new Response('<html>index</html>', { status: 200 }))

    await expect(apiGet('/api/portfolio')).rejects.toThrow(
      'GET /api/portfolio: response is not JSON',
    )
  })

  it('passes the signal to fetch and rejects when it is aborted', async () => {
    const fetchMock = vi.fn((_input: RequestInfo | URL, init?: RequestInit) => {
      return new Promise<Response>((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () =>
          reject(new DOMException('aborted', 'AbortError')),
        )
      })
    })
    vi.stubGlobal('fetch', fetchMock)
    const controller = new AbortController()

    const request = apiGet('/api/portfolio', { signal: controller.signal })
    controller.abort()

    await expect(request).rejects.toMatchObject({ name: 'AbortError' })
    expect(fetchMock.mock.calls[0][1]?.signal).toBe(controller.signal)
  })

  it('accepts only GET paths with a JSON response and no path parameters', () => {
    // Проверка типов: tsc -b в npm run build падает, если ошибка ниже исчезнет.
    function invalidCalls() {
      // @ts-expect-error у адреса нет GET
      void apiGet('/api/services/order')
      // @ts-expect-error у GET есть параметр пути
      void apiGet('/api/clients/{id}')
      // @ts-expect-error обязательные параметры строки запроса не переданы
      void apiGet('/api/schedule/free-slots')
    }
    expect(invalidCalls).toBeTypeOf('function')
  })
})
