import { defineConfig } from '@playwright/test';

const isCI = !!process.env.CI;

// Screenshots and traces for every one of the 275 tests produced a 397 MB report
// directory, which CI uploaded on every run, pass or fail — 461 MiB as stored, against
// the 500 MB of artifact storage a private repo gets on the Free plan. One green run
// very nearly filled the account's entire quota, and it was retained for 14 days.
//
// Failure-only capture costs nothing in debuggability, because a passing test's trace
// is never opened. Measured on a green 275-test run: 764 KB. Set WIR_FULL_TRACE=1 to
// get the old behaviour back while chasing something locally.
const fullCapture = !!process.env.WIR_FULL_TRACE;

export default defineConfig({
  testDir: './tests',
  // Refuses to start when the pre-generated reports are missing or older than the
  // built plugin. Without it a forgotten regeneration produces a fully green run
  // against the previous build's output. See global-setup.ts.
  globalSetup: require.resolve('./global-setup'),
  timeout: 30000,
  expect: { timeout: 5000 },
  fullyParallel: true,        // Each test gets its own browser context (loadClean handles isolation)

  // A stray `test.only` narrows the run from 275 tests to 1 and still exits green —
  // the same false-pass shape the fixture stamps exist to prevent, so CI refuses it.
  forbidOnly: isCI,

  // Locally, a flake should be seen. In CI a single flaky test costs a 22-minute rerun
  // of the whole regression workflow, nearly all of it regenerating fixtures that had
  // nothing to do with the flake. Playwright reports a test that only passed on retry
  // as "flaky", distinct from "passed", so retries surface flakiness rather than
  // burying it.
  retries: isCI ? 2 : 0,

  reporter: [
    ['list'],
    ['html', { open: 'never', outputFolder: '../../test-results/playwright-report' }],
    ['json', { outputFile: '../../test-results/playwright-results.json' }],
  ],
  use: {
    headless: true,
    screenshot: fullCapture ? 'on' : 'only-on-failure',
    trace: fullCapture ? 'on' : 'retain-on-failure',
    video: 'off',
  },
  projects: [
    { name: 'chromium', use: { browserName: 'chromium' } },
  ],
});
