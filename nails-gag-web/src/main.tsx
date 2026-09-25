import './styles/fonts.css'
import './styles/tokens.css'
import './styles/global.css'
import { StrictMode } from 'react'
import { createRoot, hydrateRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router'
import { AppRoutes } from './App'
import { canHydrate } from './lib/hydration'

const root = document.getElementById('root')!
// useTransitions={false}: иначе BrowserRouter оборачивает смену location в startTransition,
// и flushSync(navigate) внутри document.startViewTransition не успевает закоммитить новый
// маршрут до снимка «после» (см. TransitionLink).
const app = (
  <StrictMode>
    <BrowserRouter useTransitions={false}>
      <AppRoutes />
    </BrowserRouter>
  </StrictMode>
)

if (canHydrate(root.dataset['route'], window.location.pathname)) {
  hydrateRoot(root, app)
} else {
  // В dev в #root лежит только <!--app-html-->; на чужом пути — пререндер другого маршрута.
  root.replaceChildren()
  createRoot(root).render(app)
}
