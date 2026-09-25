import { privacy } from '../content/privacy'
import { site } from '../content/site'
import { terms } from '../content/terms'

type Meta = { title: string; description: string; jsonLd?: Record<string, unknown> }

/** Заголовок вкладки для маршрута: общий источник правды для пререндера и клиента. */
export function titleFor(path: string): string {
  switch (path) {
    case '/':
      return `${site.name} — маникюр в Санкт-Петербурге, чёткие квадраты`
    case '/privacy':
      return `${privacy.title} — ${site.name}`
    case '/terms':
      return `${terms.title} — ${site.name}`
    case '/booking':
      return `Онлайн-запись — ${site.name}`
    case '/cabinet':
      return `Личный кабинет — ${site.name}`
    default:
      return `Страница не найдена — ${site.name}`
  }
}

const HOME: Meta = {
  title: titleFor('/'),
  description: site.description,
  jsonLd: {
    '@context': 'https://schema.org',
    '@type': 'NailSalon',
    name: site.name,
    description: site.description,
    priceRange: '₽₽',
    address: {
      '@type': 'PostalAddress',
      streetAddress: site.address.street,
      addressLocality: site.address.locality,
      addressCountry: site.address.country,
    },
    areaServed: site.city,
    sameAs: [site.channelUrl],
  },
}

const PRIVACY: Meta = {
  title: titleFor('/privacy'),
  description: `Как сайт ${site.name} обращается с данными посетителей.`,
}

const TERMS: Meta = {
  title: titleFor('/terms'),
  description: `Условия записи, отмены, оплаты и гарантии в ${site.name}.`,
}

const BOOKING: Meta = {
  title: titleFor('/booking'),
  description: `Онлайн-запись на маникюр в ${site.name}.`,
}

const META: Record<string, Meta> = {
  '/': HOME,
  '/privacy': PRIVACY,
  '/terms': TERMS,
  '/booking': BOOKING,
}

function metaFor(path: string): Meta {
  // Неизвестный маршрут не должен выдавать себя за главную в schema.org.
  return META[path] ?? { title: titleFor(path), description: HOME.description }
}

function escape(s: string): string {
  return s.replaceAll('&', '&amp;').replaceAll('"', '&quot;').replaceAll('<', '&lt;')
}

/** HTML для <head>: title, description, canonical, Open Graph и JSON-LD. */
export function headFor(path: string, siteUrl: string): string {
  const base = siteUrl.replace(/\/$/, '')
  const url = `${base}${path === '/' ? '/' : path}`
  const meta = metaFor(path)
  const parts = [
    `<title>${escape(meta.title)}</title>`,
    `<meta name="description" content="${escape(meta.description)}" />`,
    `<link rel="canonical" href="${url}" />`,
    `<meta property="og:type" content="website" />`,
    `<meta property="og:title" content="${escape(meta.title)}" />`,
    `<meta property="og:description" content="${escape(meta.description)}" />`,
    `<meta property="og:url" content="${url}" />`,
    `<meta property="og:image" content="${base}/og.jpg" />`,
    `<meta property="og:image:width" content="1200" />`,
    `<meta property="og:image:height" content="630" />`,
    `<meta property="og:site_name" content="${escape(site.name)}" />`,
    `<meta property="og:locale" content="ru_RU" />`,
    `<meta name="twitter:card" content="summary_large_image" />`,
  ]
  if (meta.jsonLd) {
    const ld = { ...meta.jsonLd, url: `${base}/` }
    // Экранируем угловую скобку, чтобы содержимое JSON не закрыло script раньше времени.
    const json = JSON.stringify(ld).replaceAll('<', '\\u003c')
    parts.push(`<script type="application/ld+json">${json}</script>`)
  }
  return parts.join('\n    ')
}
