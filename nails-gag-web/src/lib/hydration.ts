/** Путь без завершающего слеша: /privacy/ и /privacy — один маршрут. */
export function normalizePath(pathname: string): string {
  return pathname.replace(/\/+$/, '') || '/'
}

/**
 * Гидрировать можно, только если разметка в #root пререндерена для текущего пути
 * (prerender.ts ставит data-route). Иначе, например когда SPA-фолбэк отдал HTML
 * главной на неизвестный путь, клиент должен отрендерить страницу заново.
 */
export function canHydrate(route: string | undefined, pathname: string): boolean {
  return route !== undefined && normalizePath(route) === normalizePath(pathname)
}
