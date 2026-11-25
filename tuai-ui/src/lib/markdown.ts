import type { Root } from 'hast'

export interface Heading { id: string; text: string; level: number }

export function headingId(text: string, index: number) {
  return `section-${index}-${text.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, '-').replace(/^-|-$/g, '')}`
}

function textContent(node: Root['children'][number]): string {
  if ('value' in node && typeof node.value === 'string') return node.value
  return 'children' in node ? node.children.map(textContent).join('') : ''
}

export function headingsFromTree(tree: Root) {
  const headings: Heading[] = []
  function walk(node: Root | Root['children'][number]) {
    if (node.type === 'element' && /^h[1-6]$/.test(node.tagName)) {
      const text = textContent(node)
      const id = headingId(text, headings.length)
      node.properties.id = id
      headings.push({ id, text, level: Number(node.tagName[1]) })
    }
    if ('children' in node) node.children.forEach(walk)
  }
  walk(tree)
  return headings
}

export function resolveDocumentLink(source: string, href: string): { source: string; hash: string } | undefined {
  if (/^(?:[a-z][a-z\d+.-]*:|\/\/)/i.test(href) || href.startsWith('#') || href.startsWith('/')) return undefined
  const [path, hash = ''] = href.split('#', 2)
  let decoded: string
  try { decoded = decodeURIComponent(path.split('?')[0]) } catch { return undefined }
  const segments = source.split('/').slice(0, -1)
  for (const part of decoded.split('/')) {
    if (part === '..') segments.pop()
    else if (part && part !== '.') segments.push(part)
  }
  return { source: segments.join('/'), hash }
}

export function hashForHeading(text: string) {
  return text.toLowerCase().replace(/[^\p{L}\p{N}\s_-]/gu, '').replace(/\s/g, '-')
}
