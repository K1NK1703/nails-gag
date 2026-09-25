import path from 'node:path'
import { loadEnv } from 'vite'

const ROOT = path.resolve(import.meta.dirname, '..')

/** Адрес сайта по умолчанию, если VITE_SITE_URL не задан ни в окружении, ни в .env. */
export const DEFAULT_SITE_URL = 'https://nails-gag.ru'

/**
 * Публичный адрес сайта без завершающего слеша. Общий источник для vite.config.ts
 * (define __SITE_URL__) и пререндера: tsx не читает .env сам, поэтому берём через loadEnv.
 */
export function resolveSiteUrl(mode: string): string {
  const url =
    process.env.VITE_SITE_URL || loadEnv(mode, ROOT, 'VITE_').VITE_SITE_URL || DEFAULT_SITE_URL
  return url.replace(/\/+$/, '')
}
