/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'
import { resolveSiteUrl } from './scripts/site-url.ts'

export default defineConfig(({ isSsrBuild, mode }) => ({
  plugins: [react()],
  define: {
    __BUILD_YEAR__: JSON.stringify(new Date().getFullYear()),
    __SITE_URL__: JSON.stringify(resolveSiteUrl(mode)),
  },
  css: {
    modules: {
      generateScopedName: '[name]_[local]_[hash:base64:5]',
    },
  },
  build: {
    // public/ нужен только клиентской сборке, в dist-ssr он лишний.
    copyPublicDir: !isSsrBuild,
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: { modules: { classNameStrategy: 'non-scoped' } },
  },
}))
