import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { reviews } from '../../content/reviews'
import { Reviews } from './Reviews'

describe('Reviews', () => {
  it('renders every review and marks examples', () => {
    render(<Reviews />)
    for (const r of reviews) expect(screen.getByText(r.text)).toBeInTheDocument()
    const examples = reviews.filter((r) => r.isExample).length
    expect(screen.queryAllByText('пример')).toHaveLength(examples)
  })
})
