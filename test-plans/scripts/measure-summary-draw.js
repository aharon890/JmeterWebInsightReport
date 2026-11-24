// SC-006: how long a report's Summary tab takes to draw every chart, opened over file:// in Playwright's Chromium.
// Called by measure-run-data.sh: node measure-summary-draw.js <playwright dir> <report.html> [runs]
// Prints the median of the runs, in seconds: from navigation to every Summary chart holding a drawn canvas.
'use strict';
const path = require('path');

(async () => {
  const [pwDir, report, runsArg] = process.argv.slice(2);
  const { chromium } = require(path.join(path.resolve(pwDir), 'node_modules', 'playwright'));
  const runs = Number(runsArg || 3);
  const url = 'file:///' + path.resolve(report).replace(/\\/g, '/');
  const browser = await chromium.launch();
  const times = [];
  for (let i = 0; i < runs; i++) {
    const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } });
    const t0 = Date.now();
    await page.goto(url, { waitUntil: 'load', timeout: 120000 });
    await page.waitForFunction(() => {
      const charts = Array.from(document.querySelectorAll('#tab-summary .chart-container'))
        .filter((c) => c.offsetParent !== null);
      return charts.length > 0 && charts.every((c) => c.querySelector('canvas'));
    }, null, { timeout: 120000, polling: 50 });
    times.push((Date.now() - t0) / 1000);
    await page.close();
  }
  await browser.close();
  times.sort((a, b) => a - b);
  console.log(JSON.stringify({ report: path.basename(report), runs: times, median: times[Math.floor(times.length / 2)] }));
})().catch((e) => { console.error(e.message); process.exit(1); });
