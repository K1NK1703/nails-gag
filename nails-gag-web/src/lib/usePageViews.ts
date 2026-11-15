import { useEffect, useRef } from 'react'
import { useLocation } from 'react-router'
import { track } from './analytics'

/** Публичные страницы сайта. Кабинет, админку и 404 счётчик посещений не видит. */
const PUBLIC_PATHS = new Set(['/', '/terms', '/privacy', '/booking'])

export type PageViewTracker = (pathname: string) => void

/**
 * Счётчик просмотров на одну загрузку страницы. Первый отправленный просмотр — вход на сайт:
 * `entry: true` и хост, с которого пришли. Непубличные пути не отправляются и вход не расходуют.
 */
export function createPageViewTracker(): PageViewTracker {
  let entrySent = false
  return (pathname) => {
    const path = publicPath(pathname)
    if (path === null) return
    if (entrySent) {
      track({ name: 'page_view', path })
      return
    }
    entrySent = true
    track({ name: 'page_view', path, entry: true, referrer: referrerHost() })
  }
}

/** Один на загрузку страницы: SPA-переходы его не сбрасывают. */
const pageViews = createPageViewTracker()

/** Отправляет `page_view` при каждой смене пути. */
export function usePageViews(trackPageView: PageViewTracker = pageViews): void {
  const { pathname } = useLocation()
  const sentFor = useRef<string | null>(null)

  useEffect(() => {
    // StrictMode в разработке повторяет эффект с тем же путём — второй просмотр не считаем.
    if (sentFor.current === pathname) return
    sentFor.current = pathname
    trackPageView(pathname)
  }, [pathname, trackPageView])
}

/**
 * Путь публичной страницы в том виде, в каком его ждёт бэкенд, или `null`. Роутер сопоставляет
 * маршруты без учёта регистра и завершающего слеша (`/Terms/` — это `/terms`), так же и здесь.
 */
function publicPath(pathname: string): string | null {
  const path = pathname.replace(/\/+$/, '').toLowerCase() || '/'
  return PUBLIC_PATHS.has(path) ? path : null
}

/** Только хост источника: без схемы, порта, пути и параметров; нет или не разобрался — ''. */
function referrerHost(): string {
  try {
    return new URL(document.referrer).hostname
  } catch {
    return ''
  }
}
