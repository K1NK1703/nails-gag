import { describe, expect, it } from 'vitest'
import { headFor, titleFor } from './seo'

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
    expect(head).toContain('Политика обработки персональных данных')
    expect(head).toContain('href="https://nails-gag.ru/privacy"')
    expect(head).not.toContain('NailSalon')
  })

  it('builds head for terms without json-ld', () => {
    const head = headFor('/terms', 'https://nails-gag.ru')
    expect(head).toContain('<title>Пользовательское соглашение — nails.gag</title>')
    expect(head).toContain('href="https://nails-gag.ru/terms"')
    expect(head).not.toContain('application/ld+json')
  })

  it('marks booking and cabinet stubs as noindex', () => {
    for (const path of ['/booking', '/cabinet']) {
      expect(headFor(path, 'https://nails-gag.ru')).toContain(
        '<meta name="robots" content="noindex" />',
      )
    }
  })

  it('keeps public pages indexable', () => {
    for (const path of ['/', '/privacy', '/terms']) {
      expect(headFor(path, 'https://nails-gag.ru')).not.toContain('name="robots"')
    }
  })
})

describe('titleFor', () => {
  it('отдаёт заголовок главной', () => {
    expect(titleFor('/')).toBe('nails.gag — маникюр в Санкт-Петербурге, чёткие квадраты')
  })

  it('отдаёт заголовок политики', () => {
    expect(titleFor('/privacy')).toBe('Политика обработки персональных данных — nails.gag')
  })

  it('отдаёт заголовок соглашения', () => {
    expect(titleFor('/terms')).toBe('Пользовательское соглашение — nails.gag')
  })

  it('отдаёт заголовок онлайн-записи', () => {
    expect(titleFor('/booking')).toBe('Онлайн-запись — nails.gag')
  })

  it('отдаёт заголовок кабинета', () => {
    expect(titleFor('/cabinet')).toBe('Личный кабинет — nails.gag')
  })

  it('отдаёт 404 для неизвестного маршрута', () => {
    expect(titleFor('/nope')).toBe('Страница не найдена — nails.gag')
  })
})
