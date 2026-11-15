import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { track } from './analytics'

describe('track', () => {
  const sendBeacon = vi.fn(() => true)

  beforeEach(() => {
    Object.defineProperty(navigator, 'sendBeacon', { value: sendBeacon, configurable: true })
  })
  afterEach(() => vi.unstubAllEnvs())

  it('does nothing without endpoint', () => {
    vi.stubEnv('VITE_ANALYTICS_ENDPOINT', '')
    track({ name: 'page_view', path: '/' })
    expect(sendBeacon).not.toHaveBeenCalled()
  })

  it('sends a beacon with the event when endpoint is set', () => {
    vi.stubEnv('VITE_ANALYTICS_ENDPOINT', 'https://example.test/e')
    track({ name: 'cta_click', place: 'hero' })
    expect(sendBeacon).toHaveBeenCalledTimes(1)
    const [url, body] = sendBeacon.mock.calls[0] as unknown as [string, Blob]
    expect(url).toBe('https://example.test/e')
    expect(body).toBeInstanceOf(Blob)
  })

  it('sends entry and referrer of page_view as JSON fields', async () => {
    vi.stubEnv('VITE_ANALYTICS_ENDPOINT', '/api/analytics/events')
    track({ name: 'page_view', path: '/', entry: true, referrer: 'yandex.ru' })
    const [url, body] = sendBeacon.mock.lastCall as unknown as [string, Blob]
    expect(url).toBe('/api/analytics/events')
    expect(body.type).toBe('application/json')
    expect(JSON.parse(await body.text())).toMatchObject({
      name: 'page_view',
      path: '/',
      entry: true,
      referrer: 'yandex.ru',
    })
  })

  it('does nothing without endpoint, even for the entry page_view', () => {
    vi.stubEnv('VITE_ANALYTICS_ENDPOINT', '')
    sendBeacon.mockClear()
    track({ name: 'page_view', path: '/', entry: true, referrer: '' })
    expect(sendBeacon).not.toHaveBeenCalled()
  })
})
