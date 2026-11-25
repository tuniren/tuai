import { describe, expect, it } from 'vitest'
import { collectDocument } from './knowledge'
import type { DocumentLines } from './knowledge'

function page(lines: string[], start: number, end: number, max = 20_000): DocumentLines {
  const last = Math.min(end, lines.length)
  const content = lines.slice(start - 1, last).join('\n')
  return { documentId: 'test', source: 'test.md', startLine: start, endLine: last, totalLines: lines.length, content: content.slice(0, max), truncated: content.length > max }
}

describe('complete Markdown retrieval', () => {
  it('joins more than 200 lines including empty lines and a trailing newline', async () => {
    const lines = Array.from({ length: 431 }, (_, i) => i % 7 ? `line ${i + 1}` : '')
    lines.push('')
    const ranges: number[][] = []
    const result = await collectDocument(async (start, end) => { ranges.push([start, end]); return page(lines, start, end) }, new AbortController().signal)
    expect(result.content).toBe(lines.join('\n'))
    expect(result.totalLines).toBe(432)
    expect(ranges).toEqual([[1, 200], [201, 400], [401, 432]])
  })

  it('retries truncated pages without skipping or duplicating lines', async () => {
    const lines = Array.from({ length: 210 }, (_, i) => `${i + 1}: ${'中文内容'.repeat(70)}`)
    const ranges: number[][] = []
    const result = await collectDocument(async (start, end) => { ranges.push([start, end]); return page(lines, start, end) }, new AbortController().signal)
    expect(result.content).toBe(lines.join('\n'))
    expect(ranges.slice(0, 3)).toEqual([[1, 200], [1, 100], [1, 50]])
    expect(result.totalLines).toBe(210)
  })

  it('reports a single truncated line instead of silently dropping content', async () => {
    await expect(collectDocument(async (start, end) => page(['x'.repeat(20_001)], start, end), new AbortController().signal)).rejects.toThrow('单行读取上限')
  })

  it('rejects an inconsistent document changed during pagination', async () => {
    const lines = Array.from({ length: 220 }, () => 'line')
    await expect(collectDocument(async (start, end) => page(start === 1 ? lines : [...lines, 'new'], start, end), new AbortController().signal)).rejects.toThrow('读取期间发生变化')
  })

  it('cancels before reading a next page', async () => {
    const controller = new AbortController()
    let calls = 0
    await expect(collectDocument(async (start, end) => { calls++; controller.abort(); return page(Array(220).fill('line'), start, end) }, controller.signal)).rejects.toThrow()
    expect(calls).toBe(1)
  })
})
