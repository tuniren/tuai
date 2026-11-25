import { memo, useEffect, useMemo } from 'react'
import Markdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import rehypeHighlight from 'rehype-highlight'
import type { Root } from 'hast'
import { App, Button } from 'antd'
import { CopyOutlined } from '@ant-design/icons'
import { headingsFromTree } from '../lib/markdown'
import type { Heading } from '../lib/markdown'

interface Props {
  content: string
  title: string
  onHeadings: (headings: Heading[]) => void
  onLink: (href: string) => void
}

function CodeBlock({ children }: { children?: React.ReactNode }) {
  const { message } = App.useApp()
  return <div className="code-block">
    <Button className="code-copy" size="small" type="text" icon={<CopyOutlined />} aria-label="复制代码" onClick={async (event) => {
      const code = event.currentTarget.closest('.code-block')?.querySelector('code')?.textContent ?? ''
      try { await navigator.clipboard.writeText(code); void message.success('代码已复制') } catch { void message.error('无法访问剪贴板，请手动复制') }
    }} />
    <pre>{children}</pre>
  </div>
}

export default memo(function MarkdownReader({ content, title, onHeadings, onLink }: Props) {
  const displayContent = useMemo(() => {
    const firstLine = content.split('\n', 1)[0]
    return firstLine.startsWith('# ') && firstLine.slice(2).trim() === title.trim() ? content.slice(firstLine.length).trimStart() : content
  }, [content, title])
  const processed = useMemo(() => {
    const headings: Heading[] = []
    const plugin = () => (tree: Root) => { headings.splice(0, headings.length, ...headingsFromTree(tree)) }
    return { headings, plugin }
  }, [displayContent])
  useEffect(() => { onHeadings(processed.headings) }, [processed, onHeadings])

  return <div className="markdown-body">
    <Markdown remarkPlugins={[remarkGfm]} rehypePlugins={[[rehypeHighlight, { detect: false, ignoreMissing: true }], processed.plugin]}
      components={{
        pre: CodeBlock,
        a: ({ href, children, ...props }) => {
          const external = /^(https?:|mailto:)/i.test(href ?? '')
          return <a {...props} href={href} target={external ? '_blank' : undefined} rel={external ? 'noopener noreferrer' : undefined}
            onClick={(event) => { if (href && !external) { event.preventDefault(); onLink(href) } }}>{children}</a>
        },
        table: ({ children }) => <div className="table-scroll"><table>{children}</table></div>,
        img: ({ src, alt }) => <img src={src} alt={alt ?? ''} loading="lazy" referrerPolicy="no-referrer" />,
      }}
    >{displayContent}</Markdown>
  </div>
})
