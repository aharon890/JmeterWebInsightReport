import type { Page } from '@playwright/test';
import * as path from 'path';
import * as fs from 'fs';

const PROJECT_ROOT = path.resolve(__dirname, '..', '..', '..');

/**
 * Find report HTML for a given test ID.
 * Searches test-results/EXX/ first, then test-plans/runs/.
 */
export function getReportPath(testId: string): string {
  const testResultPath = path.join(PROJECT_ROOT, 'test-results', testId, `${testId}-report.html`);
  if (fs.existsSync(testResultPath)) return testResultPath;
  const runsDir = path.join(PROJECT_ROOT, 'test-plans', 'runs');
  if (fs.existsSync(runsDir)) {
    const files = fs.readdirSync(runsDir).filter(f => f.endsWith('.html')).sort().reverse();
    if (files.length > 0) return path.join(runsDir, files[0]);
  }
  throw new Error(`No report found for ${testId}. Run JMeter tests first.`);
}

export function getReportUrl(testId: string): string {
  return `file://${getReportPath(testId)}`;
}

/**
 * Load report with CLEAN localStorage — for default-state tests.
 * Clears all wir_* keys so test sees factory defaults.
 */
export async function loadClean(page: Page, testId: string): Promise<void> {
  const url = getReportUrl(testId);
  await page.goto(url, { waitUntil: 'networkidle' });
  // Clear all Web Insight Report localStorage keys
  await page.evaluate(() => {
    const keys = Object.keys(localStorage).filter(k => k.startsWith('wir_'));
    keys.forEach(k => localStorage.removeItem(k));
  });
  // Reload so the report reads clean localStorage
  await page.goto(url, { waitUntil: 'networkidle' });
}

/**
 * Load report WITHOUT clearing localStorage — for persistence/mutation tests.
 */
export async function loadDirty(page: Page, testId: string): Promise<void> {
  await page.goto(getReportUrl(testId), { waitUntil: 'networkidle' });
}

/**
 * Switch to a tab by clicking its button.
 */
export async function switchTab(page: Page, tabName: string): Promise<void> {
  await page.click(`.tab-btn[data-tab="${tabName}"]`);
  await page.waitForTimeout(400);
}

/**
 * Collect JS errors during a callback.
 */
export async function expectNoJsErrors(page: Page, action: () => Promise<void>): Promise<void> {
  const errors: string[] = [];
  const handler = (err: Error) => errors.push(err.message);
  page.on('pageerror', handler);
  await action();
  page.removeListener('pageerror', handler);
  if (errors.length > 0) {
    throw new Error(`JS errors detected: ${errors.join('; ')}`);
  }
}
