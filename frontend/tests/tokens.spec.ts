import { test, expect } from '@playwright/test';

// API token management UI (feature 016, Phase 3): an admin (alice, PUBLISH) signs in, mints a scoped
// token, sees the secret exactly once, finds it listed, and revokes it. Runs against the auth-enabled
// backend from scripts/e2e.sh.
test('admin signs in and manages an API token', async ({ page }) => {
  const name = `e2e-${Date.now()}`;

  await page.goto('/');

  // Sign in as an administrator (a PUBLISH user).
  await page.getByTestId('login-button').click();
  await expect(page.getByTestId('login-screen')).toBeVisible();
  await page.getByTestId('login-username').fill('alice');
  await page.getByTestId('login-password').fill('pw');
  await page.getByTestId('login-submit').click();
  await expect(page.getByTestId('current-user')).toHaveText('alice');

  // Open Users & Tokens, then the API Tokens tab.
  await page.getByRole('link', { name: 'Users & Tokens', exact: true }).click();
  await page.getByTestId('tab-tokens').click();
  await expect(page.getByTestId('tokens-panel')).toBeVisible();

  // Mint a publish-scoped token.
  await page.getByTestId('token-create-open').click();
  await page.getByTestId('token-name').fill(name);
  await page.getByTestId('token-scope').selectOption('publish');
  await page.getByTestId('token-create-submit').click();

  // The secret is shown exactly once and looks like a token.
  await expect(page.getByTestId('token-secret')).toContainText('rlq_');
  await page.getByTestId('token-created-dismiss').click();

  // It appears in the list (unique name ⇒ exactly one row).
  const row = page.getByTestId('token-row').filter({ hasText: name });
  await expect(row).toHaveCount(1);
  await expect(row).toContainText('publish');

  // Revoke it; the row reflects the revoked state.
  page.on('dialog', (d) => d.accept());
  await row.getByTestId('token-revoke').click();
  await expect(page.getByTestId('token-row').filter({ hasText: name })).toContainText('revoked');
});
