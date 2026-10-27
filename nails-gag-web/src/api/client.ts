import type { paths } from './schema'

/** Операция GET адреса, если она есть. */
type GetOperation<P extends keyof paths> = paths[P] extends { get: infer O } ? O : never

/** Тело успешного ответа (200, JSON). */
type JsonOk<O> = O extends { responses: { 200: { content: { 'application/json': infer T } } } }
  ? T
  : never

/** Параметры строки запроса. */
type Query<O> = O extends { parameters: { query?: infer Q } } ? NonNullable<Q> : never

/**
 * Адреса, которые читает этот помощник: GET с JSON-ответом и без параметров пути — подставлять их
 * пока некому.
 */
export type JsonGetPath = {
  [P in keyof paths]: GetOperation<P> extends {
    parameters: { path?: never }
    responses: { 200: { content: { 'application/json': unknown } } }
  }
    ? P
    : never
}[keyof paths]

/** Ответ сервера с кодом не из 2xx. */
export class ApiError extends Error {
  readonly status: number

  constructor(path: string, status: number) {
    super(`GET ${path} failed with HTTP ${status}`)
    this.name = 'ApiError'
    this.status = status
  }
}

/**
 * GET к API сайта (тот же origin: в разработке Vite проксирует /api на бэкенд). Типы адреса,
 * параметров и ответа — из src/api/schema.ts. Код не из 2xx — {@link ApiError}; сетевой сбой и
 * отмена через signal — исключения fetch.
 */
export async function apiGet<P extends JsonGetPath>(
  path: P,
  options: { query?: Query<GetOperation<P>>; signal?: AbortSignal } = {},
): Promise<JsonOk<GetOperation<P>>> {
  const params = new URLSearchParams()
  for (const [name, value] of Object.entries(options.query ?? {})) {
    if (value !== undefined && value !== null) params.append(name, String(value))
  }
  const search = params.toString()
  const response = await fetch(search ? `${path}?${search}` : path, {
    headers: { Accept: 'application/json' },
    signal: options.signal,
  })
  if (!response.ok) throw new ApiError(path, response.status)
  return (await response.json()) as JsonOk<GetOperation<P>>
}
