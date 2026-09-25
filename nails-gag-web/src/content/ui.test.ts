import { describe, expect, it } from 'vitest'
import { ui } from './ui'

describe('ui', () => {
  it('склоняет счётчик отзывов', () => {
    expect(ui.reviewsKicker(1)).toBe('1 отзыв')
    expect(ui.reviewsKicker(4)).toBe('4 отзыва')
    expect(ui.reviewsKicker(11)).toBe('11 отзывов')
  })

  it('собирает подпись портфолио', () => {
    expect(ui.galleryKicker(11)).toBe('Портфолио · 11 фото')
  })
})
