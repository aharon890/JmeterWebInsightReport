import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import { loadClean, loadDirty, getReportUrl, getReportPath } from './helpers';

test.describe('Header — Title & Subtitle', () => {
  test('h1 shows non-empty report title', async ({ page }) => {
    await loadClean(page, 'E02');
    const h1 = page.locator('.report-header h1');
    await expect(h1).toBeVisible();
    const text = await h1.textContent();
    expect(text!.trim().length).toBeGreaterThan(0);
  });

  test('page title matches h1', async ({ page }) => {
    await loadClean(page, 'E02');
    const h1Text = await page.locator('.report-header h1').textContent();
    const pageTitle = await page.title();
    expect(pageTitle).toBe(h1Text!.trim());
  });

  test('subtitle shows Generated timestamp', async ({ page }) => {
    await loadClean(page, 'E02');
    const subtitle = page.locator('.report-subtitle');
    const text = await subtitle.textContent();
    expect(text).toContain('Generated:');
  });

  test('subtitle shows Duration', async ({ page }) => {
    await loadClean(page, 'E02');
    const subtitle = page.locator('.report-subtitle');
    const text = await subtitle.textContent();
    expect(text).toContain('Duration:');
  });
});

/* SF38 (plans/server-tab-followups.md, Batch 6): "Generated" was Java's text in the GENERATING
   machine's zone — "2026-10-03 16:10:16 IDT" in New York too, beside a Server tab in the reader's.
   The template now carries the instant as an ISO attribute and the page writes it in the reader's
   zone, with the offset. The CI/CD JSON's generatedAt is that Java text still, untouched (R06). */
for (const [zone, minutesEast, label] of [['UTC', 0, 'UTC+00:00'], ['Asia/Kolkata', 330, 'UTC+05:30']] as const) {
  test.describe(`Header — Generated in ${zone}`, () => {
    test.use({ timezoneId: zone });
    test(`[R] SF38: the header's Generated is written in the reader's zone, ${label}`, async ({ page }) => {
      await loadClean(page, 'E26');
      const span = page.locator('#reportGeneratedAt');
      const iso = await span.getAttribute('data-generated-at');
      expect(iso, 'the template carries the instant').toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$/);
      // The instant in this zone, worked out here rather than by the page's own formatter.
      const at = new Date(Date.parse(iso!) + minutesEast * 60000);
      const p = (n: number) => String(n).padStart(2, '0');
      const local = `${at.getUTCFullYear()}-${p(at.getUTCMonth() + 1)}-${p(at.getUTCDate())} `
        + `${p(at.getUTCHours())}:${p(at.getUTCMinutes())}:${p(at.getUTCSeconds())}`;
      await expect(span).toHaveText(`Generated: ${local} ${label}`);
      // What the generating machine wrote is still there to read, as the JSON's generatedAt has it.
      const json = JSON.parse(fs.readFileSync(getReportPath('E26').replace(/\.html$/, '.json'), 'utf8'));
      await expect(span).toHaveAttribute('title', `As the generating machine wrote it: ${json.generatedAt}`);
    });
  });
}

test.describe('Header — Status Badge', () => {
  /* The verdict badge stands alone. No prose beside it.

     Found by opening a report, not by a test: a Thymeleaf parser-level comment in the header
     spelled out its own closing delimiter to explain itself, which closed the comment on the
     spot. Every line after it became document content and rendered as loose paragraphs next to
     the verdict — and, being a flex item in the same row, shoved the dark-mode and colorblind
     buttons out of position. All 543 specs passed: every one of them asked whether the right
     thing was PRESENT, and none asked whether anything else was.

     So this asserts on the badge CONTAINER, not the badge. The status is the whole point of
     that corner — descriptions belong on the Summary tab and the Notes tab — so anything in
     there beyond the badge itself is a defect whatever it says. */
  test('the header badge carries the verdict and nothing else', async ({ page }) => {
    await loadClean(page, 'E25');
    const container = page.locator('.header-badge');
    await expect(container).toBeVisible();

    const badgeText = (await container.locator('.status-badge').textContent())!.trim();
    const containerText = (await container.textContent())!.trim();
    expect(containerText).toBe(badgeText);

    // The buttons stay to the RIGHT of the title. Leaked text between them and the badge is
    // what moved them, so their position is the second, independent symptom worth pinning.
    const title = (await page.locator('.report-header h1').boundingBox())!;
    for (const id of ['#darkModeToggle', '#cbModeToggle']) {
      const box = (await page.locator(id).boundingBox())!;
      expect(box.x).toBeGreaterThan(title.x + title.width);
    }
  });

  /* A run that declared no asserts. The badge must state that, and must not claim a verdict:
     it used to say "ALL PASSED" in green whenever the run recorded no errors, which is also
     what a typo'd annotations path, a malformed file and a tag selection that matched nothing
     all produce. "ALL PASSED" is asserted absent rather than merely unexpected, because the
     regression that matters is the green one coming back. */
  test('no-asserts report: says NO ASSERTS DECLARED, never claims a pass', async ({ page }) => {
    await loadClean(page, 'E02');
    const badge = page.locator('.status-badge');
    await expect(badge).toBeVisible();
    const text = await badge.textContent();
    // One answer, whatever else the run measured. ERRORS DETECTED used to be the alternative
    // here, in the warn colour, whenever a gateless run recorded errors — an error count
    // standing in for a verdict. Errors have a header card, a tab and two tables of their own;
    // none of them has to pretend to be a judgement.
    expect(text).toMatch(/NO ASSERTS DECLARED/);
    expect(text).not.toMatch(/ERRORS DETECTED/);
    expect(text).not.toMatch(/ALL PASSED/);
    await expect(badge).not.toHaveClass(/badge-pass/);
  });

  /* The product uses one word, "ASSERTS", for one mechanism. The class names are deliberately
     untouched — badge-pass/badge-fail are internal and renaming them would be churn every
     browser spec pays for. */
  test('Passing report: badge says ASSERTS PASS with badge-pass class', async ({ page }) => {
    await loadClean(page, 'E10');
    const badge = page.locator('.status-badge');
    await expect(badge).toContainText('ASSERTS PASS');
    await expect(badge).toHaveClass(/badge-pass/);
  });

  test('Failing report: badge says ASSERTS FAIL with badge-fail class', async ({ page }) => {
    await loadClean(page, 'E11');
    const badge = page.locator('.status-badge');
    await expect(badge).toContainText('ASSERTS FAIL');
    await expect(badge).toHaveClass(/badge-fail/);
  });
});

test.describe('Header — Dark Mode CRUD', () => {
  test('[R] default OFF: body has no dark-mode class, icon is crescent', async ({ page }) => {
    await loadClean(page, 'E02');
    await expect(page.locator('body')).not.toHaveClass(/dark-mode/);
    const icon = await page.locator('#darkModeIcon').textContent();
    expect(icon).toContain('\u263E');
  });

  test('[C/D] toggle ON then OFF: body class and bg color change and restore', async ({ page }) => {
    await loadClean(page, 'E02');
    const bgBefore = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
    // Toggle ON
    await page.click('#darkModeToggle');
    await expect(page.locator('body')).toHaveClass(/dark-mode/);
    const bgDark = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
    expect(bgDark).not.toEqual(bgBefore);
    // Toggle OFF
    await page.click('#darkModeToggle');
    await expect(page.locator('body')).not.toHaveClass(/dark-mode/);
    const bgAfter = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
    expect(bgAfter).toEqual(bgBefore);
  });

  test('[U] dark mode persists in localStorage after reload', async ({ page }) => {
    await loadClean(page, 'E02');
    await page.click('#darkModeToggle');
    await expect(page.locator('body')).toHaveClass(/dark-mode/);
    // Reload (dirty - don't clear localStorage)
    await loadDirty(page, 'E02');
    await expect(page.locator('body')).toHaveClass(/dark-mode/);
    // Clean up: toggle off
    await page.click('#darkModeToggle');
  });
});

test.describe('Header — Colorblind Mode CRUD', () => {
  test('[R] default OFF: no cb-mode class, icon dim', async ({ page }) => {
    await loadClean(page, 'E02');
    await expect(page.locator('body')).not.toHaveClass(/cb-mode/);
    const opacity = await page.locator('#cbModeIcon').evaluate(el => getComputedStyle(el).opacity);
    expect(parseFloat(opacity)).toBeLessThan(0.5);
  });

  test('[C/D] toggle ON then OFF', async ({ page }) => {
    await loadClean(page, 'E02');
    await page.click('#cbModeToggle');
    await expect(page.locator('body')).toHaveClass(/cb-mode/);
    await page.click('#cbModeToggle');
    await expect(page.locator('body')).not.toHaveClass(/cb-mode/);
  });

  test('[C] dark + colorblind combined: both classes on body', async ({ page }) => {
    await loadClean(page, 'E02');
    await page.click('#darkModeToggle');
    await page.click('#cbModeToggle');
    await expect(page.locator('body')).toHaveClass(/dark-mode/);
    await expect(page.locator('body')).toHaveClass(/cb-mode/);
    // Cleanup
    await page.click('#darkModeToggle');
    await page.click('#cbModeToggle');
  });
});
