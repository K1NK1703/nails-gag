import { describe, expect, it } from 'vitest'
import { plural } from './plural'

const forms: [string, string, string] = ['отзыв', 'отзыва', 'отзывов']

describe('plural', () => {
  it.each([
    [0, 'отзывов'],
    [1, 'отзыв'],
    [2, 'отзыва'],
    [5, 'отзывов'],
    [11, 'отзывов'],
    [12, 'отзывов'],
    [21, 'отзыв'],
    [104, 'отзыва'],
  ])('%i → %s', (n, expected) => {
    expect(plural(n, forms)).toBe(expected)
  })
})
