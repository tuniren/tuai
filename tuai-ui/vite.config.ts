import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import type { ProxyOptions } from 'vite'

export default defineConfig(({ mode }) => {
  const env = { ...loadEnv(mode, process.cwd(), ''), ...process.env }
  const target = env.TUAI_MCP_TARGET || 'http://192.168.6.162:30881'
  const proxy: Record<string, ProxyOptions> = {
    '/api': { target, changeOrigin: true, timeout: 95_000, proxyTimeout: 95_000 },
    '/mcp': {
      target,
      changeOrigin: true,
      timeout: 30_000,
      proxyTimeout: 30_000,
      configure(server) {
        server.on('proxyReq', (request) => {
          // The MCP validates Origin against Host. Forward our fixed upstream origin.
          request.setHeader('Origin', new URL(target).origin)
        })
      },
    },
  }
  return {
    plugins: [react()],
    server: { port: 5173, strictPort: true, proxy },
    preview: { port: 4173, strictPort: true, proxy },
    build: {
      rollupOptions: {
        output: {
          manualChunks(id) {
            if (/node_modules\/(react|react-dom|scheduler)\//.test(id)) return 'react'
            if (/node_modules\/(antd|@ant-design|@rc-component|rc-[^/]+)\//.test(id)) return 'antd'
            if (id.includes('node_modules') && /react-markdown|remark-|rehype-|highlight.js|micromark|mdast|hast|unist/.test(id)) return 'markdown'
          },
        },
      },
    },
  }
})
