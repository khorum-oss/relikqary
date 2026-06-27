import { sveltekit } from '@sveltejs/kit/vite';
import { defineConfig } from 'vite';

// During `vite dev`, proxy the backend API and the default repo download roots to the running
// backend so the SPA works at the same origin. Override with RELIKQUARY_BACKEND.
const backend = process.env.RELIKQUARY_BACKEND ?? 'http://localhost:8080';

export default defineConfig({
  plugins: [sveltekit()],
  server: {
    proxy: {
      '/api': backend,
      '/releases': backend,
      '/snapshots': backend,
    },
  },
});
