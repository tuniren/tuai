import { useEffect, useRef, useState } from 'react'
import { Alert, Drawer, Skeleton } from 'antd'
import { ArrowLeftOutlined, ArrowRightOutlined, BookOutlined, BulbOutlined, CheckOutlined, CloseOutlined, FileTextOutlined, FilterOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons'
import { errorMessage } from '../lib/knowledge'
import { excerpt, listSearchNamespaces, memoryKinds, searchKnowledge, searchLocation, searchUrl } from '../lib/search'
import type { SearchItem, SearchLocation, SearchResponse } from '../lib/search'
import './search.css'

function Highlight({ text, query }: { text: string; query: string }) {
  if (!query) return <>{text}</>
  const index = text.toLowerCase().indexOf(query.toLowerCase())
  return index < 0 ? <>{text}</> : <>{text.slice(0, index)}<mark>{text.slice(index, index + query.length)}</mark>{text.slice(index + query.length)}</>
}

function readerUrl(namespace: string, id?: string | null) {
  return namespace ? `/?${new URLSearchParams({ namespace, ...(id ? { document: id } : {}) })}` : '/'
}

export default function SearchPage() {
  const [location, setLocation] = useState(() => searchLocation(window.location.search))
  const [query, setQuery] = useState(location.q)
  const [namespaces, setNamespaces] = useState<string[]>([])
  const [namespaceError, setNamespaceError] = useState(false)
  const [result, setResult] = useState<{ key: string; data?: SearchResponse; error?: string; elapsed?: number }>()
  const [revision, setRevision] = useState(0)
  const [detail, setDetail] = useState<SearchItem>()
  const [filtersOpen, setFiltersOpen] = useState(false)
  const [mobile, setMobile] = useState(() => window.matchMedia('(max-width: 760px)').matches)
  const input = useRef<HTMLInputElement>(null)
  const requestKey = searchUrl(location)
  const active = result?.key === requestKey ? result : undefined
  const loading = Boolean(location.q) && !active
  const items = active?.data?.items ?? []

  function navigate(next: SearchLocation) {
    const url = searchUrl(next)
    if (url !== `${window.location.pathname}${window.location.search}`) window.history.pushState(null, '', url)
    setLocation(next); setQuery(next.q); setDetail(undefined)
  }
  function apply(patch: Partial<SearchLocation> = {}) {
    navigate({ ...location, q: query.trim(), ...patch })
  }

  useEffect(() => {
    const media = window.matchMedia('(max-width: 760px)')
    const listener = () => { setMobile(media.matches); setFiltersOpen(false) }
    media.addEventListener('change', listener)
    return () => media.removeEventListener('change', listener)
  }, [])

  useEffect(() => {
    const listener = () => {
      const next = searchLocation(window.location.search)
      setLocation(next); setQuery(next.q); setDetail(undefined)
    }
    window.addEventListener('popstate', listener)
    return () => window.removeEventListener('popstate', listener)
  }, [])

  useEffect(() => {
    const controller = new AbortController()
    setNamespaceError(false)
    listSearchNamespaces(controller.signal).then((data) => {
      if (!controller.signal.aborted) setNamespaces(data)
    }).catch(() => { if (!controller.signal.aborted) setNamespaceError(true) })
    return () => controller.abort()
  }, [revision])

  useEffect(() => {
    document.title = location.q ? `${location.q} · 搜索 · TuAI` : '搜索 · TuAI'
    if (!location.q) return
    const controller = new AbortController()
    const started = performance.now()
    setResult(undefined)
    searchKnowledge(location, controller.signal).then((data) => {
      if (!controller.signal.aborted) setResult({ key: requestKey, data, elapsed: (performance.now() - started) / 1000 })
    }).catch((error: unknown) => {
      if (!controller.signal.aborted) setResult({ key: requestKey, error: error instanceof Error && error.name === 'TimeoutError'
        ? '搜索超时，请稍后重试，或切换关键词搜索。' : errorMessage(error) })
    })
    return () => controller.abort()
  }, [location, requestKey, revision])

  useEffect(() => {
    const listener = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key === 'k') { event.preventDefault(); input.current?.focus() }
    }
    window.addEventListener('keydown', listener)
    return () => window.removeEventListener('keydown', listener)
  }, [])

  const filters = <>
    <h2 className="search-filter-heading">筛选条件</h2>
    <nav className="search-types" aria-label="搜索类型">
      <button type="button" aria-label="知识文档" className={location.type === 'documents' ? 'is-active' : ''} aria-pressed={location.type === 'documents'} onClick={() => apply({ type: 'documents', kind: '' })}><FileTextOutlined />知识文档<ArrowRightOutlined /></button>
      <button type="button" aria-label="长期记忆" className={location.type === 'memories' ? 'is-active' : ''} aria-pressed={location.type === 'memories'} onClick={() => apply({ type: 'memories' })}><BulbOutlined />长期记忆<ArrowRightOutlined /></button>
    </nav>
    <div className="search-filter-group">
      <label htmlFor="search-namespace">命名空间</label>
      <select id="search-namespace" value={location.namespace} onChange={(event) => apply({ namespace: event.target.value })}>
        <option value="">全部命名空间</option>
        {[...new Set([...namespaces, ...(location.namespace ? [location.namespace] : [])])].sort().map((item) => <option key={item} value={item}>{item}</option>)}
      </select>
      <p>不选择时搜索全部命名空间。</p>
      {namespaceError && <button className="search-text-button" onClick={() => setRevision((value) => value + 1)}>重新加载命名空间</button>}
    </div>
    <fieldset className="search-filter-group search-mode-options"><legend>搜索方式</legend>
      <label><input type="radio" name="search-mode" checked={location.mode === 'semantic'} onChange={() => apply({ mode: 'semantic' })} /><span>语义搜索<small>用自然语言查找相关内容</small></span></label>
      <label><input type="radio" name="search-mode" checked={location.mode === 'keyword'} onChange={() => apply({ mode: 'keyword', minScore: 0 })} /><span>关键词搜索<small>匹配标题、路径和正文</small></span></label>
    </fieldset>
    {location.type === 'memories' && <div className="search-filter-group"><label htmlFor="search-kind">记忆类型</label><select id="search-kind" value={location.kind} onChange={(event) => apply({ kind: event.target.value })}><option value="">全部类型</option>{Object.entries(memoryKinds).map(([key, label]) => <option value={key} key={key}>{label}</option>)}</select></div>}
    {location.mode === 'semantic' && <div className="search-filter-group"><label htmlFor="search-score">最低相似度</label><select id="search-score" value={location.minScore} onChange={(event) => apply({ minScore: Number(event.target.value) })}>
      {[...new Set([0, 0.3, 0.5, 0.7, 0.9, location.minScore])].sort((a, b) => a - b).map((score) => <option value={score} key={score}>{score === 0 ? '不限' : `≥ ${score.toFixed(2)}`}</option>)}</select><p>相似度表示内容相关程度。</p></div>}
    <div className="search-filter-group"><label htmlFor="search-limit">最多返回</label><select id="search-limit" value={location.limit} onChange={(event) => apply({ limit: Number(event.target.value) })}><option value="10">10 条结果</option><option value="20">20 条结果</option></select></div>
    <div className="search-scope-note"><CheckOutlined /><span>只读检索<br /><small>不会修改文档和记忆</small></span></div>
  </>

  return <div className="search-page">
    <header className="search-header">
      <a className="search-brand" href={readerUrl(location.namespace)} aria-label="TuAI 知识库"><span><BookOutlined /></span><strong>TuAI</strong></a><span className="search-header-divider">/</span><span className="search-header-label">搜索</span>
      <form id="search-form" className="search-query-form" role="search" onSubmit={(event) => { event.preventDefault(); apply(); setFiltersOpen(false) }}>
        <div className="search-query-field"><SearchOutlined /><input ref={input} aria-label="搜索内容" placeholder="搜索知识文档或长期记忆…" maxLength={8000} value={query} onChange={(event) => setQuery(event.target.value)} />
          {query ? <button type="button" aria-label="清空搜索内容" onClick={() => { setQuery(''); input.current?.focus() }}><CloseOutlined /></button> : <kbd>Ctrl K</kbd>}
        </div><button type="submit" className="search-submit">搜索</button>
      </form>
      <a className="search-back" href={readerUrl(location.namespace)}><ArrowLeftOutlined /><span>返回阅读</span></a>
    </header>
    <div className="search-layout">
      <aside className="search-sidebar" aria-label="检索筛选">{!mobile && filters}</aside>
      <main className="search-results" id="search-results">
        <div className="search-mobile-tools"><button className="search-secondary-button" aria-label="筛选条件" onClick={() => setFiltersOpen(true)}><FilterOutlined /> 筛选条件</button><span>{location.type === 'documents' ? '知识文档' : '长期记忆'} · {location.namespace || '全部命名空间'}</span></div>
        {!location.q ? <div className="search-start">
          <div className="search-empty-icon"><SearchOutlined /></div><h1>搜索你的知识与记忆</h1><p>输入一个问题、一段描述，或你记得的关键词。</p>
          <div className="search-examples"><span>试试搜索</span>{(location.type === 'documents' ? ['如何部署 TuAI', '数据库配置', 'MCP 连接方法'] : ['回答语言偏好', '项目技术决策', '常用操作流程']).map((example) => <button key={example} onClick={() => { navigate({ ...location, q: example }) }}>{example}<ArrowRightOutlined /></button>)}</div>
          <div className="search-start-note"><strong>{location.mode === 'semantic' ? '语义搜索' : '关键词搜索'}</strong><span>{location.mode === 'semantic' ? '理解查询含义，查找表达不同但含义相关的内容。' : '查找包含完整关键词的标题、路径和正文。'}</span></div>
        </div> : <>
          <div className="search-results-title"><div><h1>搜索结果</h1><p>在 <strong>{location.namespace || '全部命名空间'}</strong> 中搜索 <span>“{location.q}”</span></p></div><span className={`search-mode-badge ${location.mode}`}>{location.mode === 'semantic' ? <BulbOutlined /> : <SearchOutlined />}{location.mode === 'semantic' ? '语义搜索' : '关键词搜索'}</span></div>
          <div className="search-results-toolbar" role="status" aria-live="polite"><span>{loading ? '正在搜索相关内容…' : active?.error ? '搜索未完成' : <>找到 <strong>{items.length}</strong> 个{location.type === 'documents' ? '知识片段' : '记忆'}{active?.elapsed !== undefined && <small> · {active.elapsed.toFixed(2)} 秒</small>}</>}</span><span>{location.mode === 'semantic' ? '按相似度排序' : location.type === 'documents' ? '按来源路径排序' : '按更新时间排序'}</span></div>
          {loading ? <div className="search-loading" aria-label="正在加载搜索结果" aria-busy="true">{[1, 2, 3].map((key) => <div className="search-result-skeleton" key={key}><Skeleton active paragraph={{ rows: 3 }} /></div>)}</div>
            : active?.error ? <div className="search-error"><Alert type="error" showIcon title="暂时无法完成搜索" description={active.error} /><div><button className="search-secondary-button" onClick={() => setRevision((value) => value + 1)}><ReloadOutlined /> 重试搜索</button>{location.mode === 'semantic' && <button className="search-text-button" onClick={() => apply({ mode: 'keyword', minScore: 0 })}>切换关键词搜索 <ArrowRightOutlined /></button>}</div></div>
              : !items.length ? <div className="search-no-results"><div className="search-empty-icon"><SearchOutlined /></div><h2>没有找到匹配的内容</h2><p>试试更简短的查询，检查命名空间，或调整搜索方式。</p>{location.minScore > 0 && <button className="search-secondary-button" onClick={() => apply({ minScore: 0 })}>取消相似度限制</button>}</div>
                : <div className="search-result-list">{items.map((item, index) => {
                  const preview = excerpt(item.content, location.q)
                  const isDocument = location.type === 'documents'
                  return <article className="search-result-card" key={`${item.id}-${index}`}>
                    <div className="search-result-heading"><span className="search-result-type-icon">{isDocument ? <FileTextOutlined /> : <BulbOutlined />}</span><div>
                      <div className="search-result-path">{item.namespace}<span>/</span>{isDocument ? item.source : item.key}</div>
                      <h2>{isDocument ? <a href={readerUrl(item.namespace, item.documentId)}><Highlight text={item.title} query={location.q} /></a> : <button onClick={() => setDetail(item)}><Highlight text={item.title} query={location.q} /></button>}</h2>
                    </div>{item.score !== null && <span className="search-score" title="余弦相似度，表示内容相关程度">相似度 <strong>{item.score.toFixed(3)}</strong></span>}</div>
                    <div className="search-snippet" aria-label="内容预览">{preview.leading && <div className="search-snippet-ellipsis">…</div>}{preview.lines.map((line, lineIndex) => <div className="search-snippet-line" key={lineIndex}>
                      {isDocument && <span className="search-line-number">{(item.startLine ?? 1) + preview.start + lineIndex}</span>}<code><Highlight text={line || ' '} query={location.q} /></code>
                    </div>)}{preview.trailing && <div className="search-snippet-ellipsis">…</div>}</div>
                    <footer className="search-result-footer"><div>{isDocument ? <><span>知识文档</span><span>第 {item.startLine}–{item.endLine} 行</span></> : <><span className="search-kind-badge">{memoryKinds[item.kind ?? ''] ?? item.kind}</span><span>版本 {item.revision}</span><span className="search-memory-source" title={item.source}>来源：{item.source}</span></>}</div>
                      {isDocument ? <a href={readerUrl(item.namespace, item.documentId)}>查看文档 <ArrowRightOutlined /></a> : <button onClick={() => setDetail(item)}>查看记忆 <ArrowRightOutlined /></button>}
                    </footer>
                  </article>
                })}<p className="search-results-end">已展示本次返回的 {items.length} 条结果{items.length === location.limit ? '，可增加返回数量或细化查询。' : '。'}</p></div>}
        </>}
      </main>
    </div>
    <Drawer title="筛选条件" open={mobile && filtersOpen} onClose={() => setFiltersOpen(false)} placement="left" size={290}><div className="search-drawer-filters">{mobile && filtersOpen && filters}</div></Drawer>
    <Drawer title={detail?.title || '记忆详情'} open={Boolean(detail)} onClose={() => setDetail(undefined)} size={620} className="search-memory-drawer">
      {detail && <><div className="search-memory-meta"><span>{memoryKinds[detail.kind ?? ''] ?? detail.kind}</span><span>版本 {detail.revision}</span></div><dl><dt>命名空间</dt><dd>{detail.namespace}</dd><dt>记忆标识</dt><dd>{detail.key}</dd><dt>来源</dt><dd>{detail.source}</dd><dt>更新时间</dt><dd>{detail.updatedAt ? new Date(detail.updatedAt).toLocaleString('zh-CN', { hour12: false }) : '未知'}</dd></dl><h3>记忆正文</h3><pre className="search-memory-content">{detail.content}</pre></>}
    </Drawer>
  </div>
}
