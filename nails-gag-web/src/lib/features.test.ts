import { afterEach, describe, expect, it, vi } from 'vitest'
import { isCabinetEnabled } from './features'

describe('features', () => {
  afterEach(() => vi.unstubAllEnvs())

  it('cabinet is off by default', () => {
    vi.stubEnv('VITE_FEATURE_CABINET', '')
    expect(isCabinetEnabled()).toBe(false)
  })

  it('cabinet is on when flag is "true"', () => {
    vi.stubEnv('VITE_FEATURE_CABINET', 'true')
    expect(isCabinetEnabled()).toBe(true)
  })
})
