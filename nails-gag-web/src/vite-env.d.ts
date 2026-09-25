/// <reference types="vite/client" />

declare const __BUILD_YEAR__: number
/** Публичный адрес сайта без завершающего слеша, см. scripts/site-url.ts. */
declare const __SITE_URL__: string

interface ImportMetaEnv {
  readonly VITE_FEATURE_CABINET?: string
  readonly VITE_PREVIEW_USER?: string
  readonly VITE_ANALYTICS_ENDPOINT?: string
  readonly VITE_SITE_URL?: string
}
