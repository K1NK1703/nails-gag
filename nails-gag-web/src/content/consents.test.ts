import { describe, expect, it } from 'vitest'
import { consents } from './consents'
import { PLACEHOLDERS } from './privacy'

describe('consents', () => {
  it('has four unique ids', () => {
    const ids = consents.map((c) => c.id)
    expect(ids).toHaveLength(4)
    expect(new Set(ids).size).toBe(4)
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
