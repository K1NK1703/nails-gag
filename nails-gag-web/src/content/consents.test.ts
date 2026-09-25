import { describe, expect, it } from 'vitest'
import { consents } from './consents'
import { PLACEHOLDERS } from './privacy'

describe('consents', () => {
  it('has five unique ids', () => {
    const ids = consents.map((c) => c.id)
    expect(ids).toEqual([
      'registration',
      'photoPublication',
      'reviewPublication',
      'reminders',
      'newsletter',
    ])
    expect(new Set(ids).size).toBe(5)
  })

  it('registration refers to both legal documents', () => {
    const reg = consents.find((c) => c.id === 'registration')
    expect(reg?.text).toContain('Пользовательское соглашение')
    expect(reg?.text).toContain('Политика обработки персональных данных')
  })

  it('every text refers to the policy', () => {
    for (const c of consents) expect(c.text).toMatch(/Политик/)
  })

  it('contains only known placeholders', () => {
    const known = new Set<string>(PLACEHOLDERS)
    for (const c of consents) {
      for (const m of `${c.label} ${c.text}`.matchAll(/\{\{[^}]*\}\}/g)) {
        expect(known).toContain(m[0])
      }
    }
  })
})
