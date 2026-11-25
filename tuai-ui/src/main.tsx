import React from 'react'
import ReactDOM from 'react-dom/client'
import { App as AntApp, ConfigProvider } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import Router from './Router'
import 'antd/dist/reset.css'
import 'highlight.js/styles/github.css'
import './styles.css'

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ConfigProvider locale={zhCN} theme={{ token: {
      colorPrimary: '#0969da', colorInfo: '#0969da', colorText: '#1f2328',
      colorBorder: '#d1d9e0', colorBgLayout: '#f6f8fa', borderRadius: 6, fontSize: 14,
      fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", "Noto Sans", Helvetica, Arial, sans-serif',
      controlHeight: 32,
    } }}>
      <AntApp><Router /></AntApp>
    </ConfigProvider>
  </React.StrictMode>,
)
