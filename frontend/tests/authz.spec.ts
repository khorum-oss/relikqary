import { test, expect } from '@playwright/test';

// Authenticated flow against the auth-enabled backend (scripts/e2e.sh): a private repo prompts login,
// then a logged-in alice browses it, uploads a file, sees it, and deletes it.
test('login, browse a private repo, upload, then delete', async ({ page }) => {
  await page.goto('/');

  // The configured-repository list lives behind the Repositories tab (the catalog is the default view).
  await page.getByTestId('repos-tab').click();

  // The private repo shows its kind and prompts for login when opened anonymously.
  await expect(page.getByTestId('repo-row').filter({ hasText: 'private' })).toBeVisible();
  await page.getByRole('link', { name: 'private' }).click();
  await expect(page.getByTestId('login-form')).toBeVisible();

  // Log in; the private contents then load (credentials retried automatically).
  await page.getByTestId('login-username').fill('alice');
  await page.getByTestId('login-password').fill('pw');
  await page.getByTestId('login-submit').click();
  await expect(page.getByTestId('current-user')).toHaveText('alice');

  for (const folder of ['com/', 'acme/', 'lib/', '1.0.0/']) {
    await page.getByRole('link', { name: folder, exact: true }).click();
  }
  await expect(page.getByRole('button', { name: 'lib-1.0.0.jar', exact: true })).toBeVisible();

  // Upload a new file into the current folder; it appears in the listing.
  await page.getByTestId('upload-toggle').click();
  await page.getByTestId('upload-file').setInputFiles({
    name: 'notes.txt',
    mimeType: 'text/plain',
    buffer: Buffer.from('release notes'),
  });
  await expect(page.getByTestId('upload-path')).toHaveValue('com/acme/lib/1.0.0/notes.txt');
  await page.getByTestId('upload-submit').click();
  await expect(page.getByRole('button', { name: 'notes.txt', exact: true })).toBeVisible();

  // Delete it again (accept the confirm dialog).
  page.on('dialog', (d) => d.accept());
  const notesRow = page.locator('tr').filter({ has: page.getByRole('button', { name: 'notes.txt', exact: true }) });
  await notesRow.getByTestId('delete').click();
  await expect(page.getByRole('button', { name: 'notes.txt', exact: true })).toHaveCount(0);
});
