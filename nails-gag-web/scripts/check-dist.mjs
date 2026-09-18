import { access, readFile } from 'node:fs/promises'
import path from 'node:path'

const DIST = path.resolve(import.meta.dirname, '..', 'dist')
const failures = []

function expect(cond, msg) {
  if (!cond) failures.push(msg)
}

const home = await readFile(path.join(DIST, 'index.html'), 'utf8')
expect(/<h1[\s>]/.test(home), 'home: <h1> missing')
for (const id of ['hero', 'gallery', 'price', 'about', 'reviews', 'faq', 'contacts']) {
  expect(home.includes(`id="${id}"`), `home: section #${id} missing`)
}
expect(home.includes('application/ld+json'), 'home: JSON-LD missing')
expect(!home.includes('<!--app-html-->'), 'home: placeholder not replaced')
expect(!/instagram/i.test(home), 'home: instagram mention found')

for (const f of ['privacy/index.html', 'sitemap.xml', 'robots.txt', 'og.jpg', 'favicon.svg']) {
  try {
    await access(path.join(DIST, f))
  } catch {
    failures.push(`missing ${f}`)
  }
}

if (failures.length) {
  console.error('check-dist FAILED:\n - ' + failures.join('\n - '))
  process.exit(1)
}
console.log('check-dist OK')
