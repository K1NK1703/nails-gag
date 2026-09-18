import { describe, expect, it } from 'vitest'
import { faq } from './faq'
import { gallery } from './gallery'
import { prices } from './prices'
import { reviews } from './reviews'
import { site } from './site'

describe('content', () => {
  it('has unique gallery slugs', () => {
    const slugs = gallery.map((p) => p.slug)
    expect(new Set(slugs).size).toBe(slugs.length)
  })

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

  it('booking link points to telegram', () => {
    expect(site.bookingUrl).toMatch(/^https:\/\/t\.me\//)
  })
})
