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
expect(home.includes('<div id="root" data-route="/">'), 'home: data-route="/" missing on #root')

const privacyPage = await readFile(path.join(DIST, 'privacy/index.html'), 'utf8')
expect(!privacyPage.includes('<!--app-html-->'), 'privacy: <!--app-html--> not replaced')
expect(!privacyPage.includes('<!--app-head-->'), 'privacy: <!--app-head--> not replaced')
expect(
  privacyPage.includes('Политика обработки персональных данных'),
  'privacy: policy title text missing',
)
const termsPage = await readFile(path.join(DIST, 'terms/index.html'), 'utf8')
expect(!termsPage.includes('<!--app-html-->'), 'terms: <!--app-html--> not replaced')
expect(
  /<title>[^<]*Пользовательское соглашение/.test(termsPage),
  'terms: title does not contain «Пользовательское соглашение»',
)

const cabinetPage = await readFile(path.join(DIST, 'cabinet/index.html'), 'utf8')
expect(
  cabinetPage.includes('<div id="root" data-route="/cabinet">'),
  'cabinet: data-route="/cabinet" missing on #root',
)
expect(cabinetPage.includes('<meta name="robots" content="noindex"'), 'cabinet: noindex missing')
const bookingPage = await readFile(path.join(DIST, 'booking/index.html'), 'utf8')
expect(bookingPage.includes('<meta name="robots" content="noindex"'), 'booking: noindex missing')
expect(!home.includes('name="robots"'), 'home: must be indexable')

const sitemap = await readFile(path.join(DIST, 'sitemap.xml'), 'utf8')
const locs = [...sitemap.matchAll(/<loc>([^<]+)<\/loc>/g)].map((m) => new URL(m[1]).pathname)
expect(
  locs.length === 3 && ['/', '/privacy', '/terms'].every((p) => locs.includes(p)),
  `sitemap: expected exactly /, /privacy, /terms, got ${locs.join(', ')}`,
)
const robots = await readFile(path.join(DIST, 'robots.txt'), 'utf8')
for (const p of ['/cabinet', '/booking']) {
  expect(robots.includes(`Disallow: ${p}\n`), `robots.txt: Disallow: ${p} missing`)
}

// Реквизиты оператора и подрядчиков заполняет владелец перед публикацией, поэтому это предупреждение, не ошибка.
const placeholders = [...new Set(`${privacyPage}${termsPage}`.match(/\{\{[A-Z_]+\}\}/g) ?? [])]
if (placeholders.length) {
  warnings.push(
    `check-dist WARN: placeholders not filled in src/content/placeholders.ts: ${placeholders.join(', ')}`,
  )
}

// Каждая пререндеренная страница (оба варианта файла) несёт свой <title>.
const TITLES = {
  'index.html': 'маникюр в Санкт-Петербурге',
  'privacy/index.html': 'Политика обработки персональных данных',
  'privacy.html': 'Политика обработки персональных данных',
  'terms/index.html': 'Пользовательское соглашение',
  'terms.html': 'Пользовательское соглашение',
  'booking/index.html': 'Онлайн-запись',
  'booking.html': 'Онлайн-запись',
  'cabinet/index.html': 'Личный кабинет',
  'cabinet.html': 'Личный кабинет',
}
for (const [file, expected] of Object.entries(TITLES)) {
  let html
  try {
    html = await readFile(path.join(DIST, file), 'utf8')
  } catch {
    failures.push(`missing ${file}`)
    continue
  }
  const title = /<title>([^<]*)<\/title>/.exec(html)?.[1] ?? ''
  expect(title.includes(expected), `${file}: <title> «${title}» does not contain «${expected}»`)
}

for (const f of ['sitemap.xml', 'robots.txt', 'og.jpg', 'favicon.svg']) {
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
