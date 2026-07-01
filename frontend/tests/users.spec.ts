import { test, expect } from '@playwright/test';

// Managed-user admin UI (feature 016, Phase 3, US8): an admin (alice, PUBLISH) signs in, creates a
// publisher account, sees it listed, and deletes it. Runs against the auth-enabled backend.
test('admin creates and deletes a managed user', async ({ page }) => {
  const username = `bob-${Date.now()}`;

  await page.goto('/');

  // Sign in as an administrator (a PUBLISH user).
  await page.getByTestId('login-button').click();
  await page.getByTestId('login-username').fill('alice');
  await page.getByTestId('login-password').fill('pw');
  await page.getByTestId('login-submit').click();
  await expect(page.getByTestId('current-user')).toHaveText('alice');

  // Users is the default tab of Users & Tokens.
  await page.getByRole('link', { name: 'Users & Tokens', exact: true }).click();
  await expect(page.getByTestId('users-panel')).toBeVisible();

  // Create a publisher.
  await page.getByTestId('user-create-open').click();
  await page.getByTestId('user-username').fill(username);
  await page.getByTestId('user-password').fill('bobpw');
  await page.getByTestId('user-role').selectOption('publisher');
  await page.getByTestId('user-create-submit').click();

  // Appears in the list with the Publisher role.
  const row = page.getByTestId('user-row').filter({ hasText: username });
  await expect(row).toHaveCount(1);
  await expect(row).toContainText('Publisher');

  // Delete it; it's gone.
  page.on('dialog', (d) => d.accept());
  await row.getByTestId('user-delete').click();
  await expect(page.getByTestId('user-row').filter({ hasText: username })).toHaveCount(0);
});
