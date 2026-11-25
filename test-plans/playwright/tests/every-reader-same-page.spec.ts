import { test, expect, type Browser } from '@playwright/test';
import * as fs from 'fs';
import { getReportUrl } from './helpers';
import { REQUIRED_FIXTURES } from '../global-setup';

/**
 * Every reader sees the same report, as every reader of JMeter's HTML report does (the owner, 2026-11-05:
 * "like in jmeter ... it does not depend on localization").
 *
 * JMeter's HTML report writes its numbers with '.' and no thousands separator whatever the browser's
 * language, and its times in the time zone of the machine that built the report, whatever the reader's
 * (measured on JMeter 5.6.3, tmp/number-format-and-what-changed/jmeter/). So this opens each report as two
 * readers who differ in both, an English browser in Jerusalem and a German one in New York, reads what
 * each is shown (every tab's text, the filter bar, every drawn chart's axis labels and one tooltip each, a
 * sampler's detail charts, the Errors tab's grouped view, the Server tab with its rows open, Copy Summary,
 * Copy Verdict and the CSV downloads) and fails on any difference. A number or a time that follows the
 * reader's language or zone cannot hide from it, wherever on the page it is.
 */

const READERS = [
  { who: 'an English reader in Jerusalem', locale: 'en-US', timezoneId: 'Asia/Jerusalem' },
  { who: 'a German reader in New York', locale: 'de-DE', timezoneId: 'America/New_York' },
] as const;
// 'tool': the tab of a run another tool recorded (spec 002, FR-034), on K1 and K2
const TABS = ['summary', 'timeline', 'samplers', 'errors', 'compare', 'asserts', 'server', 'tool', 'notes'];
const CSV: Record<string, string[]> = {
  samplers: ['exportSamplerStatsCsv'],
  errors: ['table:errorSummaryTable', 'table:errorDetailsTable'],
  compare: ['table:comparisonTable'],
  // a table of the toolkit (spec 002) downloads what it shows: table:<its id>
  asserts: ['table:assertsTable'],
  tool: ['table:toolThresholds', 'table:toolTimings', 'table:toolChecks', 'table:toolGroupDurations'],
};

type Seen = Record<string, string[]>;

/** Every drawn chart under `selector`: its axes' labels as rendered, and one tooltip. */
async function chartsUnder(page: import('@playwright/test').Page, selector: string): Promise<string[]> {
  const charts: string[] = await page.evaluate((sel) => {
    const out: string[] = [];
    (window as any).__wirCharts = [];
    document.querySelectorAll(sel).forEach((root) => root.querySelectorAll('[_echarts_instance_]').forEach((el) => {
      const inst = (window as any).echarts.getInstanceByDom(el);
      if (!inst || !(el as HTMLElement).offsetParent || (el as HTMLElement).clientWidth === 0) return;
      (window as any).__wirCharts.push(el);
      const model = inst.getModel();
      ['xAxis', 'yAxis'].forEach((kind) => model.eachComponent(kind, (m: any, i: number) => {
        if (!m.axis) return;
        let labels: string[] = [];
        try { labels = m.axis.getViewLabels().map((l: any) => l.formattedLabel); } catch (e) { labels = ['(no labels)']; }
        // A chart's id without the creation time a sampler's detail charts carry
        // ("detail-slot-0-<name>-<Date.now()>"), which no reader sees
        out.push(`${(el.id || 'chart').replace(/-\d{12,}$/, '')} ${kind}[${i}]: ${labels.join(' | ')}`);
      }));
    }));
    return out;
  }, selector);
  const count = await page.evaluate(() => (window as any).__wirCharts.length);
  for (let i = 0; i < count; i++) {
    const shown = await page.evaluate((i) => {
      const el = (window as any).__wirCharts[i];
      const inst = (window as any).echarts.getInstanceByDom(el);
      const series = inst.getOption().series || [];
      const s = series.findIndex((x: any) => Array.isArray(x.data) && x.data.length > 0);
      if (s < 0) return false;
      const len = series[s].data.length;
      for (let k = 0; k < len; k++) {
        const j = (Math.floor(len / 2) + k) % len;
        const d = series[s].data[j];
        const v = Array.isArray(d) ? d[1] : (d && typeof d === 'object' ? d.value : d);
        if (v != null && !(Array.isArray(v) && v[1] == null)) {
          inst.dispatchAction({ type: 'showTip', seriesIndex: s, dataIndex: j });
          return true;
        }
      }
      return false;
    }, i);
    if (!shown) continue;
    await page.waitForTimeout(150);
    charts.push(await page.evaluate((i) => {
      const el = (window as any).__wirCharts[i];
      const tips = Array.from(el.querySelectorAll('div')).filter((d: any) =>
        !d.querySelector('canvas, svg') && d.innerText && d.innerText.trim() && /z-index:\s*9999999/.test(d.getAttribute('style') || ''));
      (window as any).echarts.getInstanceByDom(el).dispatchAction({ type: 'hideTip' });
      return `${(el.id || 'chart').replace(/-\d{12,}$/, '')} tooltip: ${tips.length ? (tips[tips.length - 1] as HTMLElement).innerText.replace(/\s+/g, ' ') : '(none)'}`;
    }, i));
  }
  return charts;
}

async function whatTheReaderSees(browser: Browser, id: string, reader: typeof READERS[number]): Promise<Seen> {
  const context = await browser.newContext({
    locale: reader.locale, timezoneId: reader.timezoneId, acceptDownloads: true, viewport: { width: 1600, height: 1000 },
  });
  await context.addInitScript(() => {
    (window as any).__clip = [];
    const clip = {
      writeText: (t: string) => { (window as any).__clip.push(String(t)); return Promise.resolve(); },
      write: async (items: any[]) => {
        for (const it of items) for (const type of it.types) (window as any).__clip.push(type + ': ' + await (await it.getType(type)).text());
      },
    };
    try { Object.defineProperty(navigator, 'clipboard', { value: clip, configurable: true }); } catch (e) { /* the page's own fallback */ }
  });
  const page = await context.newPage();
  const seen: Seen = {};
  const lines = (s: string | null | undefined) => (s || '').split('\n').map((l) => l.trim()).filter(Boolean);
  try {
    const url = getReportUrl(id);
    await page.goto(url, { waitUntil: 'networkidle' });
    await page.evaluate(() => Object.keys(localStorage).filter((k) => k.startsWith('wir_')).forEach((k) => localStorage.removeItem(k)));
    await page.goto(url, { waitUntil: 'networkidle' });
    await page.waitForFunction(() => (window as any).globalChartData, null, { timeout: 60000 });
    // Σ Total on, so every surface that draws the whole run shows it
    await page.locator('#samplerFilterList label[data-sampler-name="σ total"]').click();
    await page.waitForTimeout(500);
    seen['header'] = lines(await page.evaluate(() => (document.querySelector('.report-header') as HTMLElement | null)?.innerText));
    seen['header: Generated hover text'] = [await page.evaluate(() => document.getElementById('reportGeneratedAt')?.getAttribute('title') || '')];
    for (const tab of TABS) {
      const button = page.locator(`.tab-btn[data-tab="${tab}"]`);
      if (!(await button.isVisible())) { seen[`${tab}: shown`] = ['no']; continue; }
      await button.click();
      await page.waitForTimeout(1200);
      if (tab === 'server' && await page.evaluate(() => !!((window as any).WIR && (window as any).WIR.serverKpis && (window as any).WIR.serverKpis.expandAll))) {
        await page.evaluate(() => (window as any).WIR.serverKpis.expandAll());
        await page.waitForTimeout(2500);
      }
      seen[`${tab}: text`] = lines(await page.evaluate((t) => (document.getElementById('tab-' + t) as HTMLElement).innerText, tab));
      seen[`${tab}: boxes and hover texts`] = await page.evaluate((t) => {
        const panel = document.getElementById('tab-' + t)!;
        const boxes = Array.from(panel.querySelectorAll('input, textarea, select'))
          .filter((i: any) => !['checkbox', 'radio', 'file'].includes(i.type)).map((i: any) => `${i.id || i.name || 'box'}=${i.value}`);
        const titles = Array.from(panel.querySelectorAll('[title]')).map((e) => e.getAttribute('title') || '').filter((x) => /\d/.test(x));
        return boxes.concat(titles);
      }, tab);
      seen[`${tab}: filter bar`] = await page.evaluate(() => Array.from(document.querySelectorAll('#filterBar input'))
        .filter((i: any) => i.type === 'text').map((i: any) => `${i.id}=${i.value}`));
      seen[`${tab}: charts`] = await chartsUnder(page, '#tab-' + tab);
      if (tab === 'samplers') {
        const opened = await page.evaluate(() => {
          const row = document.querySelector('#tab-samplers tbody tr[data-sampler]');
          if (!row) return false;
          (window as any).toggleSamplerDetail(row);
          return true;
        });
        if (opened) {
          await page.waitForTimeout(1200);
          seen['samplers: first sampler\'s detail charts'] = await chartsUnder(page, '#tab-samplers .sampler-detail-row');
          // Without the charts' hover tooltips: opening the panel scrolls the page, and a chart drawn
          // under the resting mouse pointer may show one, depending on timing and the pointer rather
          // than on the reader (seen 2026-11-24: "200-500" and "0" from the distribution chart's
          // tooltip, for one reader only). The tooltips are compared on purpose by chartsUnder above.
          seen['samplers: text with the detail open'] = lines(await page.evaluate(() => {
            const tips = Array.from(document.querySelectorAll('#tab-samplers div')).filter((d) =>
              !d.querySelector('canvas, svg') && /z-index:\s*9999999/.test(d.getAttribute('style') || '')) as HTMLElement[];
            const shown = tips.map((t) => t.style.display);
            tips.forEach((t) => { t.style.display = 'none'; });
            const text = (document.getElementById('tab-samplers') as HTMLElement).innerText;
            tips.forEach((t, i) => { t.style.display = shown[i]; });
            return text;
          }));
        }
      }
      if (tab === 'errors' && await page.evaluate(() => !!document.getElementById('errorDetailsTable-group'))) {
        // grouped by sampler and type of error, what the old Grouped view showed (spec 002)
        await page.selectOption('#errorDetailsTable-group', 'error');
        await page.waitForTimeout(600);
        seen['errors: grouped view'] = lines(await page.evaluate(() => (document.getElementById('tab-errors') as HTMLElement).innerText));
        await page.selectOption('#errorDetailsTable-group', '');
      }
      if (tab === 'summary') {
        await page.evaluate(() => {
          (window as any).__clip.length = 0;
          (window as any).copySummaryAsHtml();
          if (typeof (window as any).copyVerdictBlock === 'function') (window as any).copyVerdictBlock();
        });
        await page.waitForTimeout(600);
        seen['Copy Summary / Copy Verdict'] = await page.evaluate(() => (window as any).__clip.flatMap((t: string) => t.split('\n')));
      }
      for (const fn of CSV[tab] || []) {
        const exists = await page.evaluate((f) => f.startsWith('table:')
          ? !!document.getElementById(f.slice(6)) : typeof (window as any)[f] === 'function', fn);
        if (!exists) continue;
        try {
          const [download] = await Promise.all([
            page.waitForEvent('download', { timeout: 4000 }),
            page.evaluate((f) => f.startsWith('table:') ? (window as any).WIR.table.csv(f.slice(6)) : (window as any)[f](), fn),
          ]);
          seen[`${tab}: ${fn} download`] = fs.readFileSync((await download.path())!, 'utf8').split('\n');
        } catch (e) {
          seen[`${tab}: ${fn} download`] = ['(nothing downloaded)'];
        }
      }
    }
  } finally {
    await context.close();
  }
  return seen;
}

/** Each line one reader saw and the other did not, paired up, per section. */
function differences(a: Seen, b: Seen): string[] {
  const out: string[] = [];
  for (const section of Array.from(new Set([...Object.keys(a), ...Object.keys(b)]))) {
    const left = a[section] || ['(not there)'];
    const right = b[section] || ['(not there)'];
    const onlyLeft = left.filter((l) => !right.includes(l));
    const onlyRight = right.filter((l) => !left.includes(l));
    for (let i = 0; i < Math.max(onlyLeft.length, onlyRight.length); i++) {
      out.push(`${section}:\n      ${READERS[0].who}: ${onlyLeft[i] ?? '(nothing)'}\n      ${READERS[1].who}: ${onlyRight[i] ?? '(nothing)'}`);
    }
  }
  return out;
}

test.describe('every reader sees the same report', () => {
  for (const id of REQUIRED_FIXTURES) {
    test(`${id}: an English reader in Jerusalem and a German reader in New York see the same page`, async ({ browser }) => {
      test.setTimeout(240000);
      // The two readers at once, each in its own context: most of a reader's minute is the waits for a tab's
      // charts to draw, so one after the other made this file a fifth of the browser suite's time (2026-11-25)
      const [first, second] = await Promise.all([
        whatTheReaderSees(browser, id, READERS[0]),
        whatTheReaderSees(browser, id, READERS[1]),
      ]);
      const found = differences(first, second);
      expect(found.slice(0, 40), `${found.length} differences between the two readers' pages`).toEqual([]);
    });
  }
});
