export type SearchType = 'documents' | 'memories'
export type SearchMode = 'semantic' | 'keyword'
export interface SearchLocation {
  q: string; namespace: string; type: SearchType; mode: SearchMode
  limit: number; minScore: number; kind: string
}
export interface SearchItem {
  id: string; namespace: string; title: string; source: string; content: string; score: number | null
  documentId: string | null; startLine: number | null; endLine: number | null
  key: string | null; kind: string | null; revision: number | null; updatedAt: string | null
}
export interface SearchResponse {
  namespace: string | null; query: string; type: SearchType; mode: SearchMode; limit: number; items: SearchItem[]
}
export const memoryKinds: Record<string, string> = { preference: '偏好', fact: '事实', decision: '决策', procedure: '流程', context: '上下文' }

export function searchLocation(search: string): SearchLocation {
  const params = new URLSearchParams(search)
  const mode = params.get('mode') === 'keyword' ? 'keyword' : 'semantic'
  const score = Number(params.get('minScore'))
  return {
    q: params.get('q') || '', namespace: params.get('namespace') || '',
    type: params.get('type') === 'memories' ? 'memories' : 'documents', mode,
    limit: Number(params.get('limit')) === 20 ? 20 : 10,
    minScore: mode === 'semantic' && Number.isFinite(score) && score >= 0 && score <= 1 ? score : 0,
    kind: Object.hasOwn(memoryKinds, params.get('kind') || '') ? params.get('kind')! : '',
  }
}

export function searchUrl(location: SearchLocation): string {
  const params = new URLSearchParams({ ...(location.namespace ? { namespace: location.namespace } : {}), type: location.type, mode: location.mode, limit: String(location.limit) })
  if (location.q) params.set('q', location.q)
  if (location.mode === 'semantic' && location.minScore) params.set('minScore', String(location.minScore))
  if (location.type === 'memories' && location.kind) params.set('kind', location.kind)
  return `/search?${params}`
}

export async function searchKnowledge(location: SearchLocation, signal: AbortSignal): Promise<SearchResponse> {
  const url = new URL(searchUrl(location), window.location.origin)
  const response = await fetch(`/api/search${url.search}`, {
    signal: AbortSignal.any([signal, AbortSignal.timeout(90_000)]),
  })
  if (!response.ok) {
    const body = await response.json().catch(() => ({})) as { message?: string; detail?: string }
    throw new Error(body.message || body.detail || (response.status === 404
      ? '搜索接口尚未启用，请确认后端已升级到支持搜索的版本。' : `搜索失败（HTTP ${response.status}），请稍后重试。`))
  }
  return response.json() as Promise<SearchResponse>
}

export async function listSearchNamespaces(signal: AbortSignal): Promise<string[]> {
  const response = await fetch('/api/search/namespaces', { signal: AbortSignal.any([signal, AbortSignal.timeout(25_000)]) })
  if (!response.ok) throw new Error('无法加载命名空间')
  return response.json() as Promise<string[]>
}

/** A bounded preview centered on the first literal match; all markup remains plain text. */
export function excerpt(content: string, query: string, maximumLines = 6) {
  const lines = content.split('\n')
  const match = query ? content.toLowerCase().indexOf(query.toLowerCase()) : -1
  const matchLine = match < 0 ? 0 : content.slice(0, match).split('\n').length - 1
  const start = Math.max(0, matchLine - 2)
  const selected = lines.slice(start, start + maximumLines)
  return { start, lines: selected.map((line) => {
    if (line.length <= 320) return line
    const index = query ? line.toLowerCase().indexOf(query.toLowerCase()) : -1
    let from = Math.max(0, index - 100)
    if (from && /[\uDC00-\uDFFF]/.test(line[from])) from--
    let end = Math.min(line.length, from + 320)
    if (end < line.length && /[\uD800-\uDBFF]/.test(line[end - 1])) end--
    return `${from ? '…' : ''}${line.slice(from, end)}${end < line.length ? '…' : ''}`
  }), leading: start > 0, trailing: start + selected.length < lines.length }
}
