/// <reference types="vitest" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The authoring app is served by Spring under /author/ and coexists with the vanilla
// /examples/gateway showcase (decision D12: coexist, then unify).
export default defineConfig({
  base: '/author/',
  plugins: [react()],
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
  server: {
    // During local dev (npm run dev) proxy API calls to the running Spring app.
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts', 'src/**/*.test.tsx'],
  },
});
