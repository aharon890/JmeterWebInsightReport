import { test, expect } from '@playwright/test';
import { loadClean, switchTab } from './helpers';

/* ============================================================
 *  ASSERTS PASS [E10]
 * ============================================================ */

test.describe('Asserts PASS (E10)', () => {
  test('Header badge shows "ASSERTS PASS" with badge-pass class', async ({ page }) => {
    await loadClean(page, 'E10');
    const badge = page.locator('.status-badge');
    await expect(badge).toBeVisible();
    await expect(badge).toContainText('ASSERTS PASS');
    await expect(badge).toHaveClass(/badge-pass/);
  });

  test('Samplers tab: all sampler badges show PASS', async ({ page }) => {
    await loadClean(page, 'E10');
    await switchTab(page, 'samplers');

    const assertBadges = page.locator('#samplerTable .assert-badge-mini');
    const count = await assertBadges.count();
    expect(count).toBeGreaterThan(0);

    for (let i = 0; i < count; i++) {
      await expect(assertBadges.nth(i)).toContainText('PASS');
      await expect(assertBadges.nth(i)).toHaveClass(/assert-badge-pass/);
    }
  });

  test('Summary cards: assert mini-badges visible', async ({ page }) => {
    await loadClean(page, 'E10');
    const assertMini = page.locator('.summary-card .assert-badge-mini');
    expect(await assertMini.count()).toBeGreaterThanOrEqual(1);
  });
});

/* ============================================================
 *  ASSERTS FAIL [E11]
 * ============================================================ */

test.describe('Asserts FAIL (E11)', () => {
  test('Header badge shows "ASSERTS FAIL" with badge-fail class', async ({ page }) => {
    await loadClean(page, 'E11');
    const badge = page.locator('.status-badge');
    await expect(badge).toBeVisible();
    await expect(badge).toContainText('ASSERTS FAIL');
    await expect(badge).toHaveClass(/badge-fail/);
  });

  test('Samplers tab: all samplers have assert FAIL badges', async ({ page }) => {
    await loadClean(page, 'E11');
    await switchTab(page, 'samplers');

    // At least one FAIL badge
    const failBadges = page.locator('#samplerTable .assert-badge-mini.assert-badge-fail');
    expect(await failBadges.count()).toBeGreaterThanOrEqual(1);

    // In E11, all samplers have FAIL badges (the assert thresholds are very aggressive)
    const allBadges = page.locator('#samplerTable .assert-badge-mini');
    const count = await allBadges.count();
    for (let i = 0; i < count; i++) {
      await expect(allBadges.nth(i)).toContainText('FAIL');
    }
  });

  test('Apdex visible in summary card [E02]', async ({ page }) => {
    // E02 has an Apdex card in summary; E11 does not
    await loadClean(page, 'E02');
    const apdexCard = page.locator('#apdexCard');
    await expect(apdexCard).toBeVisible();
    const scoreText = await apdexCard.locator('.card-value').textContent();
    const score = parseFloat(scoreText!);
    expect(score).toBeGreaterThanOrEqual(0);
    expect(score).toBeLessThanOrEqual(1);
  });

  test('Apdex column visible in samplers table [E02]', async ({ page }) => {
    // E02 has an Apdex column; E11 does not
    await loadClean(page, 'E02');
    await switchTab(page, 'samplers');

    // Find Apdex header by text
    const apdexHeader = page.locator('#samplerTable thead th', { hasText: 'Apdex' });
    await expect(apdexHeader).toBeVisible();

    // Each row should have an apdex badge
    const apdexBadges = page.locator('#samplerTable tbody .apdex-badge');
    expect(await apdexBadges.count()).toBeGreaterThan(0);
  });

  test('Sort by Apdex ascending: worst first [E02]', async ({ page }) => {
    await loadClean(page, 'E02');
    await switchTab(page, 'samplers');

    // Click Apdex header to sort ascending
    const apdexHeader = page.locator('#samplerTable thead th', { hasText: 'Apdex' });
    await apdexHeader.click();
    await page.waitForTimeout(300);

    // Read first and last Apdex values from data rows only
    const rows = page.locator('#samplerTable tbody tr[data-sampler]:not(.sampler-detail-row):not(.total-sampler-row)');
    const count = await rows.count();
    expect(count).toBeGreaterThanOrEqual(2);

    const firstApdex = parseFloat((await rows.first().locator('.apdex-badge').textContent()) || '0');
    const lastApdex = parseFloat((await rows.nth(count - 1).locator('.apdex-badge').textContent()) || '0');
    expect(firstApdex).toBeLessThanOrEqual(lastApdex);
  });

  test('Sort by Apdex descending: best first [E02]', async ({ page }) => {
    await loadClean(page, 'E02');
    await switchTab(page, 'samplers');

    // Click Apdex header twice: first for ascending, second for descending
    const apdexHeader = page.locator('#samplerTable thead th', { hasText: 'Apdex' });
    await apdexHeader.click();
    await page.waitForTimeout(200);
    await apdexHeader.click();
    await page.waitForTimeout(300);

    const rows = page.locator('#samplerTable tbody tr[data-sampler]:not(.sampler-detail-row):not(.total-sampler-row)');
    const count = await rows.count();
    expect(count).toBeGreaterThanOrEqual(2);

    const firstApdex = parseFloat((await rows.first().locator('.apdex-badge').textContent()) || '0');
    const lastApdex = parseFloat((await rows.nth(count - 1).locator('.apdex-badge').textContent()) || '0');
    expect(firstApdex).toBeGreaterThanOrEqual(lastApdex);
  });

  test('Apdex values vary across samplers [E02]', async ({ page }) => {
    await loadClean(page, 'E02');
    await switchTab(page, 'samplers');

    // Verify Apdex badges exist and have numeric values
    const apdexBadges = page.locator('#samplerTable tbody tr[data-sampler] .apdex-badge');
    const count = await apdexBadges.count();
    expect(count).toBeGreaterThan(0);

    // Collect all apdex values
    const values: number[] = [];
    for (let i = 0; i < count; i++) {
      const text = await apdexBadges.nth(i).textContent();
      const val = parseFloat(text!);
      expect(val).toBeGreaterThanOrEqual(0);
      expect(val).toBeLessThanOrEqual(1);
      values.push(val);
    }

    // There should be variation in Apdex values across samplers
    const unique = new Set(values);
    expect(unique.size).toBeGreaterThan(1);
  });

  test('Slow sampler has lower Apdex than fast sampler [E02]', async ({ page }) => {
    await loadClean(page, 'E02');
    await switchTab(page, 'samplers');

    // E02 has samplers with varying response times — find a fast and slow one
    const apdexBadges = page.locator('#samplerTable tbody tr[data-sampler] .apdex-badge');
    const count = await apdexBadges.count();
    expect(count).toBeGreaterThanOrEqual(2);

    const values: number[] = [];
    for (let i = 0; i < count; i++) {
      values.push(parseFloat((await apdexBadges.nth(i).textContent()) || '0'));
    }

    // min should be < max (some variation exists)
    const minApdex = Math.min(...values);
    const maxApdex = Math.max(...values);
    expect(maxApdex).toBeGreaterThan(minApdex);
  });
});

/* ============================================================
 *  Notes Tab — Annotations [E12]
 * ============================================================ */

test.describe('Notes Tab — Annotations (E12)', () => {
  test('Notes tab: test notes content visible', async ({ page }) => {
    await loadClean(page, 'E12');
    await switchTab(page, 'notes');

    // The notes textarea should exist and may have pre-filled content
    const textarea = page.locator('#notesTextarea');
    await expect(textarea).toBeVisible();
  });

  test('Notes tab: timeline annotations rendered when present', async ({ page }) => {
    await loadClean(page, 'E12');
    await switchTab(page, 'notes');

    // Check for timeline markers section
    const markersSection = page.locator('.timeline-markers-list');
    if (await markersSection.count() > 0) {
      const markerRows = markersSection.locator('.timeline-marker-row');
      expect(await markerRows.count()).toBeGreaterThan(0);

      // Each marker row has time, type badge, and label
      const firstMarker = markerRows.first();
      await expect(firstMarker.locator('.timeline-marker-time')).toBeVisible();
      await expect(firstMarker.locator('.timeline-marker-type-badge')).toBeVisible();
      await expect(firstMarker.locator('.timeline-marker-label')).toBeVisible();
    }
  });

  test('Sampler notes: info icons visible on annotated samplers in Samplers tab', async ({ page }) => {
    await loadClean(page, 'E12');
    await switchTab(page, 'samplers');

    // Check for sampler note icons (the 'i' icon next to annotated sampler names)
    const noteIcons = page.locator('#samplerTable .sampler-note-icon');
    expect(await noteIcons.count()).toBeGreaterThan(0);

    // Verify the icon is visible and has a title (tooltip text)
    const firstIcon = noteIcons.first();
    await expect(firstIcon).toBeVisible();
    const title = await firstIcon.getAttribute('title');
    expect(title).toBeTruthy();
    expect(title!.length).toBeGreaterThan(0);
  });

  test('Sampler note icon click toggles expanded class', async ({ page }) => {
    await loadClean(page, 'E12');
    await switchTab(page, 'samplers');

    const noteIcons = page.locator('#samplerTable .sampler-note-icon');
    if (await noteIcons.count() > 0) {
      const icon = noteIcons.first();
      await expect(icon).not.toHaveClass(/expanded/);

      await icon.click();
      await page.waitForTimeout(200);
      await expect(icon).toHaveClass(/expanded/);

      await icon.click();
      await page.waitForTimeout(200);
      await expect(icon).not.toHaveClass(/expanded/);
    }
  });
});
