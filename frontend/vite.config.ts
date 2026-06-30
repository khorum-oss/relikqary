import { sveltekit } from '@sveltejs/kit/vite';
import { defineConfig } from 'vite';

// During `vite dev`, proxy the backend API and any Maven repo path (so downloads/uploads to ANY
// repository work, not just releases/snapshots) to the running backend. The SPA's own client routes
// live at `/`, `/r/...`, and the top-level sections `/dashboard`, `/publish`, `/users`, `/settings`
// (feature 016), and Vite's dev assets under `/@…`, `/_app`, `/src`, `/node_modules`, `/.svelte-kit`;
// everything else with a path segment is a backend request. Override with RELIKQUARY_BACKEND. In the
// bundled (production) build the UI is same-origin with the backend (adapter-static fallback serves
// these routes), so no proxy is involved.
const backend = process.env.RELIKQUARY_BACKEND ?? 'http://localhost:8080';

export default defineConfig({
  plugins: [sveltekit()],
  server: {
    proxy: {
      '^/(?!r(?:/|$)|(?:dashboard|publish|users|settings)(?:/|$)|_app/|@|src/|node_modules/|\\.svelte-kit/|favicon)[^/].*':
        backend,
    },
  },
});
