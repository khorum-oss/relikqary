import { test, expect } from '@playwright/test';

// Anonymous flow against an auth-enabled backend (scripts/e2e.sh): the open 'releases' repo browses and
// downloads with no login. Authenticated actions (private repos, upload, delete) live in authz.spec.ts.
test('anonymous: browse and download from an open repository', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Repositories' })).toBeVisible();

  // The configured-repository list lives behind the Repositories tab (the catalog is the default view).
  await page.getByTestId('repos-tab').click();

  // Repository kind is shown (feature 008).
  const releasesRow = page.getByTestId('repo-row').filter({ hasText: 'releases' });
  await expect(releasesRow.getByTestId('repo-kind')).toHaveText('hosted');

  await page.getByRole('link', { name: 'releases' }).click();
  for (const folder of ['com/', 'example/', 'widget/', '1.0.0/']) {
    await page.getByRole('link', { name: folder, exact: true }).click();
  }

  const jarButton = page.getByRole('button', { name: 'widget-1.0.0.jar', exact: true });
  await jarButton.click();
  await expect(page.getByTestId('details')).toContainText('bytes');

  // Download is a credentialed fetch + blob save; anonymous works for the open repo.
  const [download] = await Promise.all([
    page.waitForEvent('download'),
    page.getByTestId('download').click(),
  ]);
  expect(download.suggestedFilename()).toBe('widget-1.0.0.jar');
});
