import { test, expect } from '@playwright/test';

// Assumes the backend is running on :8080 with auth disabled and seeded with
// releases/com/example/widget/1.0.0/widget-1.0.0.jar (+ .pom, .sha1). See scripts/e2e.sh.
test('browse to an artifact, view details, then delete it', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Repositories' })).toBeVisible();

  await page.getByRole('link', { name: 'releases' }).click();

  for (const folder of ['com/', 'example/', 'widget/', '1.0.0/']) {
    await page.getByRole('link', { name: folder, exact: true }).click();
  }

  // File details (exact name so the .jar.sha1 sibling doesn't also match).
  const jarButton = page.getByRole('button', { name: 'widget-1.0.0.jar', exact: true });
  await jarButton.click();
  await expect(page.getByTestId('details')).toContainText('bytes');
  await expect(page.getByTestId('download')).toBeVisible();

  // Delete the jar (auth disabled → no credential prompt; accept the confirm dialog).
  page.on('dialog', (d) => d.accept());
  const jarRow = page.locator('tr').filter({ has: jarButton });
  await jarRow.getByTestId('delete').click();
  await expect(page.getByRole('button', { name: 'widget-1.0.0.jar', exact: true })).toHaveCount(0);
});
