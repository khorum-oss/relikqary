import { test, expect } from '@playwright/test';

// Gradle module surfacing in the browse UI (feature 011): a coordinate with a .module is badged, shows
// Gradle (Kotlin + Groovy) and Maven consume snippets, and a parsed module detail view. Seeded by
// scripts/e2e.sh at releases/com/example/gmodule/2.0.0 (jar + .module with one variant).
test('a Gradle module coordinate is badged, offers snippets, and shows variant detail', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('link', { name: 'releases' }).click();
  for (const folder of ['com/', 'example/', 'gmodule/', '2.0.0/']) {
    await page.getByRole('link', { name: folder, exact: true }).click();
  }

  // Coordinate panel with the Gradle module badge.
  await expect(page.getByTestId('coordinate-panel')).toContainText('com.example:gmodule:2.0.0');
  await expect(page.getByTestId('gradle-module-badge')).toBeVisible();

  // Consume snippets: all three forms with the coordinate + repository URL.
  await expect(page.getByTestId('consume-snippets')).toBeVisible();
  await expect(page.getByTestId('snippet-body')).toContainText('implementation("com.example:gmodule:2.0.0")');
  await page.getByTestId('snippet-groovy').click();
  await expect(page.getByTestId('snippet-body')).toContainText("implementation 'com.example:gmodule:2.0.0'");
  await page.getByTestId('snippet-maven').click();
  await expect(page.getByTestId('snippet-body')).toContainText('<artifactId>gmodule</artifactId>');

  // Module detail: the variant with its attributes / capabilities / dependencies / files.
  await page.getByTestId('view-module').click();
  const detail = page.getByTestId('module-detail');
  await expect(detail).toContainText('apiElements');
  await expect(detail).toContainText('org.gradle.usage');
  await expect(detail).toContainText('com.example:gmodule:2.0.0');
  await expect(detail).toContainText('guava');
  await expect(detail).toContainText('gmodule-2.0.0.jar');
});
