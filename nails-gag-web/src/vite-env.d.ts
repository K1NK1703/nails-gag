/// <reference types="vite/client" />

declare const __BUILD_YEAR__: number

interface ImportMetaEnv {
  readonly VITE_FEATURE_CABINET?: string
  readonly VITE_ANALYTICS_ENDPOINT?: string
  readonly VITE_SITE_URL?: string
}
