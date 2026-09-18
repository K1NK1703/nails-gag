/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

export default defineConfig(({ isSsrBuild }) => ({
  plugins: [react()],
  define: {
    __BUILD_YEAR__: JSON.stringify(new Date().getFullYear()),
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
