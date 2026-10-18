/**
 * Captures targeted screenshots for WIKI documentation, from the fixtures in test-results/.
 * Run from test-plans/playwright: node ../../WIKI/Tutorial/take-screenshots.js
 * Only the Server tab's shots (E26, E27): node ../../WIKI/Tutorial/take-screenshots.js --server-only
 * — the rest carry the generation date, so retaking them all churns every image for nothing.
 *
 * @playwright/test is resolved from test-plans/playwright, where it is installed, not from this
 * script's directory: a bare require() resolves from WIKI/Tutorial and fails with "Cannot find
 * module" whatever the cwd. So no NODE_PATH is needed, and the script runs from any cwd.
 */
const path = require('path');

const PROJECT = path.resolve(__dirname, '..', '..');
const { chromium } = require(require.resolve('@playwright/test', {
  paths: [path.resolve(PROJECT, 'test-plans', 'playwright')],
}));
const IMG = path.resolve(PROJECT, 'WIKI', 'Tutorial', 'images');
const RESULTS = path.resolve(PROJECT, 'test-results');

function reportUrl(id) {
  return `file://${RESULTS}/${id}/${id}-report.html`;
}

async function main() {
  const browser = await chromium.launch();

  async function ctx() {
    return browser.newContext({ viewport: { width: 1400, height: 900 } });
  }

  // Waits until no ECharts instance on the page is animating, and stays so across two checks — a
  // chart drawn by a timer after the last action has started by then. Without it a shot taken
  // straight after a load, a tab switch or the theme toggle caught the summary chart's Active
  // Threads line stopped partway across, and five E02 shots differed from one run to the next.
  // zrender's animation.isFinished() is true once no clip is left to play.
  async function settle(page) {
    const calm = () => page.evaluate(() => {
      if (!window.echarts) return true;
      return Array.from(document.querySelectorAll('[_echarts_instance_]')).every(el => {
        const chart = window.echarts.getInstanceByDom(el);
        return !chart || chart.isDisposed() || chart.getZr().animation.isFinished();
      });
    });
    const deadline = Date.now() + 15000;
    for (let quiet = 0; quiet < 2;) {
      if (Date.now() > deadline) throw new Error('charts still animating after 15s');
      quiet = (await calm()) ? quiet + 1 : 0;
      await page.waitForTimeout(150);
    }
  }

  // What this run took and skipped. Counting the PNGs in images/ instead also counted the ones no
  // script produces, so the total read as shots taken when it was not.
  let taken = 0;
  const skipped = [];

  async function shot(page, name) {
    await settle(page);
    await page.screenshot({ path: `${IMG}/${name}.png` });
    taken++;
    console.log(`  ${name}.png`);
  }

  async function shotEl(page, selector, name) {
    const el = page.locator(selector).first();
    if (await el.count() > 0 && await el.isVisible()) {
      await settle(page);
      await el.screenshot({ path: `${IMG}/${name}.png` });
      taken++;
      console.log(`  ${name}.png`);
    } else {
      skipped.push(name);
      console.log(`  SKIP ${name} (not found: ${selector})`);
    }
  }

  async function tab(page, name) {
    await page.click(`.tab-btn[data-tab="${name}"]`);
    await page.waitForTimeout(600);
  }

  // Runs fn in a viewport `height` tall, then puts the 900 back. For a shot the 900 cannot hold:
  // an element taller than the viewport is captured beyond it, and the asserts table, taken that
  // way, was laid out narrower than it measured — its rows wrapped taller, and the last one fell
  // off the bottom of the image.
  async function atHeight(page, height, fn) {
    await page.setViewportSize({ width: 1400, height });
    await page.waitForTimeout(300);
    await fn();
    await page.setViewportSize({ width: 1400, height: 900 });
    await page.waitForTimeout(300);
  }

  // Clean localStorage helper
  async function loadClean(page, id) {
    const url = reportUrl(id);
    await page.goto(url, { waitUntil: 'networkidle' });
    await page.evaluate(() => {
      Object.keys(localStorage).filter(k => k.startsWith('wir_')).forEach(k => localStorage.removeItem(k));
    });
    await page.goto(url, { waitUntil: 'networkidle' });
  }

  const serverOnly = process.argv.includes('--server-only');
  let c;
  let page;
  if (!serverOnly) {
  // ==========================================
  // E02: Rich report — most screenshots
  // ==========================================
  console.log('=== E02: Main report ===');
  c = await ctx();
  page = await c.newPage();
  await loadClean(page, 'E02');

  // Overview
  await shot(page, '01-summary-overview');
  await shotEl(page, '.report-header', '02-header');
  // 03-summary-cards is taken on E12: it sits under the mini badges' paragraph, and E02 has no gate.

  // Apdex card
  await shotEl(page, '#apdexCard', '03b-apdex-card');

  // Summary chart + fullscreen button
  await shotEl(page, '#summary-response-time-chart', '04-summary-chart');

  // Top 5
  await shotEl(page, '.top-samplers-list', '05-top5-slowest');

  // Copy Summary button
  await shotEl(page, 'button:has-text("Copy Summary")', '05b-copy-summary-btn');

  // Filter bar
  await shotEl(page, '#filterBar', '06-filter-bar');

  // Filter menu expanded
  await page.click('#filterBarToggle');
  await page.waitForTimeout(300);
  await shotEl(page, '.filter-bar-toggle-wrapper', '06b-filter-menu-expanded');
  await page.click('#filterBarToggle'); // close

  // Palette buttons
  await shotEl(page, '#paletteToggle', '06c-palette-toggle');

  // Metric toggle
  await shotEl(page, '#percentileToggle', '06d-metric-toggle');

  // Granularity dropdown
  await shotEl(page, '#granularitySelect', '06e-granularity');

  // Sampler checkboxes with color dots
  await shotEl(page, '#samplerFilterList', '06f-sampler-checkboxes');

  // Time range + presets row
  await shotEl(page, '#filterRow2', '06g-time-presets');

  // Timeline tab
  await tab(page, 'timeline');
  await shot(page, '07-timeline-overview');

  // Individual charts
  await shotEl(page, '[data-chart-id="response-time-chart"]', '08-chart-response-time');
  await shotEl(page, '[data-chart-id="throughput-chart"]', '08b-chart-throughput');
  await shotEl(page, '[data-chart-id="error-rate-chart"]', '08c-chart-error-rate');
  await shotEl(page, '[data-chart-id="heatmap-chart"]', '08d-chart-heatmap');
  await shotEl(page, '[data-chart-id="response-codes-chart"]', '08e-chart-response-codes');
  await shotEl(page, '[data-chart-id="scatter-chart"]', '08f-chart-scatter');
  await shotEl(page, '[data-chart-id="percentile-bar-chart"]', '08g-chart-percentiles');

  // Chart controls (hide/fullscreen buttons)
  await shotEl(page, '[data-chart-id="response-time-chart"] .chart-header-actions', '08h-chart-controls');

  // Samplers tab
  await tab(page, 'samplers');
  await shot(page, '10-samplers-overview');

  // Samplers table header
  await shotEl(page, '#tab-samplers table thead', '10b-table-header');

  // Search input
  await shotEl(page, '#tab-samplers input[type="text"]', '10c-search-input');

  // Expand detail
  await page.locator('#tab-samplers table tbody tr').first().click();
  await page.waitForTimeout(600);
  await shot(page, '11-samplers-detail');

  // Detail chart slots
  await shotEl(page, '.detail-charts, .sampler-detail-charts', '11b-detail-charts');

  // Collapse
  await page.locator('#tab-samplers table tbody tr').first().click();
  await page.waitForTimeout(300);

  // Errors tab
  await tab(page, 'errors');
  await shot(page, '12-errors-overview');

  // The Error Breakdown card: by response code (the donut) and by sampler. Not
  // `#tab-errors .chart-container`, whose first match is the Error Timeline's scatter.
  await shotEl(page, '[data-chart-id="error-breakdown"]', '12b-error-pie');

  // Error summary table
  await shotEl(page, '#tab-errors table', '12c-error-summary-table');

  // Expand error detail row. Then centred: the shots above leave the page scrolled so that the
  // click's own scroll parks the row on the viewport's bottom edge, with the Request URL and
  // Response Body it opened below the fold. The mouse is moved off so no row shows its hover.
  const errorRow = page.locator('#tab-errors .error-row-main').first();
  if (await errorRow.count() > 0) {
    await errorRow.click();
    await page.waitForTimeout(400);
    await errorRow.evaluate(el => el.scrollIntoView({ block: 'center' }));
    await page.mouse.move(0, 0);
    await page.waitForTimeout(300);
    await shot(page, '12d-error-detail-expanded');
  } else {
    skipped.push('12d-error-detail-expanded');
    console.log('  SKIP 12d-error-detail-expanded (not found: #tab-errors .error-row-main)');
  }

  // Notes tab (no annotations in E02 — shows editor)
  await tab(page, 'notes');
  await shot(page, '13-notes-editor');

  // Dark mode
  await tab(page, 'summary');
  await page.click('#darkModeToggle');
  await page.waitForTimeout(400);
  await shot(page, '14-dark-mode');
  await page.click('#darkModeToggle'); // revert

  // Colorblind mode
  await page.click('#cbModeToggle');
  await page.waitForTimeout(300);
  await shot(page, '14b-colorblind-mode');
  await page.click('#cbModeToggle'); // revert

  await c.close();

  // ==========================================
  // E11: asserts FAIL
  // ==========================================
  console.log('=== E11: ASSERTS FAIL ===');
  c = await ctx();
  page = await c.newPage();
  await loadClean(page, 'E11');

  await shot(page, '15-asserts-fail');
  await shotEl(page, '.status-badge', '15b-asserts-fail-badge');

  // Assert badges in samplers table
  await tab(page, 'samplers');
  await shot(page, '16-asserts-fail-samplers');

  await c.close();

  // ==========================================
  // E10: asserts PASS
  // ==========================================
  console.log('=== E10: ASSERTS PASS ===');
  c = await ctx();
  page = await c.newPage();
  await loadClean(page, 'E10');

  await shotEl(page, '.status-badge', '15c-asserts-pass-badge');

  await c.close();

  // ==========================================
  // E12: Full annotations
  // ==========================================
  console.log('=== E12: Annotations ===');
  c = await ctx();
  page = await c.newPage();
  await loadClean(page, 'E12');

  // Both rows of summary cards, from a run that gates p95 and p99, so the mini badges are there
  await shotEl(page, '#tab-summary section.report-section:has(.summary-cards)', '03-summary-cards');

  // Assert threshold line on chart
  await tab(page, 'timeline');
  await page.waitForTimeout(500);
  await shotEl(page, '[data-chart-id="response-time-chart"]', '17-asserts-threshold-line');

  // Notes tab with rendered markdown: the notes open in their editor, and Preview renders them
  await tab(page, 'notes');
  await page.click('#tab-notes .notes-toolbar button:has-text("Preview")');
  await page.waitForTimeout(300);
  await shot(page, '17b-annotations-notes');

  // Engineer notes on summary
  await tab(page, 'summary');
  await shot(page, '17c-summary-with-notes');

  // Assert mini badges on cards: the second row, where P95 and P99 carry them
  await shotEl(page, '.summary-cards:has(.assert-badge-mini)', '17d-asserts-mini-badges');

  // Timeline markers
  await tab(page, 'timeline');
  await page.waitForTimeout(500);
  await shotEl(page, '[data-chart-id="response-time-chart"]', '17e-timeline-markers');

  // Rule lines on a chart (12-Asserts-Tab.md), last because it leaves the chart on p95 with a
  // sampler unticked. Taken here rather than on E25 with the gate's other shots: E12's p95 bands
  // sit among the data, so the binding fail line is drawn bold and the other dimmed, as the text
  // says. E25's p95 bands are machine-independent extremes (1 ms, 2 ms, 999,999 ms), and the axis
  // widens to keep every rule line on the plot (ruleAxisMax), so there every series lies flat on
  // zero, or, with Slow API alone, one rule's two lines and labels pile up on the axis floor.
  // The search sampler is unticked, so search:p95 stops drawing: its fail > 1,500 ms and
  // warn > 1,200 ms labels otherwise print over checkout's warn > 1,600 ms and over the bold
  // default:p95 fail > 1,000 ms, the one label the shot is for.
  await page.click('.filter-toggle-btn[data-metric="p95"]');
  await page.click('#samplerFilterList label:has-text("products/search")');
  // Long enough for the redraw's animation: at 800ms the series were caught half drawn.
  await page.waitForTimeout(1500);
  await shotEl(page, '[data-chart-id="response-time-chart"]', '55-chart-rule-lines');

  await c.close();

  // ==========================================
  // CMP: Compare tab
  // ==========================================
  console.log('=== CMP: Compare ===');
  c = await ctx();
  page = await c.newPage();
  await loadClean(page, 'CMP');

  // Compare tab visible
  await tab(page, 'compare');
  await shot(page, '18-compare-overview');

  // Comparison charts
  await shotEl(page, '#tab-compare .chart-container', '18b-compare-chart');

  // Comparison table with badges
  await shotEl(page, '#tab-compare table', '18c-compare-table');

  // Regression threshold controls
  await shotEl(page, '#tab-compare .threshold-controls, #tab-compare .regression-controls, #tab-compare [class*="threshold"]', '18d-threshold-controls');

  await c.close();

  // ==========================================
  // E08: Long names
  // ==========================================
  console.log('=== E08: Long names ===');
  c = await ctx();
  page = await c.newPage();
  await loadClean(page, 'E08');

  await tab(page, 'samplers');
  await shotEl(page, '#tab-samplers table', '19-long-names-table');

  await c.close();

  // ==========================================
  // E06: Zero errors
  // ==========================================
  console.log('=== E06: Zero errors ===');
  c = await ctx();
  page = await c.newPage();
  await loadClean(page, 'E06');

  await tab(page, 'errors');
  await shot(page, '20-zero-errors');

  await c.close();

  // ==========================================
  // E07: 100% errors
  // ==========================================
  console.log('=== E07: 100% errors ===');
  c = await ctx();
  page = await c.newPage();
  await loadClean(page, 'E07');

  await shotEl(page, '.summary-cards', '21-all-errors-cards');
  await tab(page, 'errors');
  await shot(page, '21b-all-errors-tab');

  await c.close();

  // ==========================================
  // E25: the quality gate — every rule state in one run (12-Asserts-Tab.md). Its thresholds are
  // extreme on purpose, so the verdicts are the same on any machine. 55, the chart's rule lines,
  // is taken on E12 instead; see there.
  // ==========================================
  console.log('=== E25: the quality gate ===');
  c = await ctx();
  page = await c.newPage();
  await loadClean(page, 'E25');

  await shotEl(page, '#gateStrip .wir-gate-strip', '50-gate-strip');

  await tab(page, 'asserts');
  // About 1,500px tall, so taken in a viewport holding the whole page (see atHeight).
  const pageHeight = await page.evaluate(() => document.documentElement.scrollHeight);
  await atHeight(page, pageHeight, () => shotEl(page, '#assertsTableWrap', '53-asserts-table'));

  // The editor, with its live preview, on the FAIL row. Opening it is not an edit, so no draft.
  await page.click('tr.wir-rule-row[data-rule-id="slow-api-p95"] .wir-edit-btn');
  await page.waitForTimeout(500);
  await shotEl(page, '#assertEditor .wir-slideover', '51-assert-editor');
  await page.click('#assertEditor .wir-slideover-head button[aria-label="Close"]');
  await page.waitForTimeout(300);

  // The wizard. At 900px its body scrolls, hiding the tuning and the preview the text describes,
  // so it is taken in a viewport tall enough to hold all of it. Closed by Escape, as every dialog
  // on the page is (B3).
  await atHeight(page, 1700, async () => {
    await page.click('#generateAssertsBtn');
    await page.waitForTimeout(500);
    await shotEl(page, '#assertWizard .wir-modal', '54-generate-wizard');
    await page.keyboard.press('Escape');
    await page.waitForTimeout(300);
  });

  // The rules judging a sampler, opened from its mini badge. The whole table, so the badge that
  // was clicked, the sampler it belongs to and the cell verdicts beside it are in the shot too.
  await tab(page, 'samplers');
  await page.click('#samplerTable tbody tr[data-sampler="Slow API"] .assert-badge-mini');
  await page.waitForSelector('.wir-judging-row[data-judging-for="Slow API"]');
  await page.mouse.move(0, 0); // off the badge, or the row shows its hover and its x
  await shotEl(page, '#samplerTable', '56-judging-rules');

  await c.close();

  }

  // ==========================================
  // E26: Server tab — built from committed inputs, so every state renders
  // ==========================================
  console.log('=== E26: Server ===');
  c = await ctx();
  page = await c.newPage();
  await loadClean(page, 'E26');

  await shotEl(page, '.wir-server-chip', '60-server-chip');
  await shotEl(page, '#serverStrip .wir-server-strip', '59-summary-server-card');
  await tab(page, 'server');
  await shot(page, '61-server-overview');
  await shotEl(page, '.wir-component-card .wir-component-head', '62-server-component-head');
  await shotEl(page, '#serverKpiTable', '63-server-board');

  // An open row: the series over time, and per instance
  await page.click('tr.wir-kpi-row:has(.wir-kpi-name:text-is("lag-spike")) .wir-row-toggle');
  await page.waitForTimeout(800);
  await shotEl(page, '.wir-chart-row', '64-server-chart');

  await shotEl(page, '.wir-collection', '65-server-collection');

  // + Add server assert opens the editor
  await page.click('.wir-server-actions .wir-btn-primary');
  await page.waitForTimeout(500);
  await shotEl(page, '#serverEditor .wir-slideover', '66-server-editor');

  await c.close();

  // ==========================================
  // E27: the catalogue — + Add component, the picker, thresholds, Generate's review
  // ==========================================
  console.log('=== E27: Server catalogue ===');
  c = await ctx();
  page = await c.newPage();
  await loadClean(page, 'E27');
  await tab(page, 'server');
  await page.click('#serverAddComponent');
  await page.waitForTimeout(300);
  await shotEl(page, '#serverDialog .wir-slideover', '67-server-add-component');
  await page.evaluate(() => window.WIR.serverKpis.closeDialog());
  await page.click('.wir-component-card[data-component="orders-db"] .wir-component-add');
  await page.waitForTimeout(300);
  for (const m of ['deadlocks', 'cache.hit.pct', 'replication.lag.seconds']) {
    await page.check(`[data-wir-dlg="pick:${m}"]`);
  }
  await page.waitForTimeout(300);
  await shot(page, '68-server-picker');
  await page.click('#serverPickNext');
  await page.waitForTimeout(300);
  await shot(page, '69-server-thresholds');
  await page.evaluate(() => window.WIR.serverKpis.closeDialog());
  await page.click('.wir-server-actions .wir-btn:has-text("Generate from this run")');
  await page.waitForTimeout(300);
  await shot(page, '70-server-generate-review');
  await c.close();

  await browser.close();
  console.log(`\nDone: ${taken} screenshots taken, ${skipped.length} skipped`
    + (skipped.length ? ` (${skipped.join(', ')})` : '') + `, in ${IMG}`);
}

main().catch(console.error);
