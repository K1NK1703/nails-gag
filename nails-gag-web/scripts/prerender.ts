import { mkdir, readdir, readFile, rm, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { pathToFileURL } from 'node:url'

const ROOT = path.resolve(import.meta.dirname, '..')
const DIST = path.join(ROOT, 'dist')
const DIST_SSR = path.join(ROOT, 'dist-ssr')
const SITE_URL = (process.env.VITE_SITE_URL ?? 'https://nails-gag.ru').replace(/\/$/, '')
const ROUTES = ['/', '/privacy']

type Render = (url: string, siteUrl: string) => { html: string; head: string }

async function loadRender(): Promise<Render> {
  const files = await readdir(DIST_SSR)
  const entry = files.find((f) => f.startsWith('entry-server') && f.endsWith('.js'))
  if (!entry) throw new Error(`entry-server.js not found in ${DIST_SSR}`)
  const mod = (await import(pathToFileURL(path.join(DIST_SSR, entry)).href)) as { render: Render }
  return mod.render
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
    const file = route === '/' ? 'index.html' : path.join(route.slice(1), 'index.html')
    await mkdir(path.dirname(path.join(DIST, file)), { recursive: true })
    await writeFile(path.join(DIST, file), page, 'utf8')
    console.log('prerendered', route, '->', file)
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

  await rm(DIST_SSR, { recursive: true, force: true })
}

main().catch((e: unknown) => {
  console.error(e)
  process.exit(1)
})
