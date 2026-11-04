import { describe, expect, it } from 'vitest'
import { PLACEHOLDERS } from './privacy'
import { terms } from './terms'

const fullText = terms.sections
  .flatMap((s) => [s.title, ...s.paragraphs, ...(s.list ?? [])])
  .join(' ')

describe('terms', () => {
  it('has at least nine sections with unique ids', () => {
    const ids = terms.sections.map((s) => s.id)
    expect(ids.length).toBeGreaterThanOrEqual(9)
    expect(new Set(ids).size).toBe(ids.length)
  })

  it('states the key rules', () => {
    expect(fullText).toContain('24 час')
    expect(fullText).toContain('3 дн')
    expect(fullText).toContain('15 минут')
    expect(fullText).toMatch(/50 ?%/)
  })

  it('lets the performer waive the prepayment', () => {
    expect(fullText).toContain('вправе не требовать предоплату')
  })

  it('keeps the prepayment of a deleted account only after a violation', () => {
    expect(fullText).toContain('внёсший предоплату после поздней отмены или неявки, удалил аккаунт')
    expect(fullText).toContain('от которых предоплата не требовалась, это правило не касается')
  })

  it('contains only known placeholders', () => {
    const known = new Set<string>(PLACEHOLDERS)
    for (const m of fullText.matchAll(/\{\{[^}]*\}\}/g)) expect(known).toContain(m[0])
  })
})
