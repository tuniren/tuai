import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

const documentId = '11111111-1111-4111-8111-111111111111'
const documents = [
  { id: 'chunk-1', title: '部署 TuAI 到 Kubernetes', source: 'tuai/docs/deployment.md', documentId, startLine: 41, endLine: 49,
    content: '## 数据库与运行环境\n\nTuAI 使用 PostgreSQL 保存知识文档与长期记忆。\n通过 Kubernetes Secret 注入数据库连接配置。\n服务启动后执行 Flyway 迁移，保留已有文档。\n\n构建镜像后，通过现有脚本执行滚动更新。\n访问 /health 确认数据库连接正常。\n完成后验证 MCP 服务。', score: 0.892 },
  { id: 'chunk-2', title: '数据库连接与资源管理', source: 'tuai/src/main/scala/com/tuniren/tuai/Resources.scala', documentId: '22222222-2222-4222-8222-222222222222', startLine: 12, endLine: 16,
    content: 'def database(config: AppConfig): Resource[IO, HikariTransactor[IO]] =\n  // PostgreSQL connection pool\n  HikariTransactor.fromHikariConfig[IO](settings)\n\n// 进程退出时自动释放连接。', score: 0.827 },
  { id: 'chunk-3', title: 'Codex 使用 TuAI 远程 Memory', source: 'tuai/docs/codex-memory-guide.md', documentId: '33333333-3333-4333-8333-333333333333', startLine: 1, endLine: 4,
    content: '# Codex 使用 TuAI 远程 Memory\n\n正文、来源、版本和向量保存在 PostgreSQL。\n支持跨会话保存用户偏好、项目事实与决策。', score: 0.781 },
].map((item) => ({ ...item, namespace: 'tuai', key: null, kind: null, revision: null, updatedAt: null }))

const otherDocument = { ...documents[0], id: 'other-chunk', namespace: 'engineering', title: '其他分组的部署指南' }

const memory = { id: 'decisions/storage', title: '长期记忆的存储方案', source: '用户确认的项目技术决策', content: 'TuAI 使用 PostgreSQL 存储长期记忆。\n复用现有连接池和 Embedding 服务。\n更新和删除前读取当前 revision，避免覆盖其他会话的修改。', score: 0.913,
  namespace: 'user-liyd', documentId: null, startLine: null, endLine: null, key: 'decisions/storage', kind: 'decision', revision: 41, updatedAt: '2026-11-23T15:30:00Z' }

async function mockLibrary(page: Page) {
  await page.route('**/api/namespaces*', (route) => route.fulfill({ json: [{ namespace: 'tuai', documents: 6, markdownDocuments: 6 }, { namespace: 'engineering', documents: 12, markdownDocuments: 9 }] }))
  await page.route('**/api/search/namespaces*', (route) => route.fulfill({ json: ['engineering', 'tuai', 'user-liyd'] }))
  await page.route('**/api/search?**', (route) => {
    const params = new URL(route.request().url()).searchParams
    const type = params.get('type') || 'documents'; const mode = params.get('mode') || 'semantic'
    const scope = params.get('namespace')
    const candidates = type === 'memories' ? [memory] : [...documents, otherDocument]
    const items = params.get('q') === '不存在的内容' ? [] : candidates.filter((item) => !scope || item.namespace === scope)
    return route.fulfill({ json: { namespace: params.get('namespace'), query: params.get('q'), type, mode, limit: Number(params.get('limit')), items: items.map((item) => ({ ...item, score: mode === 'semantic' ? item.score : null })) } })
  })
  const summary = { id: documentId, namespace: 'tuai', title: documents[0].title, source: documents[0].source, metadata: {}, chunks: 1, updatedAt: '2026-11-23T12:00:00Z' }
  await page.route('**/api/documents?**', (route) => route.fulfill({ json: { items: [summary], total: 1, limit: 20, offset: 0 } }))
  await page.route('**/mcp', (route) => {
    const rpc = route.request().postDataJSON() as { id?: number; method: string }
    if (!rpc.id) return route.fulfill({ status: 202, body: '' })
    const result = rpc.method === 'initialize' ? { protocolVersion: '2025-06-18', capabilities: { tools: {} }, serverInfo: { name: 'tuai', version: 'test' } }
      : { isError: false, content: [{ type: 'text', text: JSON.stringify({ documentId, source: summary.source, startLine: 1, endLine: 9, totalLines: 9, content: documents[0].content, truncated: false }) }] }
    return route.fulfill({ json: { jsonrpc: '2.0', id: rpc.id, result } })
  })
}

test.beforeEach(async ({ page }) => { await mockLibrary(page) })

test('opens the search route without issuing an empty search, then shows real API result fields', async ({ page }) => {
  const requests: URL[] = []; const errors: string[] = []
  page.on('request', (request) => { if (request.url().includes('/api/search?')) requests.push(new URL(request.url())) })
  page.on('pageerror', (error) => errors.push(error.message))
  await page.goto('/search')
  await expect(page.getByRole('heading', { name: '搜索你的知识与记忆' })).toBeVisible()
  expect(requests).toHaveLength(0)
  await page.getByRole('textbox', { name: '搜索内容' }).fill('PostgreSQL')
  await page.getByRole('search').getByRole('button', { name: '搜索', exact: true }).click()
  await expect(page.locator('.search-result-card')).toHaveCount(4)
  await expect(page.getByRole('heading', { name: documents[0].title })).toBeVisible()
  await expect(page.locator('.search-score').first()).toContainText('0.892')
  await expect(page.locator('.search-result-card').first()).toContainText('第 41–49 行')
  await expect(page.locator('.search-snippet mark').first()).toHaveText('PostgreSQL')
  await expect(page.locator('.search-result-card').first().getByRole('link', { name: '查看文档' })).toHaveAttribute('href', /namespace=tuai&document=11111111/)
  await expect(page).toHaveURL(/\/search\?.*q=PostgreSQL/)
  expect(requests.at(-1)?.searchParams.get('mode')).toBe('semantic')
  await page.screenshot({ path: '.tools/search-desktop.png', fullPage: true })
  expect(errors).toEqual([])
})

test('keeps filters in the URL across reload, back and forward; opens the reader', async ({ page }) => {
  await page.goto('/search?namespace=tuai&q=PostgreSQL')
  await expect(page.locator('.search-result-card')).toHaveCount(3)
  await page.getByRole('radio', { name: /关键词搜索/ }).check()
  await expect(page.locator('.search-score')).toHaveCount(0)
  await expect(page).toHaveURL(/mode=keyword/)
  await page.getByLabel('最多返回').selectOption('20')
  await page.reload()
  await expect(page.getByLabel('最多返回')).toHaveValue('20')
  await expect(page.getByRole('radio', { name: /关键词搜索/ })).toBeChecked()
  await page.goBack()
  await expect(page.getByLabel('最多返回')).toHaveValue('10')
  await page.goForward()
  await expect(page.getByLabel('最多返回')).toHaveValue('20')
  await page.locator('.search-result-card').first().getByRole('link', { name: '查看文档' }).click()
  await expect(page.getByRole('heading', { name: documents[0].title, exact: true })).toBeVisible()
  await expect(page.getByText('已展示完整文档')).toBeVisible()
  await page.getByRole('link', { name: '打开检索页面' }).click()
  await expect(page.getByRole('heading', { name: '搜索你的知识与记忆' })).toBeVisible()
})

test('selects a memory-only namespace and displays complete memory details', async ({ page }) => {
  const requests: URL[] = []
  page.on('request', (request) => { if (request.url().includes('/api/search?')) requests.push(new URL(request.url())) })
  await page.goto('/search?namespace=tuai&q=PostgreSQL')
  await page.getByRole('button', { name: '长期记忆', exact: true }).click()
  await page.getByLabel('命名空间', { exact: true }).selectOption('user-liyd')
  await expect(page).toHaveURL(/namespace=user-liyd/)
  await page.getByLabel('记忆类型').selectOption('decision')
  await expect(page.locator('.search-result-card')).toHaveCount(1)
  await page.getByRole('button', { name: '查看记忆' }).click()
  const drawer = page.getByRole('dialog')
  await expect(drawer.getByText('decisions/storage', { exact: true })).toBeVisible()
  await expect(drawer.getByText('版本 41', { exact: true })).toBeVisible()
  await expect(drawer.locator('.search-memory-content')).toHaveText(memory.content)
  expect(requests.at(-1)?.searchParams.get('kind')).toBe('decision')
  expect(requests.at(-1)?.searchParams.get('namespace')).toBe('user-liyd')
})

test('shows empty and failed searches distinctly and recovers using keyword mode', async ({ page }) => {
  await page.goto('/search?namespace=tuai&q=不存在的内容')
  await expect(page.getByRole('heading', { name: '没有找到匹配的内容' })).toBeVisible()
  await page.route('**/api/search?**', (route) => new URL(route.request().url()).searchParams.get('mode') === 'semantic'
    ? route.fulfill({ status: 503, json: { message: '语义检索服务暂时不可用' } }) : route.fallback())
  await page.getByRole('textbox', { name: '搜索内容' }).fill('PostgreSQL')
  await page.getByRole('textbox', { name: '搜索内容' }).press('Enter')
  await expect(page.getByText('暂时无法完成搜索')).toBeVisible()
  await expect(page.getByRole('heading', { name: '没有找到匹配的内容' })).toHaveCount(0)
  await page.getByRole('button', { name: /切换关键词搜索/ }).click()
  await expect(page.locator('.search-result-card')).toHaveCount(3)
  await expect(page.locator('.search-score')).toHaveCount(0)
})

test('supports mobile results and filter navigation without horizontal overflow', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/search?namespace=tuai&q=PostgreSQL')
  await expect(page.locator('.search-result-card')).toHaveCount(3)
  await expect(page.locator('body')).toHaveJSProperty('scrollWidth', 390)
  await page.screenshot({ path: '.tools/search-mobile.png', fullPage: true })
  await page.getByRole('button', { name: '筛选条件', exact: true }).click()
  const drawer = page.getByRole('dialog')
  await drawer.getByRole('radio', { name: /关键词搜索/ }).check()
  await expect(page).toHaveURL(/mode=keyword/)
  await page.keyboard.press('Escape')
  await expect(drawer).not.toBeVisible()
  await expect(page.locator('.search-score')).toHaveCount(0)
  await expect(page.locator('body')).toHaveJSProperty('scrollWidth', 390)
})

test('renders hostile snippets as text and cannot run stored markup', async ({ page }) => {
  await page.route('**/api/search?**', (route) => route.fulfill({ json: { namespace: 'tuai', query: 'test', type: 'documents', mode: 'keyword', limit: 10,
    items: [{ ...documents[0], title: '<script>test</script>', content: '<img src=x onerror="window.__searchUnsafe=true">\n<script>window.__searchUnsafe=true</script>', score: null }] } }))
  await page.goto('/search?namespace=tuai&q=test&mode=keyword')
  await expect(page.locator('.search-snippet')).toContainText('<img src=x')
  await expect(page.locator('.search-snippet img, .search-snippet script')).toHaveCount(0)
  expect(await page.evaluate(() => '__searchUnsafe' in window)).toBe(false)
})

test('selects or clears namespaces and keeps result links in their actual namespace', async ({ page }) => {
  const requests: URL[] = []
  page.on('request', (request) => { if (request.url().includes('/api/search?')) requests.push(new URL(request.url())) })
  await page.goto('/search?q=PostgreSQL')
  const namespace = page.getByLabel('命名空间', { exact: true })
  await expect(namespace).toHaveValue('')
  await expect(page.locator('.search-result-card')).toHaveCount(4)
  const other = page.locator('.search-result-card').filter({ hasText: otherDocument.title })
  await expect(other.getByRole('link', { name: '查看文档' })).toHaveAttribute('href', /namespace=engineering&document=/)
  await namespace.selectOption('engineering')
  await expect(page.locator('.search-result-card')).toHaveCount(1)
  await expect(page).toHaveURL(/namespace=engineering/)
  await page.reload()
  await expect(namespace).toHaveValue('engineering')
  await namespace.selectOption('')
  await expect(page.locator('.search-result-card')).toHaveCount(4)
  expect(new URL(page.url()).searchParams.has('namespace')).toBe(false)
  expect(requests.at(-1)?.searchParams.has('namespace')).toBe(false)
  await page.goBack()
  await expect(namespace).toHaveValue('engineering')
  await page.goForward()
  await expect(namespace).toHaveValue('')
  await page.getByRole('button', { name: '长期记忆', exact: true }).click()
  await expect(page.locator('.search-result-card')).toHaveCount(1)
  await page.getByRole('button', { name: '查看记忆' }).click()
  await expect(page.getByRole('dialog').getByText('user-liyd', { exact: true })).toBeVisible()
})
