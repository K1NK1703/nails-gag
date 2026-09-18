import '@fontsource-variable/inter'
import './styles/tokens.css'
import './styles/global.css'
import { StrictMode } from 'react'
import { createRoot, hydrateRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router'
import { AppRoutes } from './App'
import { track } from './lib/analytics'

const root = document.getElementById('root')!
const app = (
  <StrictMode>
    <BrowserRouter>
      <AppRoutes />
    </BrowserRouter>
  </StrictMode>
)

// В dev внутри #root лежит только комментарий <!--app-html-->, это не пререндер.
const prerendered = root.firstElementChild !== null

if (prerendered) {
  hydrateRoot(root, app)
} else {
  createRoot(root).render(app)
}

track({ name: 'page_view', path: window.location.pathname })
