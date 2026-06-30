import { test, expect } from '@playwright/test';

// Phase 2 read-only data (feature 016): the Dashboard shows live stats, and the Repositories landing
// defaults to a cross-repo artifact catalog filtered by the topbar search. Seeded by scripts/e2e.sh
// (releases/com/example/widget/1.0.0 and releases/com/example/gmodule/2.0.0).
test('dashboard stats and the searchable artifact catalog', async ({ page }) => {
  // Dashboard renders the live stat cards.
  await page.goto('/dashboard');
  await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible();
  await expect(page.getByTestId('stat-cards')).toContainText('Total Artifacts');
  await expect(page.getByTestId('stat-cards')).toContainText('Repositories');

  // The catalog is the default Repositories view: one row per group:artifact.
  await page.goto('/');
  await expect(page.getByTestId('catalog')).toBeVisible();
  const widget = page.getByTestId('catalog-row').filter({ hasText: 'com.example:widget' });
  await expect(widget).toBeVisible();

  // Topbar search filters the catalog to matching coordinates.
  await page.getByTestId('topbar-search').fill('gmodule');
  await expect(page.getByTestId('catalog-row').filter({ hasText: 'com.example:gmodule' })).toBeVisible();
  await expect(page.getByTestId('catalog-row').filter({ hasText: 'com.example:widget' })).toHaveCount(0);

  // A catalog row opens the artifact's raw folder view (its version directories).
  await page.getByTestId('topbar-search').fill('');
  await widget.getByRole('link').click();
  await expect(page.getByRole('link', { name: '1.0.0/', exact: true })).toBeVisible();
});
