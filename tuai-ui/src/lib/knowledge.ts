export interface DocumentSummary {
  id: string
  namespace: string
  source: string
  title: string
  metadata: Record<string, unknown>
  chunks: number
  updatedAt: string
}

export interface DocumentLines {
  documentId: string
  source: string
  startLine: number
  endLine: number
  totalLines: number
  content: string
  truncated: boolean
}

export interface FullDocument {
  content: string
  totalLines: number
}

interface RpcResponse<T> {
  jsonrpc: string
  id: number
  result?: T
  error?: { code: number; message: string }
}

interface ToolResult {
  isError?: boolean
  content: { type: string; text?: string }[]
}

let requestId = 0
let connection: Promise<void> | undefined

async function rpc<T>(method: string, params: unknown, signal?: AbortSignal): Promise<T> {
  const id = ++requestId
  const response = await fetch('/mcp', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream' },
    body: JSON.stringify({ jsonrpc: '2.0', id, method, params }),
    signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(25_000)]) : AbortSignal.timeout(25_000),
  })
  if (!response.ok) throw new Error(`知识库连接失败（HTTP ${response.status}）。请检查 MCP 服务及代理配置。`)
  const payload: RpcResponse<T> = await response.json()
  if (payload.id !== id || payload.jsonrpc !== '2.0') throw new Error('知识库返回了无效的协议响应。')
  if (payload.error) throw new Error(payload.error.message)
  if (!payload.result) throw new Error('知识库未返回有效数据。')
  return payload.result
}

async function connect() {
  connection ??= (async () => {
    await rpc('initialize', { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'tuai-ui', version: '0.1.0' } })
    const response = await fetch('/mcp', {
      method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream' },
      body: JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }), signal: AbortSignal.timeout(25_000),
    })
    if (!response.ok) throw new Error('知识库初始化握手失败。')
  })().catch((error: unknown) => { connection = undefined; throw error })
  return connection
}

async function call<T>(name: string, args: Record<string, unknown>, signal: AbortSignal): Promise<T> {
  await connect()
  signal.throwIfAborted()
  const result = await rpc<ToolResult>('tools/call', { name, arguments: args }, signal)
  const text = result.content.filter((item) => item.type === 'text').map((item) => item.text ?? '').join('\n')
  if (result.isError) throw new Error(text || '知识库操作失败。')
  try { return JSON.parse(text) as T } catch { throw new Error('知识库返回的数据格式不正确。') }
}

export interface NamespaceSummary { namespace: string; documents: number; markdownDocuments: number }
export interface DocumentPage { items: DocumentSummary[]; total: number; limit: number; offset: number }

async function api<T>(path: string, params: Record<string, string>, signal: AbortSignal): Promise<T> {
  const response = await fetch(`/api/${path}?${new URLSearchParams(params)}`, { signal: AbortSignal.any([signal, AbortSignal.timeout(25_000)]) })
  if (!response.ok) {
    const error = await response.json().catch(() => ({})) as { message?: string }
    throw new Error(error.message || `知识库请求失败（HTTP ${response.status}）`)
  }
  return response.json() as Promise<T>
}

export const listNamespaces = (signal: AbortSignal) => api<NamespaceSummary[]>('namespaces', {}, signal)
export const listDocuments = (namespace: string, query: string, page: number, pageSize: number, signal: AbortSignal) =>
  api<DocumentPage>('documents', { namespace, query, limit: String(pageSize), offset: String((page - 1) * pageSize) }, signal)
export const getDocumentSummary = (namespace: string, id: string, signal: AbortSignal) =>
  api<DocumentSummary>(`documents/${encodeURIComponent(id)}`, { namespace }, signal)
export const resolveDocument = (namespace: string, source: string, signal: AbortSignal) =>
  api<DocumentSummary>('documents/resolve', { namespace, source }, signal)

export type ReadLines = (startLine: number, endLine: number) => Promise<DocumentLines>

/** A truncated page's endLine is not evidence of returned lines. Retry a narrower range. */
export async function collectDocument(read: ReadLines, signal: AbortSignal, onProgress?: (read: number, total: number) => void): Promise<FullDocument> {
  const parts: string[] = []
  let start = 1
  let total: number | undefined
  let windowSize = 200
  while (total === undefined || start <= total) {
    signal.throwIfAborted()
    const requestedEnd = Math.min(start + windowSize - 1, total ?? Number.MAX_SAFE_INTEGER)
    const page = await read(start, requestedEnd)
    if (page.startLine !== start || page.endLine < start || page.endLine > requestedEnd || page.totalLines < page.endLine) throw new Error('文档分页响应不一致，请刷新后重试。')
    if (total !== undefined && total !== page.totalLines) throw new Error('文档在读取期间发生变化，请刷新后重试。')
    total = page.totalLines
    if (page.truncated) {
      if (requestedEnd === start) throw new Error(`第 ${start} 行超过服务单行读取上限，无法展示完整文档。请将原文长行拆分后重新入库。`)
      windowSize = Math.max(1, Math.floor((requestedEnd - start + 1) / 2))
      continue
    }
    parts.push(page.content)
    onProgress?.(page.endLine, total)
    start = page.endLine + 1
  }
  return { content: parts.join('\n'), totalLines: total }
}

export function readDocument(namespace: string, documentId: string, signal: AbortSignal, onProgress?: (read: number, total: number) => void) {
  return collectDocument((startLine, endLine) => call<DocumentLines>('kb_read_document', { namespace, documentId, startLine, endLine }, signal), signal, onProgress)
}

export function errorMessage(error: unknown): string {
  if (error instanceof Error && error.name === 'TimeoutError') return '读取超时，请检查服务连接后重试。'
  if (error instanceof TypeError) return '无法连接知识库，请检查网络和 MCP 代理是否启动。'
  return error instanceof Error ? error.message : '读取失败，请稍后重试。'
}
