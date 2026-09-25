import { describe, expect, it } from 'vitest'
import { canHydrate, normalizePath } from './hydration'

describe('normalizePath', () => {
  it('убирает завершающий слеш, но оставляет корень', () => {
    expect(normalizePath('/privacy/')).toBe('/privacy')
    expect(normalizePath('/privacy')).toBe('/privacy')
    expect(normalizePath('/')).toBe('/')
  })
})

describe('canHydrate', () => {
  it('гидрирует разметку, пререндеренную для этого пути', () => {
    expect(canHydrate('/', '/')).toBe(true)
    expect(canHydrate('/privacy', '/privacy/')).toBe(true)
  })

  it('не гидрирует HTML главной, отданный на другой путь', () => {
    expect(canHydrate('/', '/nope')).toBe(false)
  })

  it('не гидрирует без data-route (dev-сервер)', () => {
    expect(canHydrate(undefined, '/')).toBe(false)
  })
})
