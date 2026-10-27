import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';
import { loadClean, switchTab } from './helpers';

/**
 * The Server tab's dialogs and the keyboard (plans/server-tab-followups.md, Batch 5) — against
 * E27: two catalogue components, orders-db (postgres) and events (kafka).
 *
 * SF28. Every #serverDialog dialog — + Add component, the metric picker, its Set thresholds step
 * and Generate's review — replaced its whole panel on each keystroke, click and tick. wireDialog
 * (the U4 contract: aria-modal, the title's id, Escape and the Tab trap) is bound to that panel,
 * and ran only on a "fresh" one, so after the first input the dialog was modal to nothing: Escape
 * left it open, Tab walked into the page behind the scrim, and the slide-in replayed per
 * character. A dialog opened again after Cancel reused opening number 1 and was never wired. The
 * picker's list and body scrolled back to the top, and every redraw left an ECharts instance
 * registered (2 -> 12 after ten).
 *
 * client-fixes.spec.ts' U4 tests open the two editors only, and the older specs here drive these
 * dialogs with .fill() and the test seams, which is why all of this was green. These drive the
 * real keyboard.
 */

async function openServer(page: Page) {
  await loadClean(page, 'E27');
  await switchTab(page, 'server');
}

const PANEL = '#serverDialog .wir-slideover';

/** True while the focused element is inside the open dialog's panel. */
function focusInDialog(page: Page) {
  return page.evaluate((sel) => !!document.activeElement?.closest(sel), PANEL);
}

/** Label the panel on screen, so a replaced one can be told from the one that was wired. */
async function markPanel(page: Page) {
  await page.locator(PANEL).evaluate((el) => {
    (el as any).wirOpening = 'the panel wireDialog wired';
  });
}

/** The panel on screen is the one markPanel labelled, and is not sliding in again. */
async function expectSamePanelStill(page: Page, why: string) {
  const state = await page.locator(PANEL).evaluate((el) => ({
    same: (el as any).wirOpening === 'the panel wireDialog wired',
    animations: el.getAnimations().length,
  }));
  expect(state.same, `${why}: the panel was replaced, so its wiring went with it`).toBe(true);
  expect(state.animations, `${why}: the slide-in replayed`).toBe(0);
}

/** Wait for the opening's own slide-in to finish, so a later animation can only be a replay. */
async function settle(page: Page) {
  await expect.poll(() => page.locator(PANEL).evaluate((el) => el.getAnimations().length)).toBe(0);
}

/** The U4 contract, as wireDialog states it: modal, named by its heading, Tab kept inside. */
async function expectModal(page: Page, why: string, tabs = 30) {
  const dialog = page.locator(PANEL);
  await expect(dialog, why).toHaveAttribute('aria-modal', 'true');
  await expect(dialog, why).toHaveAttribute('aria-labelledby', 'serverDialogTitle');
  expect(await focusInDialog(page), `${why}: focus is not in the dialog`).toBe(true);
  for (let i = 0; i < tabs; i++) {
    await page.keyboard.press(i % 7 === 6 ? 'Shift+Tab' : 'Tab');
    expect(await focusInDialog(page), `${why}: Tab ${i + 1} walked out of the dialog`).toBe(true);
  }
}

/** Escape closes it, and focus goes back to `opener`. */
async function expectEscapeCloses(page: Page, why: string, opener: string) {
  await page.keyboard.press('Escape');
  await expect(page.locator(PANEL), `${why}: Escape left the dialog open`).toHaveCount(0);
  expect(await page.evaluate((sel) => document.activeElement === document.querySelector(sel), opener),
    `${why}: focus did not go back to ${opener}`).toBe(true);
}

/** Every registered ECharts instance, and how many of them sit in no document at all. */
function chartRegistry(page: Page) {
  return page.evaluate(() => {
    const all: any[] = (window as any).chartInstances;
    return { count: all.length, detached: all.filter((c) => !document.contains(c.getDom())).length };
  });
}

const ORDERS_ADD = '.wir-component-card[data-component="orders-db"] .wir-component-add';
const EVENTS_ADD = '.wir-component-card[data-component="events"] .wir-component-add';

test.describe('SF28: a server dialog stays the dialog wireDialog wired', () => {
  test('[U] + Add component: typing, a type card and Advanced keep it modal, and Escape still closes it', async ({ page }) => {
    await openServer(page);
    await page.focus('#serverAddComponent');
    await page.keyboard.press('Enter');
    await expectModal(page, 'opened', 0);
    await settle(page);
    await markPanel(page);

    // Each character is a redraw.
    await page.locator(`${PANEL} [data-wir-dlg="id"]`).click();
    await page.keyboard.type('billing-db');
    await expect(page.locator(`${PANEL} [data-wir-dlg="id"]`)).toBeFocused();
    await expectSamePanelStill(page, 'after typing an id');
    // A click on a type card, and Advanced by the keyboard: the control used keeps its focus.
    await page.locator(`${PANEL} .wir-type-card`, { hasText: 'Kafka' }).click();
    await expect(page.locator(`${PANEL} .wir-type-card`, { hasText: 'Kafka' })).toBeFocused();
    await page.locator(`${PANEL} .wir-link`, { hasText: 'Advanced' }).focus();
    await page.keyboard.press('Enter');
    await expect(page.locator(`${PANEL} [data-wir-dlg="match"]`)).toBeVisible();
    await expect(page.locator(`${PANEL} .wir-link`, { hasText: 'Advanced' })).toBeFocused();
    await page.locator(`${PANEL} [data-wir-dlg="match"]`).click();
    await page.keyboard.type('instance="billing:9092"');
    await expect(page.locator(`${PANEL} [data-wir-dlg="match"]`)).toHaveValue('instance="billing:9092"');

    await expectSamePanelStill(page, 'after a dozen redraws');
    await expectModal(page, 'after a dozen redraws');
    await expectEscapeCloses(page, 'after a dozen redraws', '#serverAddComponent');
  });

  test('[U] a dialog opened again after Cancel or Escape is wired like the first', async ({ page }) => {
    await openServer(page);
    // + Add component, Cancel, and + Add component again: opening 1 twice, never wired.
    await page.focus('#serverAddComponent');
    await page.keyboard.press('Enter');
    await page.locator(`${PANEL} .wir-slideover-foot .wir-btn`, { hasText: 'Cancel' }).click();
    await expect(page.locator(PANEL)).toHaveCount(0);
    await page.focus('#serverAddComponent');
    await page.keyboard.press('Enter');
    await expectModal(page, 'the second + Add component');
    await expectEscapeCloses(page, 'the second + Add component', '#serverAddComponent');

    // The picker: + metric on one card, Escape, + metric on another.
    await page.focus(ORDERS_ADD);
    await page.keyboard.press('Enter');
    await expectModal(page, 'the first picker', 0);
    await expectEscapeCloses(page, 'the first picker', ORDERS_ADD);
    await page.focus(EVENTS_ADD);
    await page.keyboard.press('Enter');
    await expect(page.locator(`${PANEL} .wir-slideover-head`)).toContainText('Pick metrics — events · Kafka');
    await expectModal(page, 'the second picker');
    await expectEscapeCloses(page, 'the second picker', EVENTS_ADD);
  });

  test('[U] the picker: search, ticks and metric clicks keep it modal, and the list keeps its scroll', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 520 });
    await openServer(page);
    await page.focus(ORDERS_ADD);
    await page.keyboard.press('Enter');
    await settle(page);
    await markPanel(page);

    const search = page.locator(`${PANEL} [data-wir-dlg="search"]`);
    await search.click();
    await page.keyboard.type('cache');
    await expect(page.locator('#serverPickList .wir-pick-row')).toHaveCount(1);
    await expect(search).toBeFocused();
    for (let i = 0; i < 5; i++) await page.keyboard.press('Backspace');
    await expect(page.locator('#serverPickList .wir-pick-row')).toHaveCount(16);
    // A tick by the keyboard: the box keeps its focus and its state.
    const deadlocks = page.locator('[data-wir-dlg="pick:deadlocks"]');
    await deadlocks.focus();
    await page.keyboard.press('Space');
    await expect(deadlocks).toBeChecked();
    await expect(deadlocks).toBeFocused();
    await expect(page.locator(`${PANEL} .wir-pick-chip`)).toHaveCount(1);
    await expectSamePanelStill(page, 'after a search and a tick');

    // The list and the body scrolled to the bottom, and the LAST metric clicked: both stay put,
    // with the clicked row in view (they went back to 0, and the row out of sight).
    const list = page.locator('#serverPickList');
    const body = page.locator(`${PANEL} .wir-slideover-body`);
    // Where each is scrolled to, and the furthest it can be: the detail pane follows the clicked
    // metric, so the list's height can change with it — a list at its bottom stays at its bottom.
    const scroll = (loc: typeof list) => loc.evaluate((el) => ({ top: el.scrollTop, max: el.scrollHeight - el.clientHeight }));
    await list.evaluate((el) => { el.scrollTop = el.scrollHeight; });
    await body.evaluate((el) => { el.scrollTop = el.scrollHeight; });
    const before = { list: await scroll(list), body: await scroll(body) };
    expect(before.list.top, 'at 1440x520 the postgres list should scroll — the pin needs it to').toBeGreaterThan(0);
    const last = page.locator('#serverPickList .wir-pick-row').last().locator('.wir-pick-name');
    await last.click();
    await expect(page.locator('.wir-pick-detail h4')).toHaveText('Database size growth (bytes/min)');
    const after = { list: await scroll(list), body: await scroll(body) };
    expect(after.list.top, 'the list went back to the top').toBe(Math.min(before.list.top, after.list.max));
    expect(after.body.top, 'the body went back to the top').toBe(Math.min(before.body.top, after.body.max));
    await expect(last).toBeFocused();
    await expect(last).toBeInViewport();

    await expectSamePanelStill(page, 'after a metric click');
    await expectModal(page, 'after the picker redrew');
    await expectEscapeCloses(page, 'after the picker redrew', ORDERS_ADD);
  });

  test('[U] Set thresholds and Back put focus on the step\'s first control, and the step stays modal as it redraws', async ({ page }) => {
    await openServer(page);
    await page.focus(ORDERS_ADD);
    await page.keyboard.press('Enter');
    await settle(page);
    await markPanel(page);
    await page.locator('[data-wir-dlg="pick:deadlocks"]').focus();
    await page.keyboard.press('Space');
    await page.locator('#serverPickNext').focus();
    await page.keyboard.press('Enter');

    // A step replaces the control that took the reader there. Focus goes to the step's first
    // control, as an opening's does — never to <body>, where Escape is dead.
    await expect(page.locator(`${PANEL} .wir-slideover-head strong`)).toHaveText('Set thresholds — orders-db · Postgres');
    await expect(page.locator(`${PANEL} .wir-thresholds-head .wir-filter-chip`).first()).toBeFocused();
    // One opening, one panel: a step is not a new slide-in.
    await expectSamePanelStill(page, 'after Set thresholds');

    // A typed band redraws the row's verdict; a prefill chip by the keyboard rewrites every row.
    const fail = page.locator('[data-wir-dlg="row:deadlocks:failValue"]');
    await fail.click();
    await page.keyboard.press('End');
    await page.keyboard.type('5');
    await expect(fail).toHaveValue('05');
    await expect(fail).toBeFocused();
    await expect(page.locator('.wir-thresholds tr[data-metric="deadlocks"] .wir-chip')).toHaveText('PASS');
    const chip = page.locator(`${PANEL} .wir-thresholds-head .wir-filter-chip`, { hasText: 'Suggested' });
    await chip.focus();
    await page.keyboard.press('Enter');
    await expect(chip).toBeFocused();
    await expect(chip).toHaveClass(/wir-on/);
    // The typed 05 is gone: the box shows the band the rule wrote (the catalogue's fail > 0),
    // which is the band Add writes — a box once typed into no longer follows its attribute.
    await expect(fail).toHaveValue('0');
    await expect(page.locator('.wir-thresholds tr[data-metric="deadlocks"] .wir-chip')).toHaveText('FAIL');
    await expectSamePanelStill(page, 'after the thresholds step redrew');
    await expectModal(page, 'after the thresholds step redrew');

    // Back: focus on the Pick step's first control, and it too stays modal as it redraws.
    await page.locator(`${PANEL} .wir-btn`, { hasText: 'Back' }).focus();
    await page.keyboard.press('Enter');
    await expect(page.locator(`${PANEL} .wir-slideover-head strong`)).toHaveText('Pick metrics — orders-db · Postgres');
    await expect(page.locator(`${PANEL} [data-wir-dlg="search"]`)).toBeFocused();
    await expect(page.locator('[data-wir-dlg="pick:deadlocks"]')).toBeChecked();
    await page.keyboard.type('dead');
    await expect(page.locator('#serverPickList .wir-pick-row')).toHaveCount(1);
    await expectSamePanelStill(page, 'after Back and a search');
    await expectModal(page, 'after Back and a search');
    await expectEscapeCloses(page, 'after Back and a search', ORDERS_ADD);
  });

  test('[U] Generate\'s review: a tick and a typed fail keep it modal, and Escape still closes it', async ({ page }) => {
    await openServer(page);
    const generate = '.wir-server-actions .wir-btn[onclick*="reviewSeeds"]';
    await page.focus(generate);
    await page.keyboard.press('Enter');
    await expect(page.locator(`${PANEL} .wir-seed-review`)).toBeVisible();
    await settle(page);
    await markPanel(page);

    const firstSeed = page.locator(`${PANEL} [data-wir-dlg="seed:0"]`);
    await firstSeed.focus();
    await page.keyboard.press('Space');
    await expect(firstSeed).not.toBeChecked();
    await expect(firstSeed).toBeFocused();
    const fail = page.locator(`${PANEL} [data-wir-dlg="seedfail:1"]`);
    await fail.click();
    await page.keyboard.press('End');
    await page.keyboard.type('9');
    await expect(fail).toBeFocused();

    await expectSamePanelStill(page, 'after a tick and a typed fail');
    await expectModal(page, 'after a tick and a typed fail');
    await expectEscapeCloses(page, 'after a tick and a typed fail', generate);
  });

  test('[U] the picker keeps one chart however often it redraws, and disposes it on a step and on close', async ({ page }) => {
    await openServer(page);
    const baseline = await chartRegistry(page);
    await page.focus(ORDERS_ADD);
    await page.keyboard.press('Enter');
    await expect.poll(async () => (await chartRegistry(page)).count).toBe(baseline.count + 1);
    const chartId = () => page.evaluate(() =>
      (window as any).echarts.getInstanceByDom(document.getElementById('serverPickChart'))?.id ?? null);
    const first = await chartId();
    expect(first).not.toBeNull();

    // Ten redraws: five keystrokes, five deleted.
    await page.locator('[data-wir-dlg="search"]').click();
    await page.keyboard.type('trans');
    for (let i = 0; i < 5; i++) await page.keyboard.press('Backspace');
    expect(await chartRegistry(page)).toEqual({ count: baseline.count + 1, detached: 0 });
    expect(await chartId(), 'a redraw made the chart again').toBe(first);

    // Another metric focused is drawn on the same chart; one with no data disposes it.
    await page.locator('.wir-pick-name', { hasText: 'Cache hit ratio' }).click();
    expect(await chartRegistry(page)).toEqual({ count: baseline.count + 1, detached: 0 });
    await page.locator('.wir-pick-name', { hasText: 'Replication lag' }).click();
    await expect(page.locator('#serverPickChart')).toContainText('No data for this metric in this run.');
    expect(await chartRegistry(page)).toEqual({ count: baseline.count, detached: 0 });
    await page.locator('.wir-pick-name', { hasText: 'Cache hit ratio' }).click();
    expect(await chartRegistry(page)).toEqual({ count: baseline.count + 1, detached: 0 });

    // A step change takes the pane, and its chart, away; Back draws one again; close disposes it.
    await page.locator('[data-wir-dlg="pick:deadlocks"]').check();
    await page.click('#serverPickNext');
    expect(await chartRegistry(page)).toEqual({ count: baseline.count, detached: 0 });
    await page.locator(`${PANEL} .wir-btn`, { hasText: 'Back' }).click();
    expect(await chartRegistry(page)).toEqual({ count: baseline.count + 1, detached: 0 });
    await page.keyboard.press('Escape');
    await expect(page.locator(PANEL)).toHaveCount(0);
    expect(await chartRegistry(page)).toEqual({ count: baseline.count, detached: 0 });
  });

  test('[R] the 4.1.33 review: hovering the picker\'s chart shows no board row\'s tooltip over the dialog', async ({ page }) => {
    // The picker's focus chart was made in the board's connected group (createServerChart), so with rows
    // open, hovering it ran every row chart's axis tooltip, and since 4.1.27 those sit on the body, above
    // the dialog (petstore R1 on 4.1.33: two row tooltips over the picker on 8 of 11 hover steps, finding 7).
    await page.setViewportSize({ width: 1280, height: 900 });
    await openServer(page);
    const ROWS = ['orders-db-connections-used-pct', 'events-consumer-lag-max'];
    for (const id of ROWS) {
      await page.evaluate((k) => { const W = (window as any).WIR.serverKpis; W.toggle(W._keyOf(k)); }, id);
      await expect.poll(() => page.evaluate((k) => {
        const W = window as any;
        const el = document.getElementById('serverChart-' + W.WIR.serverKpis._keyOf(k));
        return !!el && !!W.echarts.getInstanceByDom(el);
      }, id), { message: `${id}'s chart is drawn` }).toBe(true);
    }
    await page.click(ORDERS_ADD);
    await page.locator('.wir-pick-name', { hasText: 'Cache hit ratio' }).click();
    // Hide every tooltip, hover the picker's chart, and count the tooltips shown on the body.
    const hover = () => page.evaluate((rows) => {
      const W = window as any;
      const pick = W.echarts.getInstanceByDom(document.getElementById('serverPickChart')!);
      rows.map((k: string) => W.echarts.getInstanceByDom(document.getElementById('serverChart-' + W.WIR.serverKpis._keyOf(k))!))
        .concat([pick]).forEach((c: any) => c.dispatchAction({ type: 'hideTip' }));
      pick.dispatchAction({ type: 'showTip', x: pick.getWidth() / 2, y: pick.getHeight() / 2 });
      return [...document.querySelectorAll('div')].filter((d) =>
        /z-index:\s*9999999/.test(d.getAttribute('style') || '') && d.style.display !== 'none'
        && getComputedStyle(d).visibility !== 'hidden' && Number(getComputedStyle(d).opacity) > 0
        && d.offsetParent !== null).length;
    }, ROWS);
    const group = (g: string | null) => page.evaluate((to) => {
      const W = window as any;
      const pick = W.echarts.getInstanceByDom(document.getElementById('serverPickChart')!);
      const was = pick.group;
      if (to !== null) { pick.group = to; W.echarts.connect(to); }
      return was;
    }, g);
    const own = await group(null);
    expect(own, 'the picker\'s chart in the board\'s group').not.toBe('wir-server-linked');
    // Positive control: in the board's group the detector sees the rows' tooltips beside the picker's own,
    // so the one below is the grouping, not a hover that showed nothing.
    await group('wir-server-linked');
    await expect.poll(hover, { message: 'the rows\' tooltips, linked' }).toBeGreaterThan(1);
    await group(own);
    await expect.poll(hover, { message: 'the picker\'s own tooltip, and no row\'s' }).toBe(1);
    // connectCharts (every tab switch and chart rebuild runs it) leaves the picker's group as it is: it
    // regrouped every chart not in the board's group into the client's.
    await page.evaluate(() => (window as any).connectCharts());
    expect(await group(null), 'the picker\'s group after connectCharts').toBe(own);
    await expect.poll(hover, { message: 'still only the picker\'s own tooltip' }).toBe(1);
  });

  test('[U] U2: the picker\'s chart is drawn again when the page rebuilds every chart — dark mode, colour-blind mode, refreshAllCharts', async ({ page }) => {
    // refreshAllCharts disposes every registered instance — the picker's focus chart included,
    // since createChart registers it — and rebuilt only the board's, so the detail pane kept its
    // facts above an empty box. No reader can reach it today (the picker is modal: the scrim covers
    // the theme toggles and Tab stays in the panel), so it is driven the way the specs above toggle
    // dark mode with the picker open.
    await openServer(page);
    const baseline = await chartRegistry(page);
    await page.click(ORDERS_ADD);
    await page.locator('.wir-pick-name', { hasText: 'Cache hit ratio' }).click();
    const drawn = () => page.evaluate(() => {
      const el = document.getElementById('serverPickChart')!;
      const chart = (window as any).echarts.getInstanceByDom(el);
      return { live: !!chart && !chart.isDisposed(), canvases: el.querySelectorAll('canvas').length };
    });
    expect(await drawn(), 'precondition: the picker draws Cache hit ratio').toEqual({ live: true, canvases: 1 });
    expect(await chartRegistry(page)).toEqual({ count: baseline.count + 1, detached: 0 });

    for (const [what, call] of [['dark mode', 'toggleDarkMode'], ['colour-blind mode', 'toggleColorblindMode'],
      ['refreshAllCharts', 'refreshAllCharts']]) {
      await page.evaluate((fn) => (window as any)[fn](), call);
      await expect.poll(drawn, { message: `${what}: the picker's chart was disposed and never drawn again` })
        .toEqual({ live: true, canvases: 1 });
      // One chart, as before: drawn again, never twice and never leaked.
      expect(await chartRegistry(page), what).toEqual({ count: baseline.count + 1, detached: 0 });
      await expect(page.locator(PANEL), `${what}: the picker closed`).toHaveCount(1);
    }

    // Closing still disposes it.
    await page.keyboard.press('Escape');
    await expect(page.locator(PANEL)).toHaveCount(0);
    expect(await chartRegistry(page)).toEqual({ count: baseline.count, detached: 0 });
  });

  test('[R] C4, the 4.1.33 review: the picker\'s time axis follows its own points, not the Timeline\'s range', async ({ page }) => {
    // Its tick floor and labels read the Timeline's visible range, as the rows' did (finding 8). E27's run is
    // 48 s, so over its own points it may tick finer than a minute and print seconds, whatever the Timeline
    // shows - here a range of five minutes, under which the visible range would have fixed a 60 s floor.
    await openServer(page);
    await page.evaluate(() => { (window as any).visibleTimeRangeMs = 300000; });
    await page.click(ORDERS_ADD);
    await page.locator('.wir-pick-name', { hasText: 'Cache hit ratio' }).click();
    const axis = await page.evaluate(() => {
      const pick = (window as any).echarts.getInstanceByDom(document.getElementById('serverPickChart')!);
      const x = pick.getOption().xAxis[0];
      const data = pick.getOption().series[0].data;
      return { minInterval: x.minInterval ?? null, label: x.axisLabel.formatter(data[0][0]) };
    });
    expect(axis.minInterval, 'a 48 s span has no minute floor').toBeNull();
    expect(axis.label, 'and prints its seconds').toMatch(/^\d{2}:\d{2}:\d{2}$/);
  });
});

/*
 * SF29, the dialogs' half: a dialog closes onto a control the commit's render had replaced, or
 * onto one inside a dialog that was itself gone, and focus landed on <body>.
 */
test.describe('SF29: a server dialog closes onto a control that is on the page', () => {
  test('[U] Cancel on the picker + Add component opened goes to the new card\'s + metric', async ({ page }) => {
    await openServer(page);
    await page.focus('#serverAddComponent');
    await page.keyboard.press('Enter');
    await page.locator(`${PANEL} [data-wir-dlg="id"]`).click();
    await page.keyboard.type('billing-db');
    await page.focus('#serverAddComponentBtn');
    await page.keyboard.press('Enter');
    await expect(page.locator(`${PANEL} .wir-slideover-head strong`)).toHaveText('Pick metrics — billing-db · Postgres');
    await page.locator(`${PANEL} .wir-slideover-foot .wir-btn`, { hasText: 'Cancel' }).focus();
    await page.keyboard.press('Enter');
    await expect(page.locator(PANEL)).toHaveCount(0);
    // Not "+ Add component" inside the dialog that went, and not <body>: the card it made.
    await expect(page.locator('.wir-component-card[data-component="billing-db"] .wir-component-add')).toBeFocused();
  });

  test('[U] Escape on the custom editor + Add component opened goes to the new card\'s + metric', async ({ page }) => {
    // The custom path takes over the opener (openMetrics): the dialog that held + Add component
    // goes, and the editor it hands over to used to close onto <body>.
    await openServer(page);
    await page.focus('#serverAddComponent');
    await page.keyboard.press('Enter');
    await page.locator(`${PANEL} .wir-type-card`, { hasText: 'Custom' }).focus();
    await page.keyboard.press('Enter');
    await page.locator(`${PANEL} [data-wir-dlg="id"]`).click();
    await page.keyboard.type('billing-api');
    await page.focus('#serverAddComponentBtn');
    await page.keyboard.press('Enter');
    await expect(page.locator('#serverEditor .wir-slideover')).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(page.locator('#serverEditor .wir-slideover')).toHaveCount(0);
    await expect(page.locator('.wir-component-card[data-component="billing-api"] .wir-component-add')).toBeFocused();
  });

  test('[U] Escape on the editor the picker\'s "Custom query…" opened goes back to that card\'s + metric', async ({ page }) => {
    // pickCustom closes the picker and opens the editor in its place: the picker's opener, the
    // card's "+ metric", is the editor's now.
    await openServer(page);
    await page.focus(ORDERS_ADD);
    await page.keyboard.press('Enter');
    await page.locator(`${PANEL} .wir-link`, { hasText: 'Custom query…' }).focus();
    await page.keyboard.press('Enter');
    await expect(page.locator(PANEL)).toHaveCount(0);
    await expect(page.locator('#serverEditor .wir-slideover')).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(page.locator('#serverEditor .wir-slideover')).toHaveCount(0);
    await expect(page.locator(ORDERS_ADD)).toBeFocused();
  });

  test('[U] Add N server asserts goes back to the card\'s + metric, and the review\'s Add to the board', async ({ page }) => {
    await openServer(page);
    await page.focus(ORDERS_ADD);
    await page.keyboard.press('Enter');
    await page.locator('[data-wir-dlg="pick:deadlocks"]').focus();
    await page.keyboard.press('Space');
    await page.locator('#serverPickNext').focus();
    await page.keyboard.press('Enter');
    await page.locator(`${PANEL} .wir-btn-primary`, { hasText: 'Add 1 server assert' }).focus();
    await page.keyboard.press('Enter');
    await expect(page.locator(PANEL)).toHaveCount(0);
    // The commit redrew the board; the "+ metric" that opened the picker is found again.
    await expect(page.locator(ORDERS_ADD)).toBeFocused();

    const generate = '.wir-server-actions .wir-btn[onclick*="reviewSeeds"]';
    await page.focus(generate);
    await page.keyboard.press('Enter');
    await page.locator('#serverAddSeedsBtn').focus();
    await page.keyboard.press('Enter');
    await expect(page.locator(PANEL)).toHaveCount(0);
    // Back to Generate while it is still offered, else to the first row it added — on the board.
    expect(await page.evaluate(() => !!document.activeElement?.closest('#serverBoard')),
      'focus fell out of the board after Add').toBe(true);
  });
});

/*
 * SF31. The picker's state was invisible: no aria-pressed on a category, no aria-current on the
 * metric the detail pane describes, and a --color-bg fill for both that was 1.04:1 on the light
 * panel and the panel's own colour in dark mode. The categories counted the whole catalogue
 * whatever else was filtered, and the detail pane went on describing a metric the filters hid.
 * (Its fourth complaint — a click resets the scroll — is SF28's, pinned above.)
 */
test.describe('SF31: the picker shows its state', () => {
  test('[U] the selected category is aria-pressed and the described metric aria-current, by the keyboard', async ({ page }) => {
    await openServer(page);
    await page.focus(ORDERS_ADD);
    await page.keyboard.press('Enter');
    const cats = page.locator(`${PANEL} .wir-pick-cat`);
    const all = cats.filter({ hasText: 'All (' });
    const locks = cats.filter({ hasText: 'Locks (' });
    await expect(all).toHaveAttribute('aria-pressed', 'true');
    await expect(locks).toHaveAttribute('aria-pressed', 'false');
    await locks.focus();
    await page.keyboard.press('Enter');
    await expect(locks).toHaveAttribute('aria-pressed', 'true');
    await expect(all).toHaveAttribute('aria-pressed', 'false');
    await expect(locks).toBeFocused();
    expect(await cats.evaluateAll((els) => els.filter((e) => e.getAttribute('aria-pressed') === 'true').length)).toBe(1);

    // One metric is the one described, and it says so: the focused metric (up) is hidden by Locks,
    // so the category's first metric is described.
    const current = page.locator('#serverPickList .wir-pick-name[aria-current="true"]');
    await expect(current).toHaveCount(1);
    await expect(current).toContainText('Sessions waiting on a lock');
    await press(page, all);
    const cache = page.locator('#serverPickList .wir-pick-name', { hasText: 'Cache hit ratio' });
    await cache.focus();
    await page.keyboard.press('Enter');
    await expect(cache).toHaveAttribute('aria-current', 'true');
    await expect(current).toHaveCount(1);
    await expect(page.locator('.wir-pick-detail h4')).toHaveText('Cache hit ratio (%)');
  });

  test('[U] the selection marker is at least 3:1 against what it sits on, in light and dark mode', async ({ page }) => {
    await openServer(page);
    await page.click(ORDERS_ADD);
    await page.locator(`${PANEL} .wir-pick-cat`, { hasText: 'Connections (' }).click();
    await page.locator('#serverPickList .wir-pick-name', { hasText: 'Active connections' }).click();
    for (const theme of ['light', 'dark']) {
      if (theme === 'dark') await page.evaluate(() => (window as any).toggleDarkMode());
      for (const sel of ['#serverPickList .wir-pick-row.wir-focus', `${PANEL} .wir-pick-cat.wir-on`]) {
        const ratios = await markerContrast(page, sel);
        expect(ratios.marker, `${theme}: ${sel} has no marker of its own`).not.toBeNull();
        expect(ratios.onPanel, `${theme}: ${sel} against the panel`).toBeGreaterThanOrEqual(3);
        expect(ratios.onSelf, `${theme}: ${sel} against its own fill`).toBeGreaterThanOrEqual(3);
      }
    }
  });

  test('[U] the marked metric row\'s secondary text keeps 4.5:1, in light and dark mode', async ({ page }) => {
    // The --color-bg-accent fill under the marked row took "no data" (and "already asserted", and
    // the peak) to 4.26:1 light and 4.04:1 dark — 12px text, under 1.4.3's 4.5:1. The ring carries
    // the state on its own (the test above).
    await openServer(page);
    await page.click(ORDERS_ADD);
    await page.locator('#serverPickList .wir-pick-name', { hasText: 'Replication lag' }).click();
    for (const theme of ['light', 'dark']) {
      if (theme === 'dark') await page.evaluate(() => (window as any).toggleDarkMode());
      const ratios = await page.evaluate(() => {
        const row = document.querySelector('#serverPickList .wir-pick-row.wir-focus')!;
        const parse = (c: string) => {
          const m = c.match(/rgba?\(([\d.]+),\s*([\d.]+),\s*([\d.]+)(?:,\s*([\d.]+))?\)/);
          return m && !(m[4] !== undefined && parseFloat(m[4]) === 0) ? [+m[1], +m[2], +m[3]] : null;
        };
        const lum = (rgb: number[]) => {
          const [r, g, b] = rgb.map((v) => { const s = v / 255; return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4); });
          return 0.2126 * r + 0.7152 * g + 0.0722 * b;
        };
        const under = (n: Element | null) => {
          for (let e = n; e; e = e.parentElement) { const c = parse(getComputedStyle(e).backgroundColor); if (c) return c; }
          return [255, 255, 255];
        };
        return Array.from(row.querySelectorAll('.wir-dim')).map((el) => {
          const fg = parse(getComputedStyle(el).color)!;
          const bg = under(el);
          const [hi, lo] = [lum(fg), lum(bg)].sort((a, b) => b - a);
          return { text: (el.textContent || '').trim(), ratio: (hi + 0.05) / (lo + 0.05) };
        });
      });
      expect(ratios.map((r) => r.text), `${theme}: the marked row says it has no data`).toContain('no data');
      for (const r of ratios) {
        expect(r.ratio, `${theme}: "${r.text}" in the marked row`).toBeGreaterThanOrEqual(4.5);
      }
    }
  });

  test('[U] category counts follow the other filters, and the detail pane follows the list', async ({ page }) => {
    await openServer(page);
    await page.click(ORDERS_ADD);
    const catTexts = () => page.locator(`${PANEL} .wir-pick-cat`).allTextContents();
    expect(await catTexts()).toEqual(['All (16)', 'Availability (1)', 'Connections (2)', 'Transactions (4)',
      'Locks (2)', 'Cache (1)', 'Replication (2)', 'Disk (4)']);

    // Replication lag has no data in E27: described, then hidden by "Has data in this run".
    await page.locator('#serverPickList .wir-pick-name', { hasText: 'Replication lag' }).click();
    await expect(page.locator('.wir-pick-detail h4')).toHaveText('Replication lag (s)');
    await page.check('[data-wir-dlg="hasDataOnly"]');
    expect(await catTexts()).toEqual(['All (6)', 'Availability (1)', 'Connections (1)', 'Transactions (1)',
      'Locks (1)', 'Cache (1)', 'Replication (0)', 'Disk (1)']);
    // The pane describes a metric the list shows: the first one, not the one it hid.
    await expect(page.locator('.wir-pick-detail h4')).toHaveText('Exporter can reach the database');
    await expect(page.locator('#serverPickList .wir-pick-name[aria-current="true"]')).toContainText('Exporter can reach the database');
    // "Disk (1)" opens one metric, not "No metric matches these filters".
    await page.locator(`${PANEL} .wir-pick-cat`, { hasText: 'Disk (' }).click();
    expect(await page.locator('#serverPickList .wir-pick-row').evaluateAll((rows) => rows.map((r) => r.getAttribute('data-metric'))))
      .toEqual(['database.size.growth']);
    await expect(page.locator('.wir-pick-detail h4')).toHaveText('Database size growth (bytes/min)');

    // A search that leaves nothing: every count is 0 and nothing is described.
    await page.locator(`${PANEL} .wir-pick-cat`, { hasText: 'All (' }).click();
    await page.locator('[data-wir-dlg="search"]').fill('zzzq');
    await expect(page.locator('#serverPickList')).toContainText('No metric matches these filters.');
    expect((await catTexts()).every((t) => / \(0\)$/.test(t)), (await catTexts()).join(', ')).toBe(true);
    await expect(page.locator('.wir-pick-detail h4')).toHaveCount(0);
  });
});

async function press(page: Page, control: ReturnType<Page['locator']>) {
  await control.focus();
  await page.keyboard.press('Enter');
}

/**
 * The contrast of a selection marker (WCAG 1.4.11, 3:1 for a state indicator): its colour — a
 * box-shadow's, else an outline's, else the fill that is all the old marker had — against the
 * panel under it and against the element's own fill.
 */
function markerContrast(page: Page, selector: string) {
  return page.evaluate((sel) => {
    const el = document.querySelector(sel) as HTMLElement;
    const parse = (c: string | null) => {
      const m = c && c.match(/rgba?\(([\d.]+),\s*([\d.]+),\s*([\d.]+)(?:,\s*([\d.]+))?\)/);
      if (!m || (m[4] !== undefined && parseFloat(m[4]) === 0)) return null;
      return [parseFloat(m[1]), parseFloat(m[2]), parseFloat(m[3])];
    };
    const lum = (rgb: number[]) => {
      const [r, g, b] = rgb.map((v) => {
        const s = v / 255;
        return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
      });
      return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    };
    const ratio = (a: number[], b: number[]) => {
      const [hi, lo] = [lum(a), lum(b)].sort((x, y) => y - x);
      return (hi + 0.05) / (lo + 0.05);
    };
    const fillUnder = (node: Element | null) => {
      for (let n = node; n; n = n.parentElement) {
        const c = parse(getComputedStyle(n).backgroundColor);
        if (c) return c;
      }
      return [255, 255, 255];
    };
    const style = getComputedStyle(el);
    const own = parse(style.backgroundColor);
    const marker = (style.boxShadow !== 'none' && parse(style.boxShadow))
      || (style.outlineStyle !== 'none' && parseFloat(style.outlineWidth) > 0 && parse(style.outlineColor))
      || own;
    const panel = fillUnder(el.parentElement);
    return {
      marker,
      onPanel: marker ? ratio(marker, panel) : 0,
      onSelf: marker ? ratio(marker, own || panel) : 0,
    };
  }, selector);
}
