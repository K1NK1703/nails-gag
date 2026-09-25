import { afterEach, describe, expect, it, vi } from 'vitest'
import { isCabinetEnabled } from './features'

describe('features', () => {
  afterEach(() => vi.unstubAllEnvs())

  it('cabinet is on by default', () => {
    vi.stubEnv('VITE_FEATURE_CABINET', '')
    expect(isCabinetEnabled()).toBe(true)
  })

  it('cabinet is off when flag is "false"', () => {
    vi.stubEnv('VITE_FEATURE_CABINET', 'false')
    expect(isCabinetEnabled()).toBe(false)
  })
})
