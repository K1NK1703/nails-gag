import { StrictMode } from 'react'
import { renderToString } from 'react-dom/server'
import { StaticRouter } from 'react-router'
import { AppRoutes } from './App'
import { headFor } from './lib/seo'

/** Рендерит страницу для пререндера: разметка #root и содержимое <head>. */
export function render(url: string, siteUrl: string): { html: string; head: string } {
  const html = renderToString(
    <StrictMode>
      <StaticRouter location={url}>
        <AppRoutes />
      </StaticRouter>
    </StrictMode>,
  )
  return { html, head: headFor(url, siteUrl) }
}
