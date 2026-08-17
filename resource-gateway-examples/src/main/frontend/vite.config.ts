/// <reference types="vitest" />
import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

// The same SPA is copied under every product route, so assets stay relative.
function apiProxyTarget(mode: string): string {
  return loadEnv(mode, process.cwd(), '').VITE_DEV_API_TARGET || 'http://localhost:8080';
}

export default defineConfig(({ mode }) => ({
  base: './',
  plugins: [react()],
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    manifest: true,
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes('/node_modules/reactflow/')
              || id.includes('/node_modules/@reactflow/')) {
            return 'react-flow';
          }
          if (id.includes('/node_modules/react/')
              || id.includes('/node_modules/react-dom/')
              || id.includes('/node_modules/scheduler/')) {
            return 'react-runtime';
          }
          if (id.endsWith('/src/i18n/i18n.ts')
              || id.endsWith('/src/i18n/messageCatalog.ts')) {
            return 'locale-catalog';
          }
          if (id.endsWith('/src/draftModel.ts')
              || id.endsWith('/src/canvasExamples.ts')
              || id.endsWith('/src/author/canvas/canvasSemantics.ts')
              || id.endsWith('/src/author/contract/effectiveContractProjection.ts')) {
            return 'author-domain';
          }
          return undefined;
        },
      },
    },
  },
  server: {
    // During local dev (npm run dev) proxy API calls to the running Spring app.
    proxy: {
      '/api': apiProxyTarget(mode),
      '/admin': apiProxyTarget(mode),
    },
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts', 'src/**/*.test.tsx'],
    // Heavy React/JSDOM suites share timers and mocked transports; file-level serialism keeps CI deterministic.
    maxWorkers: 1,
  },
}));
