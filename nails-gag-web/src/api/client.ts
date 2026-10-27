import type { paths } from './schema'

/**
 * Адреса, которые читает этот помощник: есть GET, у него нет параметров пути (подставлять их пока
 * некому), успешный ответ — JSON.
 */
export type JsonGetPath = {
  [P in keyof paths]: paths[P] extends {
    get: {
      parameters: { path?: never }
      responses: { 200: { content: { 'application/json': unknown } } }
    }
  }
    ? P
    : never
}[keyof paths]

type GetOperation<P extends JsonGetPath> = paths[P]['get']

/** Тело успешного ответа (200, JSON). */
type JsonOk<O> = O extends { responses: { 200: { content: { 'application/json': infer T } } } }
  ? T
  : never

type Options<Q> = { query?: Q; signal?: AbortSignal }

/**
 * Второй аргумент apiGet: обязателен, если у операции есть обязательные параметры строки запроса.
 * Необязательный ключ (query?: …) не подходит под { query: … }, поэтому идёт во вторую ветку.
 */
type OptionsArgs<O> = O extends { parameters: { query: infer Q } }
  ? object extends Q
    ? [options?: Options<Q>]
    : [options: Options<Q> & { query: Q }]
  : O extends { parameters: { query?: infer Q } }
    ? [options?: Options<NonNullable<Q>>]
    : [options?: Options<never>]

/** Ответ сервера с кодом не из 2xx. */
export class ApiError extends Error {
  readonly status: number
  /** Машинный код ошибки из тела Problem (RFC 9457), если сервер его прислал. */
  readonly code: string | undefined

  constructor(path: string, status: number, code?: string) {
    super(`GET ${path} failed with HTTP ${status}${code ? ` (${code})` : ''}`)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

/** Код ошибки из тела ответа, если тело — Problem с полем code; иначе undefined. */
async function problemCode(response: Response): Promise<string | undefined> {
  const body: unknown = await response.json().catch(() => null)
  if (typeof body !== 'object' || body === null || !('code' in body)) return undefined
  return typeof body.code === 'string' ? body.code : undefined
}

/**
 * GET к API сайта (тот же origin: в разработке Vite проксирует /api на бэкенд). Типы адреса,
 * параметров и ответа — из src/api/schema.ts. Параметры со значением undefined или null в строку
 * запроса не попадают. Код не из 2xx — {@link ApiError}; успешный ответ не в JSON — Error с
 * адресом; сетевой сбой и отмена через signal — исключения fetch.
 */
export async function apiGet<P extends JsonGetPath>(
  path: P,
  ...[options]: OptionsArgs<GetOperation<P>>
): Promise<JsonOk<GetOperation<P>>> {
  const params = new URLSearchParams()
  const query: object = options?.query ?? {}
  for (const [name, value] of Object.entries(query)) {
    if (value !== undefined && value !== null) params.append(name, String(value))
  }
  const search = params.toString()
  const response = await fetch(search ? `${path}?${search}` : path, {
    headers: { Accept: 'application/json' },
    signal: options?.signal,
  })
  if (!response.ok) throw new ApiError(path, response.status, await problemCode(response))
  try {
    return (await response.json()) as JsonOk<GetOperation<P>>
  } catch (e) {
    throw new Error(`GET ${path}: response is not JSON`, { cause: e })
  }
}
