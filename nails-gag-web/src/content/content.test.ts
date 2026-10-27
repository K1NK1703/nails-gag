import { describe, expect, it } from 'vitest'
import { faq } from './faq'
import { prices } from './prices'
import { reviews } from './reviews'
import { site } from './site'

describe('content', () => {
  it('has unique faq ids', () => {
    const ids = faq.map((q) => q.id)
    expect(new Set(ids).size).toBe(ids.length)
  })

  it('has exactly one primary price', () => {
    expect(prices.filter((p) => p.primary)).toHaveLength(1)
  })

  it('has at least three reviews', () => {
    expect(reviews.length).toBeGreaterThanOrEqual(3)
  })

  it('telegram link points to t.me', () => {
    expect(site.telegramUrl).toMatch(/^https:\/\/t\.me\//)
  })

  it('booking path is internal', () => {
    expect(site.bookingPath).toBe('/booking')
  })
})
