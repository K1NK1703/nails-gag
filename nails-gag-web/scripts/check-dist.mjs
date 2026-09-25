import { access, readFile } from 'node:fs/promises'
import path from 'node:path'

const DIST = path.resolve(import.meta.dirname, '..', 'dist')
const failures = []
const warnings = []

function expect(cond, msg) {
  if (!cond) failures.push(msg)
}

function fail(msg) {
  console.error(msg)
  process.exit(1)
}

try {
  await access(DIST)
} catch {
  fail('check-dist FAILED: run npm run build first')
}

const home = await readFile(path.join(DIST, 'index.html'), 'utf8')
expect(/<h1[\s>]/.test(home), 'home: <h1> missing')
for (const id of ['hero', 'gallery', 'price', 'about', 'reviews', 'faq', 'contacts']) {
  expect(home.includes(`id="${id}"`), `home: section #${id} missing`)
}
expect(home.includes('application/ld+json'), 'home: JSON-LD missing')
expect(!home.includes('<!--app-html-->'), 'home: <!--app-html--> not replaced')
expect(!home.includes('<!--app-head-->'), 'home: <!--app-head--> not replaced')
expect(!/instagram/i.test(home), 'home: instagram mention found')

const privacyPage = await readFile(path.join(DIST, 'privacy/index.html'), 'utf8')
expect(!privacyPage.includes('<!--app-html-->'), 'privacy: <!--app-html--> not replaced')
expect(!privacyPage.includes('<!--app-head-->'), 'privacy: <!--app-head--> not replaced')
expect(privacyPage.includes('Политика конфиденциальности'), 'privacy: policy title text missing')
// Имя оператора заполняет владелец перед публикацией, поэтому это предупреждение, не ошибка.
if (privacyPage.includes('{{OPERATOR}}')) {
  warnings.push('check-dist WARN: {{OPERATOR}} is not filled in src/content/privacy.ts')
}

for (const f of [
  'privacy/index.html',
  'privacy.html',
  'booking/index.html',
  'sitemap.xml',
  'robots.txt',
  'og.jpg',
  'favicon.svg',
]) {
  try {
    await access(path.join(DIST, f))
  } catch {
    failures.push(`missing ${f}`)
  }
}

for (const w of warnings) console.warn(w)

if (failures.length) {
  fail('check-dist FAILED:\n - ' + failures.join('\n - '))
}
console.log('check-dist OK')
