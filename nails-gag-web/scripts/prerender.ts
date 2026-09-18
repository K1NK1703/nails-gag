import { mkdir, readdir, readFile, rm, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { pathToFileURL } from 'node:url'
import { loadEnv } from 'vite'

const ROOT = path.resolve(import.meta.dirname, '..')
const DIST = path.join(ROOT, 'dist')
const DIST_SSR = path.join(ROOT, 'dist-ssr')
// tsx не читает .env сам, поэтому берём переменную через loadEnv, как это делает vite.
const SITE_URL = (
  process.env.VITE_SITE_URL ??
  loadEnv('production', ROOT, 'VITE_').VITE_SITE_URL ??
  'https://nails-gag.ru'
).replace(/\/$/, '')
const ROUTES = ['/', '/privacy']

type Render = (url: string, siteUrl: string) => { html: string; head: string }

async function loadRender(): Promise<Render> {
  const files = await readdir(DIST_SSR)
  const entry = files.find((f) => f.startsWith('entry-server') && f.endsWith('.js'))
  if (!entry) throw new Error(`entry-server.js not found in ${DIST_SSR}`)
  const mod: unknown = await import(pathToFileURL(path.join(DIST_SSR, entry)).href)
  const render = (mod as { render?: unknown }).render
  if (typeof render !== 'function') {
    throw new Error(`${entry} does not export render()`)
  }
  return render as Render
}

async function main() {
  const template = await readFile(path.join(DIST, 'index.html'), 'utf8')
  if (!template.includes('<!--app-html-->') || !template.includes('<!--app-head-->')) {
    throw new Error('dist/index.html has no <!--app-html--> / <!--app-head--> placeholders')
  }
  const render = await loadRender()

  for (const route of ROUTES) {
    const { html, head } = render(route, SITE_URL)
    // Функции-заменители: в разметке могут встретиться $& и подобные последовательности.
    const page = template
      .replace('<!--app-head-->', () => head)
      .replace('<!--app-html-->', () => html)
    // Для не-корневых маршрутов пишем оба варианта, чтобы работали и try_files {path}.html,
    // и {path}/index.html.
    const files =
      route === '/'
        ? ['index.html']
        : [path.join(route.slice(1), 'index.html'), `${route.slice(1)}.html`]
    for (const file of files) {
      const target = path.join(DIST, file)
      await mkdir(path.dirname(target), { recursive: true })
      await writeFile(target, page, 'utf8')
      console.log('prerendered', route, '->', file)
    }
  }

  const today = new Date().toISOString().slice(0, 10)
  const sitemap =
    `<?xml version="1.0" encoding="UTF-8"?>\n` +
    `<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n` +
    ROUTES.map((r) => `  <url><loc>${SITE_URL}${r}</loc><lastmod>${today}</lastmod></url>`).join(
      '\n',
    ) +
    `\n</urlset>\n`
  await writeFile(path.join(DIST, 'sitemap.xml'), sitemap, 'utf8')
  console.log('sitemap.xml written')

  const robots = `User-agent: *\nAllow: /\nSitemap: ${SITE_URL}/sitemap.xml\n`
  await writeFile(path.join(DIST, 'robots.txt'), robots, 'utf8')
  console.log('robots.txt written')
}

try {
  await main()
} catch (e: unknown) {
  console.error(e)
  process.exitCode = 1
} finally {
  await rm(DIST_SSR, { recursive: true, force: true })
}
