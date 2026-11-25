import { describe, expect, it } from 'vitest'
import { excerpt, searchLocation, searchUrl } from './search'

describe('search URL and evidence previews', () => {
  it('round trips a shared search without mixing namespace, query or memory filters', () => {
    const location = { namespace: 'project/中文', q: 'a&b # memory?', type: 'memories' as const, mode: 'semantic' as const, limit: 20, minScore: 0.7, kind: 'decision' }
    expect(searchLocation(searchUrl(location).split('?')[1])).toEqual(location)
    expect(searchUrl({ ...location, type: 'documents', mode: 'keyword' })).not.toMatch(/kind=|minScore=/)
  })

  it('normalizes unsupported URL options and uses all namespaces when none is selected', () => {
    expect(searchLocation('?namespace=&type=bad&mode=bad&limit=999&minScore=NaN&kind=__proto__')).toEqual({
      namespace: '', q: '', type: 'documents', mode: 'semantic', limit: 10, minScore: 0, kind: '',
    })
    expect(searchLocation('')).toHaveProperty('namespace', '')
    expect(searchUrl({ ...searchLocation(''), q: 'needle' })).not.toContain('namespace=')
  })

  it('centers previews on late matches and preserves complete Unicode characters in long lines', () => {
    const content = Array.from({ length: 20 }, (_, index) => index === 15 ? 'x'.repeat(600) + '😀 keyword ' + 'y'.repeat(600) : `line ${index}`).join('\n')
    const preview = excerpt(content, 'keyword')
    expect(preview.start).toBe(13)
    expect(preview.leading).toBe(true)
    expect(preview.trailing).toBe(true)
    expect(preview.lines[2]).toContain('😀 keyword')
    expect(preview.lines[2].length).toBeLessThanOrEqual(322)
    expect(Array.from(preview.lines[2]).some((character) => character.length === 1 && /[\uD800-\uDFFF]/.test(character))).toBe(false)
  })
})
