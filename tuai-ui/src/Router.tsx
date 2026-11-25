import { useEffect, useState } from 'react'
import App from './App'
import SearchPage from './pages/SearchPage'

/** Small route switch for the reader and independently addressable search page. */
export default function Router() {
  const [path, setPath] = useState(window.location.pathname)
  useEffect(() => {
    const listener = () => setPath(window.location.pathname)
    window.addEventListener('popstate', listener)
    return () => window.removeEventListener('popstate', listener)
  }, [])
  return path.replace(/\/$/, '') === '/search' ? <SearchPage /> : <App />
}
