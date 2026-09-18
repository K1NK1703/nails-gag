import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { gallery } from '../../content/gallery'
import { Gallery } from './Gallery'

vi.mock('../../lib/analytics', () => ({ track: vi.fn() }))
import { track } from '../../lib/analytics'

describe('Gallery', () => {
  it('renders all photos from content', () => {
    render(<Gallery />)
    expect(screen.getAllByRole('button', { name: /открыть фото/i })).toHaveLength(gallery.length)
  })

  it('opens lightbox on click and tracks the index', () => {
    render(<Gallery />)
    fireEvent.click(screen.getAllByRole('button', { name: /открыть фото/i })[2])
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(track).toHaveBeenCalledWith({ name: 'gallery_open', index: 2 })
  })

  it('returns focus to the tile after closing', () => {
    render(<Gallery />)
    const tile = screen.getAllByRole('button', { name: /открыть фото/i })[2]
    fireEvent.click(tile)
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(tile).toHaveFocus()
  })
})
