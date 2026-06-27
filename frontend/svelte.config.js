import adapter from '@sveltejs/adapter-static';
import { vitePreprocess } from '@sveltejs/vite-plugin-svelte';

/** @type {import('@sveltejs/kit').Config} */
const config = {
  preprocess: vitePreprocess(),
  kit: {
    // SPA build: a single fallback shell serves the client-side routes. The static output can be
    // served standalone or bundled into the backend (FR-008). When bundled, the backend serves it
    // under /ui, so BASE_PATH=/ui is set for that build.
    adapter: adapter({ fallback: 'index.html' }),
    paths: { base: process.env.BASE_PATH ?? '' },
  },
};

export default config;
