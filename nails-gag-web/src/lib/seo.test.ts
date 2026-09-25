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
    expect(head).toContain('Политика конфиденциальности')
    expect(head).toContain('href="https://nails-gag.ru/privacy"')
    expect(head).not.toContain('NailSalon')
  })
})

describe('titleFor', () => {
  it('отдаёт заголовок главной', () => {
    expect(titleFor('/')).toBe('nails.gag — маникюр в Санкт-Петербурге, чёткие квадраты')
  })

  it('отдаёт заголовок политики', () => {
    expect(titleFor('/privacy')).toBe('Политика конфиденциальности — nails.gag')
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
