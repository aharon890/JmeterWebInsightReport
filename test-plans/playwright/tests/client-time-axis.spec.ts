import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';
import { loadClean, switchTab } from './helpers';

/**
 * XLOG-1: the client charts' time labels at a phone's width.
 *
 * The Timeline tab's charts, the Summary tab's response-time chart and the error timeline drew their
 * time axis with no hideOverlap, so at 390px the labels printed over one another — the overprint the
 * Server tab's charts lost in SF35, and EXPLORATORY-LOG's SF35 row left "predates B6 and stays". They
 * take one axis now (timelineXAxis); js-unit/client-time-axis.test.js holds the option, and this the
 * drawing: on the bundled ECharts a 358px chart drew 10 labels in 9 overlapping pairs without it, and
 * 5 in none with it.
 */

type Label = { t: string, x: number, y: number, w: number, h: number };

/** The chart's time-axis option and the time labels it drew — zrender's own boxes, as
    server-layout.spec.ts reads them — or null while it is not drawn. */
async function timeAxis(page: Page, domId: string): Promise<{ hideOverlap: unknown, labels: Label[] } | null> {
  return page.evaluate((id) => {
    const el = document.getElementById(id);
    const inst = el && (window as any).echarts.getInstanceByDom(el);
    if (!el || !inst) return null;
    const axis = (inst.getOption().xAxis || [])[0];
    const labels: any[] = [];
    inst.getZr().storage.getDisplayList(true).forEach((e: any) => {
      if ((e.type !== 'tspan' && e.type !== 'text') || e.invisible || e.ignore || !e.style || !e.style.text) return;
      /* A clock label, with its date in front on a run past midnight. */
      if (!/\d{1,2}:\d\d/.test(String(e.style.text))) return;
      const b = e.getBoundingRect().clone();
      b.applyTransform(e.getComputedTransform());
      labels.push({ t: String(e.style.text), x: b.x, y: b.y, w: b.width, h: b.height });
    });
    return { hideOverlap: axis && axis.axisLabel ? axis.axisLabel.hideOverlap : undefined, labels };
  }, domId);
}

const overlap = (a: Label, b: Label) =>
  Math.max(0, Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x))
  * Math.max(0, Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y));

/** 'ok', or what is wrong with one chart's time labels. */
async function labelsClear(page: Page, domId: string): Promise<string> {
  const axis = await timeAxis(page, domId);
  if (!axis) return 'not drawn';
  if (axis.hideOverlap !== true) return `hideOverlap is ${axis.hideOverlap}`;
  if (axis.labels.length < 2) return `${axis.labels.length} time labels`;
  const clashes: string[] = [];
  for (let i = 0; i < axis.labels.length; i++) {
    for (let j = i + 1; j < axis.labels.length; j++) {
      if (overlap(axis.labels[i], axis.labels[j]) > 0) clashes.push(`${axis.labels[i].t} / ${axis.labels[j].t}`);
    }
  }
  return clashes.join(', ') || 'ok';
}

/* The client charts over the run on each tab that has them — on the Timeline tab, the three every run
   draws; the rest take the same axis through finalizeTimelineChart. E02 records errors, so its error
   timeline is drawn. */
const CHARTS: Array<[string, string[]]> = [
  ['summary', ['summary-response-time-chart']],
  ['timeline', ['response-time-chart', 'throughput-chart', 'error-rate-chart']],
  ['errors', ['error-timeline-chart']]
];

test.describe('XLOG-1: the client charts\' time labels at 390px', () => {
  for (const [tab, ids] of CHARTS) {
    test(`[R] on the ${tab} tab no time label prints over another`, async ({ page }) => {
      await page.setViewportSize({ width: 390, height: 844 });
      await loadClean(page, 'E02');
      if (tab !== 'summary') await switchTab(page, tab);
      for (const id of ids) {
        await expect.poll(() => labelsClear(page, id), { message: id }).toBe('ok');
      }
    });
  }
});
