import { afterEach, describe, expect, it, vi } from 'vitest'
import { useAuth } from './auth'

describe('useAuth', () => {
  afterEach(() => vi.unstubAllEnvs())

  it('без VITE_PREVIEW_USER пользователь — гость', () => {
    vi.stubEnv('VITE_PREVIEW_USER', '')
    expect(useAuth()).toEqual({ user: null })
  })

  it('VITE_PREVIEW_USER подставляет пользователя для превью дизайна', () => {
    vi.stubEnv('VITE_PREVIEW_USER', 'Алина')
    expect(useAuth()).toEqual({ user: { name: 'Алина' } })
  })
})
