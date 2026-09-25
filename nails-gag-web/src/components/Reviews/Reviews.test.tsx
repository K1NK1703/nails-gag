import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { reviews } from '../../content/reviews'
import { plural } from '../../lib/plural'
import { Reviews } from './Reviews'

describe('Reviews', () => {
  it('renders every review and marks examples', () => {
    render(<Reviews />)
    for (const r of reviews) expect(screen.getByText(r.text)).toBeInTheDocument()
    const examples = reviews.filter((r) => r.isExample).length
    expect(screen.queryAllByText('пример')).toHaveLength(examples)
  })

  it('kicker states the review count', () => {
    render(<Reviews />)
    const word = plural(reviews.length, ['отзыв', 'отзыва', 'отзывов'])
    expect(screen.getByText(`${reviews.length} ${word}`)).toBeInTheDocument()
  })
})
