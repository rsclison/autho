import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import path from 'path'

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: { '@': path.resolve(__dirname, './src') }
  },
  server: {
    port: 3000,
    proxy: {
      '/isAuthorized':   'http://localhost:8080',
      '/whoAuthorized':  'http://localhost:8080',
      '/whatAuthorized': 'http://localhost:8080',
      '/explain':        'http://localhost:8080',
      '/policies':       'http://localhost:8080',
      '/policy':         'http://localhost:8080',
      '/v1':             'http://localhost:8080',
      '/admin':          'http://localhost:8080',
      '/status':         'http://localhost:8080',
      '/health':         'http://localhost:8080',
      '/metrics':        'http://localhost:8080',
    }
  },
  base: '/admin/',
  build: {
    outDir: '../resources/public/admin',
    emptyOutDir: true,
    assetsInlineLimit: 4096,
    rollupOptions: {
      output: {
        manualChunks(id: string) {
          if (!id.includes('node_modules')) return
          if (id.includes('monaco-editor') || id.includes('@monaco-editor')) return 'vendor-monaco'
          if (id.includes('recharts') || id.includes('d3-') || id.includes('victory-vendor')) return 'vendor-charts'
          if (id.includes('react-hook-form') || id.includes('/zod/') || id.includes('@hookform')) return 'vendor-forms'
          if (id.includes('@tanstack/react-query')) return 'vendor-query'
          if (id.includes('@tanstack/react-table')) return 'vendor-table'
          if (id.includes('react-router') || id.includes('react-dom') || id.includes('/react/')) return 'vendor-react'
        }
      }
    }
  },
  appType: 'spa',
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/test/setup.ts',
  },
})
