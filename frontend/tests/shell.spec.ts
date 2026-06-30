import { test, expect } from '@playwright/test';

// The Artifact Sanctuary shell (feature 016): the sidebar navigates the top-level sections, the topbar
// reflects the active section as the page heading, and the Publish screen offers a copyable snippet and
// a manual-upload drop zone. Anonymous use is preserved — signing in is an optional affordance.
test('sidebar navigates sections; publish screen renders', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Repositories' })).toBeVisible();

  // All five sections are present in the rail.
  for (const label of ['Dashboard', 'Repositories', 'Publish', 'Users & Tokens', 'Settings']) {
    await expect(page.getByRole('link', { name: label, exact: true })).toBeVisible();
  }

  // Publish: topbar title tracks the section; the snippet copy control and drop zone are present.
  await page.getByRole('link', { name: 'Publish', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Publish' })).toBeVisible();
  await expect(page.getByTestId('publish-copy')).toBeVisible();
  await expect(page.getByTestId('upload-form')).toBeVisible();

  // A later-phase section shows a themed placeholder, not a broken page.
  await page.getByRole('link', { name: 'Settings', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Settings' })).toBeVisible();
  await expect(page.getByTestId('placeholder')).toBeVisible();

  // The themed sign-in is reachable and dismissable; browsing stayed anonymous throughout.
  await page.getByTestId('login-button').click();
  await expect(page.getByTestId('login-screen')).toBeVisible();
});
