/// <reference types="vitest" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The same SPA is copied under /author/, /libraries/, /rehearsals/, and /showcase/, so assets stay relative.
export default defineConfig({
  base: './',
  plugins: [react()],
  build: {
    outDir: 'dist',
    emptyOutDir: true,
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
          return undefined;
        },
      },
    },
  },
  server: {
    // During local dev (npm run dev) proxy API calls to the running Spring app.
    proxy: {
      '/api': 'http://localhost:8080',
      '/admin': 'http://localhost:8080',
    },
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts', 'src/**/*.test.tsx'],
  },
});
