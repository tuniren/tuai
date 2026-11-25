import { expect, test } from '@playwright/test'

test('reads actual MCP documents, navigates and displays source', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'TuAI Knowledge Base MCP', exact: true })).toBeVisible()
  await expect(page.getByText('已展示完整文档')).toBeVisible()
  await expect(page.getByRole('navigation', { name: '知识库文档' }).getByRole('button', { name: 'Codex 使用 TuAI 知识库 MCP' })).toBeVisible()
  await page.getByRole('navigation', { name: '知识库文档' }).getByRole('button', { name: 'Codex 使用 TuAI 知识库 MCP' }).click()
  await expect(page.getByRole('heading', { name: 'Codex 使用 TuAI 知识库 MCP', exact: true })).toBeVisible()
  await expect(page.getByText('已展示完整文档')).toBeVisible()
  await expect(page.getByRole('main').locator('table').first()).toBeVisible()
  await page.getByText('源码', { exact: true }).click()
  await expect(page.getByLabel('Markdown 源码')).toContainText('tool_timeout_sec = 300')
  await page.getByText('阅读', { exact: true }).click()
  await page.getByRole('textbox', { name: '检索文档' }).fill('模型离线存储在节点上')
  await expect(page.getByRole('navigation', { name: '知识库文档' }).getByRole('button')).toHaveCount(1)
  await page.getByRole('navigation', { name: '知识库文档' }).getByRole('button', { name: '本地 Embedding 服务' }).click()
  await expect(page.getByRole('heading', { name: '本地 Embedding 服务', exact: true })).toBeVisible()
  await expect(page.getByText('已展示完整文档')).toBeVisible()
  await page.getByRole('textbox', { name: '检索文档' }).clear()
  await expect(page.getByRole('navigation', { name: '知识库文档' }).getByRole('button')).toHaveCount(6)
  await page.screenshot({ path: '.tools/reader-desktop.png', fullPage: true })
  await expect(page.locator('body')).toHaveJSProperty('scrollWidth', await page.locator('body').evaluate((el) => el.clientWidth))
  expect(errors).toEqual([])
})

test('supports mobile navigation without horizontal overflow', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/')
  await expect(page.getByText('已展示完整文档')).toBeVisible()
  await page.getByRole('button', { name: '打开文档列表' }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Codex 使用 TuAI 知识库 MCP' }).click()
  await expect(page.getByRole('dialog')).not.toBeVisible()
  await expect(page.getByRole('heading', { name: 'Codex 使用 TuAI 知识库 MCP', exact: true })).toBeVisible()
  await expect(page.getByText('已展示完整文档')).toBeVisible()
  await expect(page.locator('body')).toHaveJSProperty('scrollWidth', 390)
  await page.screenshot({ path: '.tools/reader-mobile.png', fullPage: true })
})

test('shows API errors and recovers with retry', async ({ page }) => {
  let fail = true
  await page.route('**/api/documents?**', async (route) => {
    if (fail) return route.fulfill({ status: 503, json: { message: 'Database operation unavailable' } })
    await route.continue()
  })
  await page.goto('/')
  await expect(page.getByText('Database operation unavailable')).toBeVisible()
  fail = false
  await page.getByRole('button', { name: '重新连接' }).click()
  await expect(page.getByText('已展示完整文档')).toBeVisible()
})

test('selects namespaces supplied by API, including namespaces with no Markdown', async ({ page }) => {
  await page.route('**/api/namespaces?*', async (route) => {
    const response = await route.fetch()
    const items = await response.json() as object[]
    await route.fulfill({ json: [...items, { namespace: 'other-project', documents: 2, markdownDocuments: 0 }] })
  })
  await page.goto('/')
  await expect(page.getByText('已展示完整文档')).toBeVisible()
  await page.getByRole('combobox', { name: '命名空间', exact: true }).click()
  await page.getByText('other-project (2)', { exact: true }).click()
  await expect(page.getByText('这个知识库还没有 Markdown 文档')).toBeVisible()
  await page.reload()
  await expect(page.getByText('这个知识库还没有 Markdown 文档')).toBeVisible()
  await expect(page).toHaveURL(/namespace=other-project/)
  await expect(page.getByRole('button', { name: /登录|登入/ })).toHaveCount(0)
})

test('requests only the selected page and searches across all pages', async ({ page }) => {
  const requested: URL[] = []
  const docs = Array.from({ length: 25 }, (_, index) => ({ id: `page-doc-${index}`, namespace: 'tuai', source: `page-${index}.md`, title: `分页文档 ${index + 1}`, metadata: {}, chunks: 1, updatedAt: '2026-11-23T12:00:00Z' }))
  await page.route('**/api/documents?**', async (route) => {
    const url = new URL(route.request().url()); requested.push(url)
    const offset = Number(url.searchParams.get('offset'))
    const limit = Number(url.searchParams.get('limit'))
    const items = url.searchParams.get('query') ? [docs[24]] : docs
    await route.fulfill({ json: { items: items.slice(offset, offset + limit), total: items.length, offset, limit } })
  })
  await page.route('**/mcp', async (route) => {
    const rpc = route.request().postDataJSON() as { id?: number; params?: { name?: string; arguments?: { documentId?: string } } }
    if (rpc.params?.name === 'kb_read_document') return route.fulfill({ json: { jsonrpc: '2.0', id: rpc.id, result: { isError: false, content: [{ type: 'text', text: JSON.stringify({ documentId: rpc.params.arguments?.documentId, source: 'page.md', startLine: 1, endLine: 1, totalLines: 1, content: '分页测试正文', truncated: false }) }] } } })
    await route.continue()
  })
  await page.goto('/')
  const list = page.getByRole('navigation', { name: '知识库文档' })
  await expect(list.getByRole('button')).toHaveCount(20)
  expect(requested.every((url) => url.searchParams.get('offset') === '0')).toBe(true)
  await page.getByRole('button', { name: '下一页', exact: true }).click()
  await expect(list.getByRole('button')).toHaveCount(5)
  expect(requested.some((url) => url.searchParams.get('offset') === '20')).toBe(true)
  await page.getByRole('textbox', { name: '检索文档' }).fill('查找第 25 份正文')
  await expect(list.getByRole('button')).toHaveCount(1)
  await expect(list.getByRole('button')).toContainText('分页文档 25')
  expect(requested.some((url) => url.searchParams.get('query') === '查找第 25 份正文' && url.searchParams.get('offset') === '0')).toBe(true)
})

test('keeps the header compact and removes decorative content', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByText('已展示完整文档')).toBeVisible()
  const header = page.locator('.app-header')
  expect((await header.boundingBox())!.height).toBeLessThanOrEqual(44)
  await expect(header.getByRole('combobox')).toHaveCount(1)
  await expect(header.getByRole('link')).toHaveCount(0)
  await expect(page.getByText('所有命名空间 · 无需登录')).toHaveCount(0)
  await expect(page.getByText('Public', { exact: true })).toHaveCount(0)
})
