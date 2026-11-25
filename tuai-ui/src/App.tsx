import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Alert, App as AntApp, Button, Drawer, Empty, Input, Pagination, Segmented, Select, Skeleton, Tooltip } from 'antd'
import { ArrowDownOutlined, ArrowUpOutlined, CheckOutlined, CopyOutlined, FileMarkdownOutlined, MenuOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons'
import MarkdownReader from './components/MarkdownReader'
import { errorMessage, getDocumentSummary, listDocuments, listNamespaces, readDocument, resolveDocument } from './lib/knowledge'
import type { DocumentSummary, FullDocument, NamespaceSummary } from './lib/knowledge'
import { hashForHeading, resolveDocumentLink } from './lib/markdown'
import type { Heading } from './lib/markdown'

type Location = { namespace: string; id?: string; page: number; pageSize: number; query: string }
type Library = { namespace: string; items: DocumentSummary[]; total: number; loading: boolean; error?: string }
type Reading = { id?: string; loading: boolean; document?: FullDocument; error?: string; progress?: string }

function locationFromUrl(): Location {
  const params = new URLSearchParams(window.location.search)
  return { namespace: params.get('namespace') || 'tuai', id: params.get('document') || undefined, page: Math.max(1, Number(params.get('page')) || 1), pageSize: [10, 20, 50].includes(Number(params.get('size'))) ? Number(params.get('size')) : 20, query: params.get('query') || '' }
}

const dateFormatter = new Intl.DateTimeFormat('zh-CN', { month: 'long', day: 'numeric', year: 'numeric' })
function formatDate(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '更新时间未知' : dateFormatter.format(date)
}

export default function App() {
  const { message } = AntApp.useApp()
  const [location, setLocation] = useState(locationFromUrl)
  const [namespaces, setNamespaces] = useState<NamespaceSummary[]>([])
  const [namespaceLoading, setNamespaceLoading] = useState(true)
  const [namespaceError, setNamespaceError] = useState<string>()
  const [resolved, setResolved] = useState<{ namespace: string; id: string; summary?: DocumentSummary; error?: string }>()
  const [library, setLibrary] = useState<Library>({ namespace: location.namespace, items: [], total: 0, loading: true })
  const [reading, setReading] = useState<Reading>({ loading: false })
  const [filter, setFilter] = useState(location.query)
  const [revision, setRevision] = useState(0)
  const [mode, setMode] = useState<'阅读' | '源码'>('阅读')
  const [headings, setHeadings] = useState<Heading[]>([])
  const [activeHeading, setActiveHeading] = useState('')
  const [drawerOpen, setDrawerOpen] = useState(false)
  const [copied, setCopied] = useState(false)
  const [pendingHash, setPendingHash] = useState('')
  const reader = useRef<HTMLElement>(null)

  const navigate = useCallback((next: Location, replace = false) => {
    const url = new URL(window.location.href)
    url.search = new URLSearchParams({ namespace: next.namespace, page: String(next.page), size: String(next.pageSize), ...(next.query ? { query: next.query } : {}), ...(next.id ? { document: next.id } : {}) }).toString()
    url.hash = ''
    window.history[replace ? 'replaceState' : 'pushState'](null, '', url)
    setLocation(next)
  }, [])

  useEffect(() => {
    const listener = () => { const next = locationFromUrl(); setLocation(next); setFilter(next.query) }
    window.addEventListener('popstate', listener)
    return () => window.removeEventListener('popstate', listener)
  }, [])

  useEffect(() => {
    const controller = new AbortController()
    setNamespaceLoading(true)
    setNamespaceError(undefined)
    listNamespaces(controller.signal).then((items) => {
      if (controller.signal.aborted) return
      setNamespaces(items)
      setNamespaceLoading(false)
    }).catch((error: unknown) => {
      if (!controller.signal.aborted) { setNamespaceError(errorMessage(error)); setNamespaceLoading(false) }
    })
    return () => controller.abort()
  }, [revision])

  useEffect(() => {
    if (namespaceLoading || !namespaces.length) return
    if (!namespaces.some((item) => item.namespace === location.namespace)) {
      navigate({ namespace: namespaces[0].namespace, page: 1, pageSize: location.pageSize, query: '' }, true)
      setFilter('')
    }
  }, [namespaceLoading, namespaces, location.namespace, location.pageSize, navigate])

  useEffect(() => {
    if (filter.trim() === location.query) return
    const timer = window.setTimeout(() => navigate({ ...location, query: filter.trim(), page: 1, id: undefined }, true), 300)
    return () => window.clearTimeout(timer)
  }, [filter, location, navigate])

  useEffect(() => {
    const controller = new AbortController()
    setLibrary({ namespace: location.namespace, items: [], total: 0, loading: true })
    listDocuments(location.namespace, location.query, location.page, location.pageSize, controller.signal).then((result) => {
      if (controller.signal.aborted) return
      const lastPage = Math.max(1, Math.ceil(result.total / location.pageSize))
      if (location.page > lastPage) { navigate({ ...location, page: lastPage }, true); return }
      setLibrary({ namespace: location.namespace, items: result.items, total: result.total, loading: false })
    }).catch((error: unknown) => {
      if (!controller.signal.aborted) setLibrary({ namespace: location.namespace, items: [], total: 0, loading: false, error: errorMessage(error) })
    })
    return () => controller.abort()
  }, [location.namespace, location.query, location.page, location.pageSize, revision, navigate])

  const documents = useMemo(() => library.namespace === location.namespace ? library.items : [], [library, location.namespace])
  const pageSelection = location.id ? documents.find((doc) => doc.id === location.id) : documents[0]
  const selected = pageSelection ?? (resolved && resolved.id === location.id && resolved.namespace === location.namespace ? resolved.summary : undefined)
  const selectedId = selected?.id
  const loadingList = library.loading || library.namespace !== location.namespace
  const currentReading = reading.id === selectedId ? reading : { loading: true }
  const selectionError = resolved && resolved.id === location.id && resolved.namespace === location.namespace ? resolved.error : undefined
  const total = library.namespace === location.namespace ? library.total : 0

  useEffect(() => {
    if (!location.id || loadingList || pageSelection) return
    const controller = new AbortController()
    setResolved({ namespace: location.namespace, id: location.id })
    getDocumentSummary(location.namespace, location.id, controller.signal).then((summary) => {
      if (!controller.signal.aborted) setResolved({ namespace: location.namespace, id: summary.id, summary })
    }).catch((error: unknown) => {
      if (!controller.signal.aborted) setResolved({ namespace: location.namespace, id: location.id!, error: errorMessage(error) })
    })
    return () => controller.abort()
  }, [location.namespace, location.id, loadingList, pageSelection, revision])

  useEffect(() => {
    setHeadings([])
    setActiveHeading('')
    setMode('阅读')
    if (!selectedId) { setReading({ loading: false }); return }
    const controller = new AbortController()
    setReading({ id: selectedId, loading: true })
    readDocument(location.namespace, selectedId, controller.signal, (done, total) => {
      if (!controller.signal.aborted) setReading({ id: selectedId, loading: true, progress: `${done} / ${total} 行` })
    }).then((document) => {
      if (!controller.signal.aborted) setReading({ id: selectedId, document, loading: false })
    }).catch((error: unknown) => {
      if (!controller.signal.aborted) setReading({ id: selectedId, loading: false, error: errorMessage(error) })
    })
    return () => controller.abort()
  }, [location.namespace, selectedId, revision])

  useEffect(() => { reader.current?.scrollTo({ top: 0 }) }, [selectedId, mode])
  useEffect(() => { document.title = selected ? `${selected.title} · TuAI` : 'TuAI · 知识库' }, [selected])

  useEffect(() => {
    if (mode !== '阅读' || !headings.length) return
    const observer = new IntersectionObserver((entries) => {
      const visible = entries.filter((entry) => entry.isIntersecting).sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top)
      if (visible[0]) setActiveHeading(visible[0].target.id)
    }, { root: reader.current, rootMargin: '-4% 0px -65% 0px' })
    for (const heading of headings) { const node = document.getElementById(heading.id); if (node) observer.observe(node) }
    return () => observer.disconnect()
  }, [headings, mode])

  const jumpTo = useCallback((id: string) => { document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' }); setActiveHeading(id) }, [])
  useEffect(() => {
    if (!pendingHash || !headings.length) return
    let decoded = pendingHash
    try { decoded = decodeURIComponent(pendingHash) } catch { /* Keep the literal fragment. */ }
    const heading = headings.find((item) => item.id === decoded || hashForHeading(item.text) === decoded)
    if (heading) jumpTo(heading.id)
    else void message.info('未找到链接指向的章节')
    setPendingHash('')
  }, [pendingHash, headings, jumpTo, message])

  function selectDocument(id: string, hash = '') {
    setPendingHash(hash)
    setDrawerOpen(false)
    navigate({ ...location, id })
  }

  async function followLink(href: string) {
    if (href.startsWith('#')) { setPendingHash(href.slice(1)); return }
    if (!selected) return
    const target = resolveDocumentLink(selected.source, href)
    if (!target) return
    try {
      const doc = await resolveDocument(location.namespace, target.source, new AbortController().signal)
      selectDocument(doc.id, target.hash)
    } catch { void message.info('该链接对应的文件尚未收录到当前知识库') }
  }

  async function copyDocument() {
    if (!currentReading.document) return
    try { await navigator.clipboard.writeText(currentReading.document.content); setCopied(true); window.setTimeout(() => setCopied(false), 1800) }
    catch { void message.error('无法访问剪贴板，请使用源码视图手动复制') }
  }

  function downloadDocument() {
    if (!currentReading.document || !selected) return
    const url = URL.createObjectURL(new Blob([currentReading.document.content], { type: 'text/markdown;charset=utf-8' }))
    const anchor = document.createElement('a'); anchor.href = url; anchor.download = selected.source.split('/').pop() || 'document.md'; anchor.click()
    window.setTimeout(() => URL.revokeObjectURL(url), 1000)
  }

  const sidebar = <div className="library-panel">
    <div className="sidebar-tools">
      <Input className="filter-input" value={filter} onChange={(event) => setFilter(event.target.value)} prefix={<SearchOutlined />} placeholder="检索标题、路径或正文…" aria-label="检索文档" allowClear maxLength={1000} />
      <Tooltip title="刷新"><Button type="text" size="small" icon={<ReloadOutlined spin={loadingList} />} aria-label="刷新文档" onClick={() => setRevision((value) => value + 1)} disabled={loadingList} /></Tooltip>
    </div>
    <nav className="document-list" aria-label="知识库文档">
      {loadingList ? <div className="list-skeleton"><Skeleton active title={false} paragraph={{ rows: 7 }} /></div> : library.error ? <div className="list-error">连接失败，请在右侧重试。</div> : documents.length ? documents.map((doc) =>
        <button key={doc.id} className={`document-item ${selected?.id === doc.id ? 'selected' : ''}`} aria-current={selected?.id === doc.id ? 'page' : undefined} onClick={() => selectDocument(doc.id)} title={doc.source}>
          <FileMarkdownOutlined /><span className="document-label"><span>{doc.title}</span><small>{doc.source.replace(/^tuai\//, '')}</small></span><span className="selection-dot" />
        </button>) : <div className="list-empty">{filter ? '没有匹配的文档' : '暂无 Markdown 文档'}</div>}
    </nav>
    <div className="library-pagination"><Pagination simple size="small" current={location.page} total={total} pageSize={location.pageSize} showSizeChanger pageSizeOptions={[10, 20, 50]} onChange={(page, pageSize) => { setDrawerOpen(false); navigate({ ...location, page: pageSize !== location.pageSize ? 1 : page, pageSize, id: undefined }) }} disabled={loadingList} /></div>
  </div>

  return <div className="app-shell">
    <header className="app-header">
      <Select className="namespace-select" aria-label="命名空间" loading={namespaceLoading} placeholder="选择命名空间" value={namespaces.some((item) => item.namespace === location.namespace) ? location.namespace : undefined}
        showSearch={{ optionFilterProp: 'label' }} options={namespaces.map((item) => ({ value: item.namespace, label: `${item.namespace} (${item.documents})` }))}
        onChange={(namespace) => { setFilter(''); navigate({ namespace, page: 1, pageSize: location.pageSize, query: '' }) }}
        notFoundContent={namespaceError ? '加载失败' : '暂无命名空间'} />
      {namespaceError && <Tooltip title={namespaceError}><Button size="small" onClick={() => setRevision((value) => value + 1)}>重试</Button></Tooltip>}
    </header>
    <div className="workspace">
      <aside className="desktop-sidebar">{sidebar}</aside>
      <div className="reader-area">
        <div className="reader-toolbar">
          <div className="reader-breadcrumb"><Button className="mobile-menu" type="text" icon={<MenuOutlined />} aria-label="打开文档列表" onClick={() => setDrawerOpen(true)} /><span className="breadcrumb-file">{selected?.source.split('/').pop() || '文档阅读'}</span></div>
          <div className="reader-toolbar-actions"><Button type="text" icon={<SearchOutlined />} href="/search" aria-label="打开检索页面">检索</Button><Segmented aria-label="文档显示模式" options={['阅读', '源码']} value={mode} onChange={(value) => setMode(value as '阅读' | '源码')} disabled={!currentReading.document} /></div>
        </div>
        <main className="reader-scroll" ref={reader} id="reader-scroll" aria-label="文档阅读区">
          {loadingList ? <div className="loading-document"><Skeleton active paragraph={{ rows: 10 }} /></div>
            : library.error ? <div className="state-panel"><Alert type="error" showIcon title="暂时无法连接知识库" description={library.error} /><Button icon={<ReloadOutlined />} onClick={() => setRevision((value) => value + 1)}>重新连接</Button></div>
            : !selected && !location.id && !documents.length ? <div className="state-panel"><Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={location.query ? '没有匹配的文档' : '这个知识库还没有 Markdown 文档'} /></div>
            : !selected ? <div className="state-panel">{selectionError ? <><Empty description={selectionError} /><Button onClick={() => navigate({ ...location, id: undefined })}>返回文档列表</Button></> : <Skeleton active />}</div>
            : <div className="document-layout">
              <article className="document-article" aria-busy={currentReading.loading}>
                <h1 className="document-title">{selected.title}</h1>
                <div className="document-meta"><span>更新于 {formatDate(selected.updatedAt)}</span><span className="meta-dot">·</span><span>{currentReading.document ? `${currentReading.document.totalLines} 行` : `${selected.chunks} 个知识片段`}</span></div>
                <div className="source-bar"><code title={selected.source}>{selected.source}</code><div className="source-actions"><Tooltip title={copied ? '已复制' : '复制 Markdown'}><Button type="text" size="small" icon={copied ? <CheckOutlined /> : <CopyOutlined />} aria-label="复制 Markdown" disabled={!currentReading.document} onClick={() => void copyDocument()} /></Tooltip><Tooltip title="下载 Markdown"><Button type="text" size="small" icon={<ArrowDownOutlined />} aria-label="下载 Markdown" disabled={!currentReading.document} onClick={downloadDocument} /></Tooltip></div></div>
                {currentReading.loading ? <div className="body-loading"><p>正在读取文档{currentReading.progress ? ` · ${currentReading.progress}` : '…'}</p><Skeleton active title={false} paragraph={{ rows: 9 }} /></div>
                  : currentReading.error ? <div className="read-error"><Alert type="error" showIcon title="文档读取失败" description={currentReading.error} /><Button icon={<ReloadOutlined />} onClick={() => setRevision((value) => value + 1)}>重新读取</Button></div>
                  : currentReading.document ? <>
                    {mode === '源码' ? <pre className="raw-source" aria-label="Markdown 源码">{currentReading.document.content}</pre> : <MarkdownReader content={currentReading.document.content} title={selected.title} onHeadings={setHeadings} onLink={(href) => void followLink(href)} />}
                    <footer className="article-footer"><span><CheckOutlined /> 已展示完整文档</span><Button type="text" size="small" icon={<ArrowUpOutlined />} onClick={() => reader.current?.scrollTo({ top: 0, behavior: 'smooth' })}>回到顶部</Button></footer>
                  </> : null}
              </article>
              <aside className="toc-panel" aria-label="文章目录"><div className="toc-title">本文目录</div>{mode === '阅读' && headings.length ? <nav>{headings.filter((heading) => heading.level <= 3).map((heading) => <button key={heading.id} style={{ paddingLeft: heading.level > 2 ? 22 : 12 }} className={activeHeading === heading.id ? 'active' : ''} onClick={() => jumpTo(heading.id)}>{heading.text}</button>)}</nav> : <p>{mode === '源码' ? '切换到阅读视图查看目录' : '目录将在文档加载后显示'}</p>}</aside>
            </div>}
        </main>
      </div>
    </div>
    <Drawer title="知识库文档" open={drawerOpen} onClose={() => setDrawerOpen(false)} placement="left" size={310} styles={{ body: { padding: 0 } }}>{sidebar}</Drawer>
  </div>
}
