import { describe, expect, it } from 'vitest'
import { headFor } from './seo'

describe('headFor', () => {
  it('builds head for home with json-ld', () => {
    const head = headFor('/', 'https://nails-gag.ru')
    expect(head).toContain('<title>')
    expect(head).toContain('rel="canonical" href="https://nails-gag.ru/"')
    expect(head).toContain('og:image')
    expect(head).toContain('"@type":"NailSalon"')
  })

  it('builds head for privacy without json-ld', () => {
    const head = headFor('/privacy', 'https://nails-gag.ru')
    expect(head).toContain('Политика конфиденциальности')
    expect(head).toContain('href="https://nails-gag.ru/privacy"')
    expect(head).not.toContain('NailSalon')
  })
})
