import { defineConfig, devices } from '@playwright/test';

// Drives the real built SvelteKit app (via `vite dev`, which proxies /api and repo downloads to the
// backend) in a real Chromium. The backend must be running on :8080 (see scripts/e2e.sh).
// RELIKQUARY_CHROMIUM_PATH points at a pre-installed Chromium (set by scripts/e2e.sh for this
// environment); when unset (e.g. CI), Playwright uses its own installed browser.
const chromiumPath = process.env.RELIKQUARY_CHROMIUM_PATH;

export default defineConfig({
  testDir: 'tests',
  timeout: 30_000,
  expect: { timeout: 7_000 },
  use: {
    baseURL: 'http://localhost:5173',
    launchOptions: {
      ...(chromiumPath ? { executablePath: chromiumPath } : {}),
      args: ['--no-sandbox', '--disable-dev-shm-usage'],
    },
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'npm run dev -- --port 5173 --host 127.0.0.1',
    port: 5173,
    reuseExistingServer: true,
    timeout: 120_000,
  },
});
