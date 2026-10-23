import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';
import { loadClean, switchTab } from './helpers';

/**
 * Adding to the Server tab (plans/report-design-and-bugs-2026-10.md §3, "Add flow"; step 4b):
 * + Add component, then the metric picker and its thresholds step — against E27, two catalogue
 * components (orders-db: postgres, events: kafka) with one assert each and every other catalogue
 * metric the CSV carries collected beside them (D1).
 */

async function openServer(page: Page) {
  await loadClean(page, 'E27');
  await switchTab(page, 'server');
}

/**
 * Rewrite one collected series of the page's chart data BEFORE report.js reads it. runOf caches
 * per component and metric, and the cache is filled at the first render by the toolbar's Generate
 * count — so a series rewritten after load is invisible to the picker (the pin trap the plan
 * records). The template declares `var chartData = {...}`; a setter defined first on window is
 * what that assignment calls.
 */
async function patchSeriesBeforeLoad(page: Page, component: string, metric: string,
  patch: Record<string, unknown>, everyPointTo: number | null = null) {
  await patchChartDataBeforeLoad(page, { series: [{ component, metric, patch, everyPointTo }] });
}

/**
 * As patchSeriesBeforeLoad, for several series and the payload's window at once. One init script
 * for all of them: a second one would define the `chartData` setter again and drop the first's.
 */
async function patchChartDataBeforeLoad(page: Page, patches: {
  series?: { component: string, metric: string, patch: Record<string, unknown>, everyPointTo?: number | null }[],
  window?: Record<string, unknown>,
  /** Added to window.start to give the payload a ramp-up — E27's has none. */
  rampUpAfterMs?: number,
}) {
  await page.addInitScript((a) => {
    let stored: any;
    Object.defineProperty(window, 'chartData', {
      configurable: true,
      get() { return stored; },
      set(value) {
        const payload = value && typeof value === 'object' ? value.serverMetrics : null;
        if (payload) {
          (a.series || []).forEach((p: any) => {
            const series = (payload.series || []).find((s: any) => s.component === p.component && s.metric === p.metric);
            if (!series) return;
            Object.assign(series, p.patch);
            if (p.everyPointTo !== null && p.everyPointTo !== undefined) {
              series.instances.forEach((i: any) => i.points.forEach((pt: any) => { pt[1] = p.everyPointTo; }));
            }
          });
          if (a.window) Object.assign(payload.window, a.window);
          if (a.rampUpAfterMs !== undefined) payload.window.rampUpEnd = payload.window.start + a.rampUpAfterMs;
        }
        stored = value;
      },
    });
  }, patches);
}

/**
 * Replace the page's document with the one the report shipped, its serverMetrics block given this
 * `collection` and these fields merged into its components — as Open file… with such a file would.
 */
async function adoptServerBlock(page: Page, edit: {
  collection?: Record<string, unknown>, components?: Record<string, Record<string, unknown>>,
}) {
  await page.evaluate((e) => {
    const W = (window as any).WIR;
    W.serverKpis.closeDialog();
    const doc = JSON.parse(JSON.stringify((window as any).globalChartData.annotationsDocument));
    if (e.collection) doc.serverMetrics.collection = e.collection;
    Object.keys(e.components || {}).forEach((id) => Object.assign(doc.serverMetrics.components[id], e.components![id]));
    W.document.adopt(doc);
    W.serverKpis.reread();
  }, edit);
}

/** The Set thresholds row of one metric. */
function thresholdsRow(page: Page, metric: string) {
  return page.locator(`.wir-thresholds tr[data-metric="${metric}"]`);
}

/** Pick these metrics on a component and go to Set thresholds. */
async function pickAndSet(page: Page, component: string, metrics: string[]) {
  await page.click(`.wir-component-card[data-component="${component}"] .wir-component-add`);
  for (const m of metrics) await page.check(`[data-wir-dlg="pick:${m}"]`);
  await page.click('#serverPickNext');
}

/** The type cards, in index.json's order, then Custom: by their labels. */
const TYPE_CARDS = [/Postgres/, /Kafka/, /Redis/, /Linux host/, /AWS EC2/, /Oracle Database/, /InfluxDB 2/,
  /InfluxDB 3/, /JVM/, /HTTP service/, /Kubernetes pod/, /MySQL/, /Custom/];

/** The serverMetrics block a Save would write now. */
async function block(page: Page) {
  return page.evaluate(() => JSON.parse((window as any).WIR.document.text()).serverMetrics);
}

test.describe('+ Add component', () => {
  test('[U] SF46: each type card names the exporter its metrics need — in the dialog and on the empty tab', async ({ page }) => {
    // The catalogue carried it only inside `source`, a maintainer's note the page never showed:
    // "Postgres / Connections, transactions, … / 10 metrics", nothing about what has to be
    // scraping the database. The type file's own `exporter` now, a prerequisite, not provenance.
    await openServer(page);
    await page.click('#serverAddComponent');
    const dialog = page.locator('#serverDialog .wir-slideover');
    const exporter = (type: string) => dialog.locator('.wir-type-card', { hasText: type }).locator('.wir-type-exporter');
    await expect(exporter('Postgres')).toHaveText('Needs postgres_exporter');
    await expect(exporter('Kafka')).toHaveText("Needs kafka_exporter, and the JMX exporter with Strimzi's rules");
    await expect(exporter('Redis')).toHaveText('Needs redis_exporter');
    await expect(exporter('Linux host')).toHaveText('Needs node_exporter');
    // A type with no prometheus query names where its metrics come from: CloudWatch's namespaces,
    // and the CloudWatch agent, which alone publishes the ENA driver's allowance counters.
    await expect(exporter('AWS EC2')).toHaveText('Needs CloudWatch AWS/EC2 and AWS/EBS + the agent for ENA');
    // Five Oracle metrics need a custom metrics file (Tutorial 15 gives it); InfluxDB serves its own.
    await expect(exporter('Oracle Database')).toHaveText('Needs Oracle Database Metrics Exporter 2.x + a TOML for 5 metrics');
    await expect(exporter('InfluxDB 2')).toHaveText("Needs InfluxDB's own /metrics (v2)");
    await expect(exporter('InfluxDB 3')).toHaveText("Needs InfluxDB's own /metrics (v3 Core)");
    // The JVM and HTTP types read Micrometer's names; a pod's KPIs join two exporters.
    await expect(exporter('JVM')).toHaveText("Needs Micrometer (Spring Boot Actuator's /actuator/prometheus)");
    await expect(exporter('HTTP service')).toHaveText("Needs Micrometer's http.server.requests (Spring Boot Actuator)");
    await expect(exporter('Kubernetes pod')).toHaveText("Needs cAdvisor (the kubelet's) and kube-state-metrics");
    await expect(exporter('MySQL')).toHaveText('Needs mysqld_exporter');
    await expect(exporter('Custom'), 'Custom writes its own query, so it needs nothing named').toHaveCount(0);
    // The maintainer's note stays in the file.
    await expect(dialog).not.toContainText('prometheus-community');

    // The empty tab's inline cards are the same cards.
    await page.locator('#serverDialog .wir-link[aria-label="Close"]').click();
    await page.evaluate(() => {
      const W = window as any;
      const d = JSON.parse(JSON.stringify(W.chartData));
      delete d.annotationsDocument.serverMetrics;
      W.WIR.serverKpis.init(d);
    });
    const inline = page.locator('#serverBoard .wir-first-component .wir-type-card');
    await expect(inline).toHaveText(TYPE_CARDS);
    await expect(inline.locator('.wir-type-exporter')).toHaveText(['Needs postgres_exporter',
      "Needs kafka_exporter, and the JMX exporter with Strimzi's rules", 'Needs redis_exporter',
      'Needs node_exporter', 'Needs CloudWatch AWS/EC2 and AWS/EBS + the agent for ENA',
      'Needs Oracle Database Metrics Exporter 2.x + a TOML for 5 metrics', "Needs InfluxDB's own /metrics (v2)",
      "Needs InfluxDB's own /metrics (v3 Core)", "Needs Micrometer (Spring Boot Actuator's /actuator/prometheus)",
      "Needs Micrometer's http.server.requests (Spring Boot Actuator)",
      "Needs cAdvisor (the kubelet's) and kube-state-metrics", 'Needs mysqld_exporter']);
  });

  test('[U] needs a type and a fresh id, writes the component into the document, then opens its picker', async ({ page }) => {
    await openServer(page);
    await page.click('#serverAddComponent');
    const dialog = page.locator('#serverDialog .wir-slideover');
    await expect(dialog).toBeVisible();
    await expect(dialog.locator('.wir-type-card')).toHaveText(TYPE_CARDS);
    // No id, no button; an id already taken, refused by name.
    await expect(page.locator('#serverAddComponentBtn')).toBeDisabled();
    await dialog.locator('[data-wir-dlg="id"]').fill('orders-db');
    await expect(dialog.locator('.wir-problem')).toHaveText('There is already a component called "orders-db".');
    await expect(page.locator('#serverAddComponentBtn')).toBeDisabled();
    // The input keeps its focus while the dialog redraws around it.
    await expect(dialog.locator('[data-wir-dlg="id"]')).toBeFocused();

    await dialog.locator('[data-wir-dlg="id"]').fill('billing-db');
    await dialog.locator('[data-wir-dlg="description"]').fill('Billing DB');
    await dialog.locator('.wir-type-card', { hasText: 'Postgres' }).click();
    await page.click('#serverAddComponentBtn');

    // Into the document, as a draft: the type and the description, nothing it did not ask for.
    expect((await block(page)).components['billing-db']).toEqual({ type: 'postgres', description: 'Billing DB' });
    await expect(page.locator('#serverUnsavedPill')).toHaveText('1 unsaved change');
    await expect(page.locator('.wir-component-card[data-component="billing-db"]')).toContainText('No server asserts on billing-db yet');
    // ...and its picker is open (design §3: "→ opens + metric on the new card").
    await expect(page.locator('#serverDialog .wir-slideover-head')).toContainText('Pick metrics — billing-db · Postgres');
  });

  test('[U] SF44: Cancel on the picker + Add component opened says what the empty component does, and Undo takes it back', async ({ page }) => {
    // The component is committed before its metrics (design §3), and stays so. But Cancel left
    // it in the draft without a word, and a component with no assert reads NOT EVALUATED on the
    // next run — which outranks WARN and PASS in the server verdict (V1: E27 read WARN, the file
    // with billing-db NOT_EVALUATED).
    await openServer(page);
    await page.click('#serverAddComponent');
    await page.locator('#serverDialog [data-wir-dlg="id"]').fill('billing-db');
    await page.click('#serverAddComponentBtn');
    await expect(page.locator('#serverDialog .wir-picker')).toHaveCount(1);
    await page.locator('#serverDialog .wir-slideover-foot button', { hasText: 'Cancel' }).click();

    const toast = page.locator('#wir-undo-toast.visible');
    await expect(toast).toContainText('Component "billing-db" has no server assert yet — saved like this it '
      + 'reads NOT EVALUATED on the next run, and so does the server verdict unless another component FAILs');
    // The toast is read out, not focused: focus goes back to the new card's "+ metric" (SF29).
    await expect(page.locator('.wir-component-card[data-component="billing-db"] .wir-component-add')).toBeFocused();
    await page.click('#wirUndoBtn');
    expect(Object.keys((await block(page)).components)).toEqual(['orders-db', 'events']);
    expect(await page.evaluate(() => (window as any).WIR.document.count())).toBe(0);
    await expect(page.locator('.wir-component-card[data-component="billing-db"]')).toHaveCount(0);
    await expect(page.locator('#serverAddComponent')).toBeFocused();

    // A custom component's add goes to the editor; leaving it without a Save says the same.
    await page.click('#serverAddComponent');
    await page.locator('#serverDialog .wir-type-card', { hasText: 'Custom' }).click();
    await page.locator('#serverDialog [data-wir-dlg="id"]').fill('checkout-app');
    await page.click('#serverAddComponentBtn');
    await expect(page.locator('#serverEditor .wir-slideover')).toHaveCount(1);
    await page.keyboard.press('Escape');
    await expect(toast).toContainText('Component "checkout-app" has no server assert yet');

    // ...and an add that ends in an assert offers nothing.
    await page.click('#serverAddComponent');
    await page.locator('#serverDialog [data-wir-dlg="id"]').fill('billing-db');
    await page.click('#serverAddComponentBtn');
    await page.check('[data-wir-dlg="pick:deadlocks"]');
    await page.click('#serverPickNext');
    await page.click('#serverAddAssertsBtn');
    // No toast at all (F5): checkout-app's was still up here, and its Undo would have put the
    // document back to before billing-db — taking billing-db and its assert with it.
    await expect(page.locator('#wir-undo-toast.visible')).toHaveCount(0);
    expect((await block(page)).asserts.filter((a: any) => a.component === 'billing-db').length).toBe(1);
  });

  test('[U] F5: the empty-component toast is withdrawn by an edit on ANOTHER component too', async ({ page }) => {
    // SF44's guard took the toast back only for an assert added on that same component; any other
    // edit made under it was written over by its Undo.
    await openServer(page);
    await page.click('#serverAddComponent');
    await page.locator('#serverDialog [data-wir-dlg="id"]').fill('billing-db');
    await page.click('#serverAddComponentBtn');
    await page.locator('#serverDialog .wir-slideover-foot button', { hasText: 'Cancel' }).click();
    const toast = page.locator('#wir-undo-toast');
    await expect(toast).toContainText('Component "billing-db" has no server assert yet');
    await expect(toast).toHaveClass(/visible/);
    await page.locator('button[onclick*="WIR.serverKpis.toggleEnabled"]').first().click();
    await expect(toast).not.toHaveClass(/visible/);
    await page.evaluate(() => (document.getElementById('wirUndoBtn') as HTMLButtonElement).click());
    const b = await block(page);
    expect(Object.keys(b.components)).toEqual(['orders-db', 'events', 'billing-db']);
    expect(b.asserts.filter((a: any) => a.enabled === false).length).toBe(1);
  });

  test('[U] F5: an edit made while the picker was open means Cancel offers no Undo that would revert it', async ({ page }) => {
    // The empty-component Undo puts back the document captured when + Add component wrote, which is
    // older than the toast. The picker is modal, so this edit is made programmatically: by hand the
    // window cannot be reached today, and the guard keeps it that way.
    await openServer(page);
    await page.click('#serverAddComponent');
    await page.locator('#serverDialog [data-wir-dlg="id"]').fill('billing-db');
    await page.click('#serverAddComponentBtn');
    await expect(page.locator('#serverDialog .wir-picker')).toHaveCount(1);
    await page.evaluate(() => {
      const W = (window as any).WIR.serverKpis;
      W.toggleEnabled(W._state.declaredKpis[0].key);
    });
    await page.evaluate(() => (window as any).WIR.serverKpis.closeDialog());
    await expect(page.locator('#wir-undo-toast.visible')).toHaveCount(0);
    await page.evaluate(() => { const b = document.getElementById('wirUndoBtn'); if (b) (b as HTMLButtonElement).click(); });
    const b = await block(page);
    expect(Object.keys(b.components)).toEqual(['orders-db', 'events', 'billing-db']);
    expect(b.asserts.filter((a: any) => a.enabled === false).length).toBe(1);
  });

  test('[U] SF44: the empty-component toast goes once its first assert is added from the "+ metric" Cancel left focus on', async ({ page }) => {
    // Cancel put focus on the new card's "+ metric" (SF29) beside a toast that stays up 8 s and
    // more while hovered. Adding an assert from there left it saying "has no server assert yet",
    // and its Undo then took the component AND the assert just added, with nothing to undo that.
    await openServer(page);
    await page.click('#serverAddComponent');
    await page.locator('#serverDialog [data-wir-dlg="id"]').fill('billing-db');
    await page.click('#serverAddComponentBtn');
    await page.locator('#serverDialog .wir-slideover-foot button', { hasText: 'Cancel' }).click();
    const toast = page.locator('#wir-undo-toast');
    await expect(toast).toHaveClass(/visible/);
    await expect(toast).toContainText('Component "billing-db" has no server assert yet');
    const add = page.locator('.wir-component-card[data-component="billing-db"] .wir-component-add');
    await expect(add).toBeFocused();
    await page.keyboard.press('Enter');
    await page.check('[data-wir-dlg="pick:deadlocks"]');
    await page.click('#serverPickNext');
    await page.click('#serverAddAssertsBtn');
    await expect(toast, 'the toast still offered to undo a component that now has an assert').not.toHaveClass(/visible/);
    // ...and its Undo, were it reached anyway, takes nothing.
    await page.evaluate(() => (document.getElementById('wirUndoBtn') as HTMLButtonElement).click());
    const b = await block(page);
    expect(Object.keys(b.components)).toEqual(['orders-db', 'events', 'billing-db']);
    expect(b.asserts.filter((a: any) => a.component === 'billing-db').length).toBe(1);
  });

  test('[U] SF27: "__proto__" is an id like any other — written, drawn, and never dropped', async ({ page }) => {
    await openServer(page);
    await page.click('#serverAddComponent');
    const dialog = page.locator('#serverDialog .wir-slideover');
    // Java takes "__proto__" as any other id. `components[id] = entry` set the object's PROTOTYPE:
    // Add wrote nothing, drew no card and opened the editor on orders-db. It was then refused by
    // name, as if the page could not hold it — but a file declaring it renders, saves and reloads
    // (SF27 review), so the Add writes the key as JSON.parse would, and it is added.
    await dialog.locator('[data-wir-dlg="id"]').fill('__proto__');
    await dialog.locator('.wir-type-card', { hasText: 'Custom' }).click();
    await expect(dialog.locator('.wir-problem')).toHaveCount(0);
    await page.click('#serverAddComponentBtn');
    const keys = await page.evaluate(() =>
      Object.keys(JSON.parse((window as any).WIR.document.text()).serverMetrics.components));
    expect(keys).toEqual(['orders-db', 'events', '__proto__']);
    await expect(page.locator('.wir-component-card[data-component="__proto__"]')).toHaveCount(1);
    // A custom component opens the editor on itself, never on another one.
    await expect(page.locator('#serverEditor [data-field="component"]')).toHaveValue('__proto__');
  });

  test('[U] a custom component has no catalogue, so + metric goes to the custom-query editor', async ({ page }) => {
    await openServer(page);
    await page.click('#serverAddComponent');
    const dialog = page.locator('#serverDialog .wir-slideover');
    await dialog.locator('.wir-type-card', { hasText: 'Custom' }).click();
    await dialog.locator('[data-wir-dlg="id"]').fill('checkout-app');
    await page.click('#serverAddComponentBtn');
    expect((await block(page)).components['checkout-app']).toEqual({ type: 'custom' });
    await expect(page.locator('#serverEditor .wir-slideover')).toBeVisible();
    await expect(page.locator('#serverEditor select[data-field="component"]')).toHaveValue('checkout-app');
    await expect(page.locator('#serverEditor [data-field="query"]')).toBeVisible();
  });

  test('[U] the empty tab offers the type cards inline', async ({ page }) => {
    await openServer(page);
    await page.evaluate(() => {
      const W = (window as any).WIR;
      const doc = JSON.parse(JSON.stringify((window as any).globalChartData.annotationsDocument));
      doc.serverMetrics = { components: {}, asserts: [] };
      W.document.adopt(doc);
      W.serverKpis.reread();
    });
    const first = page.locator('#serverBoard .wir-first-component');
    await expect(first).toContainText('+ Add your first component');
    await first.locator('.wir-type-card', { hasText: 'Kafka' }).click();
    await expect(page.locator('#serverDialog .wir-type-card.wir-on')).toContainText('Kafka');
  });
});

test.describe('The metric picker', () => {
  test('[U] lists the type\'s catalogue by category, marks what is asserted, and filters', async ({ page }) => {
    await openServer(page);
    await page.click('.wir-component-card[data-component="orders-db"] .wir-component-add');
    const list = page.locator('#serverPickList');
    await expect(list.locator('.wir-pick-row')).toHaveCount(16);
    // The one already asserted cannot be picked twice.
    await expect(page.locator('[data-wir-dlg="pick:connections.used.pct"]')).toBeDisabled();
    await expect(list.locator('.wir-pick-row[data-metric="connections.used.pct"]')).toContainText('already asserted');

    // "Has data in this run" leaves exactly what D1 collected for orders-db, in catalogue order
    // (database.size.growth, the catalogue's one slope, is e27-metrics.csv's since Q9).
    await page.check('[data-wir-dlg="hasDataOnly"]');
    expect(await list.locator('.wir-pick-row').evaluateAll((rows) => rows.map((r) => r.getAttribute('data-metric'))))
      .toEqual(['up', 'connections.used.pct', 'transaction.longest.seconds', 'deadlocks', 'cache.hit.pct', 'database.size.growth']);
    await page.uncheck('[data-wir-dlg="hasDataOnly"]');

    // The search box keeps its focus while the list redraws.
    const search = page.locator('[data-wir-dlg="search"]');
    await search.fill('cache');
    await expect(list.locator('.wir-pick-row')).toHaveCount(1);
    await expect(search).toBeFocused();
    await search.fill('');

    // A category narrows (Locks: sessions waiting on a lock, and deadlocks); the detail pane
    // follows the focused metric and this run's numbers.
    await page.click('.wir-pick-cat:has-text("Locks")');
    await expect(list.locator('.wir-pick-row')).toHaveCount(2);
    await page.click('.wir-pick-name:has-text("Deadlocks")');
    await expect(page.locator('.wir-pick-detail h4')).toHaveText('Deadlocks per minute (peak)');
    await expect(page.locator('.wir-pick-detail')).toContainText('fail > 0');

    // Nothing picked, no next step.
    await expect(page.locator('#serverPickNext')).toBeDisabled();
  });

  test('[U] b5: aws-ec2 lists seven KPIs, and each metric-math one says what to compute and the name to export it as', async ({ page }) => {
    // EBS IOPS, EBS throughput, EBS limit exceeded and the ENA allowances are each two to five
    // CloudWatch metrics with no single one to name (cloudwatch: null). The picker's description is
    // where a reader learns the expression, and the name the file's metric column must carry.
    await openServer(page);
    await page.click('#serverAddComponent');
    const dialog = page.locator('#serverDialog .wir-slideover');
    const card = dialog.locator('.wir-type-card', { hasText: 'AWS EC2' });
    await expect(card).toContainText('7 metrics');
    await dialog.locator('[data-wir-dlg="id"]').fill('app-1-aws');
    await card.click();
    await page.click('#serverAddComponentBtn');
    await expect(page.locator('#serverDialog .wir-slideover-head')).toContainText('Pick metrics — app-1-aws · AWS EC2');

    const list = page.locator('#serverPickList');
    expect(await list.locator('.wir-pick-row').evaluateAll((rows) => rows.map((r) => r.getAttribute('data-metric'))))
      .toEqual(['cpu.credit.balance', 'ebs.burst.balance.pct', 'ebs.limit.exceeded', 'ebs.iops', 'ebs.throughput.rate',
        'status.check.failed', 'network.allowance.exceeded']);
    // Recommended: five. IOPS and throughput have no band to recommend — compare them with what is provisioned.
    expect(await list.locator('.wir-pick-row:has(.wir-star)').evaluateAll((rows) => rows.map((r) => r.getAttribute('data-metric'))))
      .toEqual(['cpu.credit.balance', 'ebs.burst.balance.pct', 'ebs.limit.exceeded', 'status.check.failed',
        'network.allowance.exceeded']);

    const detail = page.locator('.wir-pick-detail');
    const how: Record<string, { label: string, says: string[], suggested: string }> = {
      // AWS/EC2's EBS totals are 5-minute data under basic monitoring: at a 60 s export PERIOD(m1)
      // would divide five minutes' sum by 60, so that route says the period it needs.
      // The AWS/EC2 route is for Nitro instances that are not bare metal (NIT-6).
      'ebs.iops': { label: 'EBS IOPS (ops/s)', suggested: 'none until this metric has data', says: [
        'No single CloudWatch metric: add AWS/EBS VolumeReadOps and VolumeWriteOps',
        "AWS/EC2 EBSReadOps and EBSWriteOps for the instance's total on a Nitro instance that is not bare metal,"
          + ' at a period of at least 300 s under basic monitoring',
        '(m1 + m2) / PERIOD(m1)'] },
      'ebs.throughput.rate': { label: 'EBS throughput (B/s)', suggested: 'none until this metric has data', says: [
        'No single CloudWatch metric: add AWS/EBS VolumeReadBytes and VolumeWriteBytes',
        "AWS/EC2 EBSReadBytes and EBSWriteBytes for the instance's total on a Nitro instance that is not bare metal,"
          + ' at a period of at least 300 s under basic monitoring',
        '(m1 + m2) / PERIOD(m1)'] },
      // AWS/EBS publishes no volume check for a magnetic or a Multi-Attach enabled volume (NIT-6).
      'ebs.limit.exceeded': { label: 'EBS limit exceeded (0/1)', suggested: 'warn > 0', says: [
        'Nitro instances only (the instance checks not on bare metal)',
        'AWS/EBS publishes neither volume check for a magnetic (standard) or Multi-Attach enabled volume',
        'InstanceEBSIOPSExceededCheck and InstanceEBSThroughputExceededCheck',
        'VolumeIOPSExceededCheck and VolumeThroughputExceededCheck',
        'for every volume attached to the instance (one series per VolumeId)', 'MAX(METRICS())'] },
      // The agent's hostname is a dimension too, and a driver reset's negative rate is clamped at 0
      // before the sum, where it would cancel a real exceed (NIT-6, REV-16).
      'network.allowance.exceeded': { label: 'ENA allowance exceeded (packets/min)', suggested: 'warn > 0', says: [
        "on Linux, list the five in the CloudWatch agent's ethtool metrics_include", 'ethtool_bw_in_allowance_exceeded',
        'ethtool_linklocal_allowance_exceeded',
        "one series per network interface: dimensions interface and driver, plus the agent's hostname (unless its"
          + ' omit_hostname is true) or what its append_dimensions adds instead',
        'for every interface of the instance', "each series' rate clamped at 0, since a driver reset makes it negative",
        '60 * (SUM(RATE(METRICS())) + SUM(ABS(RATE(METRICS())))) / 2'] },
    };
    for (const [metric, h] of Object.entries(how)) {
      await list.locator(`.wir-pick-row[data-metric="${metric}"] .wir-pick-name`).click();
      await expect(detail.locator('h4')).toHaveText(h.label);
      const description = detail.locator('p').first();
      for (const said of h.says) await expect(description).toContainText(said);
      // Last, the name the computed series goes under: what --csv reads, with or without --from.
      await expect(description).toHaveText(new RegExp(`, and export the result as ${metric.replace(/\./g, '\\.')}\\.$`));
      await expect(detail.locator('.wir-pick-facts')).toContainText(h.suggested);
    }
  });

  test('[U] thresholds: prefilled, judged live, NO DATA asks, and Add writes the metric — not its query', async ({ page }) => {
    await openServer(page);
    await page.click('.wir-component-card[data-component="orders-db"] .wir-component-add');
    for (const m of ['deadlocks', 'cache.hit.pct', 'replication.lag.seconds']) {
      await page.check(`[data-wir-dlg="pick:${m}"]`);
    }
    await expect(page.locator('.wir-pick-chip')).toHaveCount(3);
    // RB10's sibling: a picked metric's × names the metric it removes and is a 24×24 target (WCAG
    // 2.2 2.5.8). It was named "Remove" on every chip, and measured about 10×19, as the band × did.
    const chipRemove = page.locator('.wir-pick-chip[data-metric="deadlocks"] button');
    await expect(chipRemove).toHaveAccessibleName('Remove Deadlocks per minute (peak)');
    const chipBox = (await chipRemove.boundingBox())!;
    expect(chipBox.width, 'the chip × is narrower than 24 px').toBeGreaterThanOrEqual(24);
    expect(chipBox.height, 'the chip × is shorter than 24 px').toBeGreaterThanOrEqual(24);
    await page.click('#serverPickNext');

    const row = (m: string) => page.locator(`.wir-thresholds tr[data-metric="${m}"]`);
    // deadlocks: the catalogue's invariant (fail > 0), and this run had one — FAIL, live.
    await expect(row('deadlocks').locator('[data-wir-dlg="row:deadlocks:failValue"]')).toHaveValue('0');
    await expect(row('deadlocks').locator('.wir-chip')).toHaveText('FAIL');
    // cache.hit.pct gets worse LOWER: its bands point down, from this run's low.
    await expect(row('cache.hit.pct').locator('[data-wir-dlg="row:cache.hit.pct:failOp"]')).toHaveValue('lt');
    await expect(row('cache.hit.pct').locator('.wir-chip')).toHaveText('PASS');
    // replication lag was not collected: NO DATA, and the row asks for a band.
    await expect(row('replication.lag.seconds')).toContainText('NO DATA');
    await expect(row('replication.lag.seconds')).toContainText('needs a band');
    await expect(page.locator('#serverAddAssertsBtn')).toBeDisabled();

    // A prefill rule applies to every row; each stays editable. ("Run + 20%" until SF11: headroom
    // is the word for both directions, and the band of a lower metric rightly sits below the run.)
    await expect(page.locator('.wir-thresholds-head .wir-filter-chip'))
      .toHaveText(['Suggested', '20% headroom', '50% headroom']);
    await page.click('.wir-thresholds-head .wir-filter-chip:has-text("20% headroom")');
    await expect(row('cache.hit.pct').locator('[data-wir-dlg="row:cache.hit.pct:failValue"]')).not.toHaveValue('');
    await page.click('.wir-thresholds-head .wir-filter-chip:has-text("Suggested")');

    await row('replication.lag.seconds').locator('[data-wir-dlg="row:replication.lag.seconds:failValue"]').fill('30');
    await expect(page.locator('#serverAddAssertsBtn')).toBeEnabled();
    await expect(page.locator('#serverAddAssertsBtn')).toHaveText('Add 3 server asserts');
    await page.click('#serverAddAssertsBtn');

    // Three rows on orders-db, and the file names each metric and its bands — the catalogue
    // supplies the query, unit and reducer, so none is written.
    await expect(page.locator('.wir-component-card[data-component="orders-db"] tbody tr.wir-kpi-row')).toHaveCount(4);
    const written = (await block(page)).asserts.filter((k: any) => k.component === 'orders-db' && k.metric !== 'connections.used.pct');
    expect(written).toEqual([
      { component: 'orders-db', metric: 'deadlocks', fail: { gt: 0 } },
      { component: 'orders-db', metric: 'cache.hit.pct', warn: { lt: written[1].warn.lt }, fail: { lt: written[1].fail.lt } },
      { component: 'orders-db', metric: 'replication.lag.seconds', fail: { gt: 30 } },
    ]);
    expect(written[1].fail.lt).toBeLessThan(written[1].warn.lt);
  });

  test('[U] Custom query… is the escape hatch, on the same component', async ({ page }) => {
    await openServer(page);
    await page.click('.wir-component-card[data-component="events"] .wir-component-add');
    await page.click('#serverDialog .wir-link:has-text("Custom query…")');
    await expect(page.locator('#serverDialog .wir-slideover')).toHaveCount(0);
    await expect(page.locator('#serverEditor select[data-field="component"]')).toHaveValue('events');
    await expect(page.locator('#serverEditor select[data-field="metric"]')).toHaveValue('');
  });

  test('[U] SF2: up folds to its worst instance, so the database that went down FAILs its safe default', async ({ page }) => {
    // E27's pg-1 reports up = 0 once (Q9) while pg-2 stays 1. postgres.json declares across min on
    // up and cache.hit.pct; folded with max, the live twin hid the dead database and the catalogue's
    // own fail < 1 PASSed. The mirror reads the catalogue's across as Java does, so this row and
    // the next run agree.
    await openServer(page);
    await page.click('.wir-component-card[data-component="orders-db"] .wir-component-add');
    await page.click('.wir-pick-name:has-text("Exporter can reach the database")');
    await expect(page.locator('.wir-pick-detail')).toContainText('low 0 over 2 instances');
    await page.check('[data-wir-dlg="pick:up"]');
    await page.click('#serverPickNext');
    const row = page.locator('.wir-thresholds tr[data-metric="up"]');
    await expect(row.locator('[data-wir-dlg="row:up:failOp"]')).toHaveValue('lt');
    await expect(row.locator('[data-wir-dlg="row:up:failValue"]')).toHaveValue('1');
    await expect(row.locator('.wir-chip')).toHaveText('FAIL');

    await page.click('#serverAddAssertsBtn');
    const boardRow = page.locator('.wir-component-card[data-component="orders-db"] tr.wir-kpi-row',
      { hasText: 'orders-db-up' });
    await expect(boardRow.locator('.wir-chip')).toHaveText('FAIL');
    // The file names the metric and its band: the across is the catalogue's, so it is not written.
    const written = (await block(page)).asserts.find((k: any) => k.metric === 'up');
    expect(written).toEqual({ component: 'orders-db', metric: 'up', fail: { lt: 1 } });
  });
});

test.describe('Batch 2: the numbers a band is taken from are the ones Java compares', () => {
  test('[U] SF8/SF14: a capped catalogue series shows and prefills the build\'s number, over the collector\'s count, and is not re-judged', async ({ page }) => {
    // The page's copy of transaction.longest.seconds holds one instance peaking at 2.9 s; the
    // build judged 21, one of them at 120 s (the K27 variant, recreated in the page's data: capped,
    // with Java's number shipped in judgedActuals). The picker read the thinned copy — "peak 3 over
    // 1 instance", fail > 4.3 judged PASS — a band that FAILs the run it was suggested from.
    await patchSeriesBeforeLoad(page, 'orders-db', 'transaction.longest.seconds',
      { instancesCapped: true, instanceCount: 21, judgedActuals: { 'transaction.longest.seconds': 120 }, judgedMax: 120 });
    await openServer(page);
    await page.click('.wir-component-card[data-component="orders-db"] .wir-component-add');
    await expect(page.locator('.wir-pick-row[data-metric="transaction.longest.seconds"] .wir-pick-peak'))
      .toHaveText('peak 120 s');
    await page.click('.wir-pick-name:has-text("Longest open transaction (s)")');
    const facts = page.locator('.wir-pick-detail');
    await expect(facts).toContainText('peak 120 s over 21 instances (1 drawn)');
    // In the board's spelling and the unit of the line above (SF17): it read "fail > 180 · warn > 150".
    await expect(facts).toContainText('fail > 180 s · warn > 150 s');

    await page.check('[data-wir-dlg="pick:transaction.longest.seconds"]');
    await page.click('#serverPickNext');
    const row = thresholdsRow(page, 'transaction.longest.seconds');
    await expect(row.locator('td.num')).toHaveText('peak 120 s');
    await expect(row.locator('[data-wir-dlg="row:transaction.longest.seconds:failValue"]')).toHaveValue('180');
    await expect(row.locator('[data-wir-dlg="row:transaction.longest.seconds:warnValue"]')).toHaveValue('150');
    // No live verdict from thinned points (CLAUDE.md: a capped series keeps Java's verdict, and
    // there is none for an assert the build never saw) — what the board will say after Add.
    await expect(row.locator('.wir-chip')).toHaveText('NOT RE-JUDGED');
    await expect(row.locator('.wir-chip')).toHaveAttribute('title', /^Not re-judged — this series reached the page capped/);

    await page.click('#serverAddAssertsBtn');
    const written = (await block(page)).asserts.find((k: any) => k.metric === 'transaction.longest.seconds');
    expect(written).toEqual({ component: 'orders-db', metric: 'transaction.longest.seconds',
      warn: { gt: 150 }, fail: { gt: 180 } });
    const board = await page.evaluate(() => (window as any).WIR.serverKpis._board()
      .find((r: any) => r.id === 'orders-db-transaction-longest-seconds'));
    expect(board.display).toBe('NOT RE-JUDGED');
  });

  test('[U] SF8: Generate seeds a capped catalogue series from the build\'s number', async ({ page }) => {
    // judgedMax — the level's maximum, a custom seed's number — is set apart from the metric's own
    // number, so a catalogue seed that read it again would be seeded from 999 and fail here.
    await patchSeriesBeforeLoad(page, 'orders-db', 'transaction.longest.seconds',
      { instancesCapped: true, instanceCount: 21, judgedActuals: { 'transaction.longest.seconds': 120 }, judgedMax: 999 });
    await openServer(page);
    const seed = await page.evaluate(() => {
      const k = (window as any).WIR.serverKpis._seedable().find((s: any) => s.metric === 'transaction.longest.seconds');
      return k ? { warn: k.warn, fail: k.fail } : null;
    });
    expect(seed).toEqual({ warn: { op: 'gt', value: 150 }, fail: { op: 'gt', value: 180 } });
  });

  test('[U] SF8: a capped seed is held against the build\'s own number — up at 0 is not offered lt 1', async ({ page }) => {
    // Capped, up cannot be re-judged here, so Generate holds its seed against the build's number
    // for it. That number is 0 (min across min); its level's maximum, judgedMax, is 1. Held
    // against nothing, or against judgedMax, the safe default lt 1 would be offered — and FAIL
    // the very run it came from.
    await patchSeriesBeforeLoad(page, 'orders-db', 'up',
      { instancesCapped: true, instanceCount: 21, judgedActuals: { up: 0 }, judgedMax: 1 });
    await openServer(page);
    expect(await page.evaluate(() => (window as any).WIR.serverKpis._seedable()
      .some((s: any) => s.metric === 'up'))).toBe(false);
  });

  test('[U] SF13: the review says what a capped seed was held against, under its NOT RE-JUDGED chip', async ({ page }) => {
    // The chip is the board's (NOT RE-JUDGED: thinned points are not re-judged), and the intro says
    // every seed passes its run by construction. The seed WAS judged before it was offered — against
    // the build's number — so the row says so, against the band as now written.
    await patchSeriesBeforeLoad(page, 'orders-db', 'transaction.longest.seconds',
      { instancesCapped: true, instanceCount: 21, judgedActuals: { 'transaction.longest.seconds': 120 }, judgedMax: 999 });
    await openServer(page);
    await page.click('.wir-server-actions .wir-btn:has-text("Generate from this run")');
    const row = page.locator('#serverDialog .wir-seed-review tbody tr', { hasText: 'Longest open transaction' });
    await expect(row.locator('[data-wir-dlg^="seedfail:"]')).toHaveValue('180');
    await expect(row.locator('.wir-chip')).toHaveText('NOT RE-JUDGED');
    await expect(row.locator('.wir-held')).toHaveText(
      'Held against the build’s number for this run (peak 120 s): it passes. Not re-judged here.');
    // Under the build's number, the edited band says it would fail — and the chip stays the board's.
    await row.locator('[data-wir-dlg^="seedfail:"]').fill('100');
    await expect(row.locator('.wir-held')).toHaveText(
      'Held against the build’s number for this run (peak 120 s): this fail band FAILs it. Not re-judged here.');
    await expect(row.locator('.wir-chip')).toHaveText('NOT RE-JUDGED');
    // An uncapped seed is judged live and needs no such line.
    await expect(page.locator('#serverDialog .wir-seed-review tbody tr', { hasText: 'Cache hit ratio' })
      .locator('.wir-held')).toHaveCount(0);
  });

  test('[U] SF8: two catalogue metrics sharing one capped series each show their own build number', async ({ page }) => {
    // Kafka's consumer.lag.total (max) and consumer.lag.final (last) are one query, so the collector
    // writes one series, recorded as one of them. Recreated on E27's events: lag climbs to 10,000
    // on an instance the page was not sent and drains to 100. One number under the recorded name
    // showed total as "peak 100" and banded it fail > 150, which the build FAILs at 10,000.
    const LAG = 'sum by (consumergroup, topic) (kafka_consumergroup_lag)';
    await patchSeriesBeforeLoad(page, 'events', 'messages.in.rate', {
      metric: 'consumer.lag.final', query: LAG, resolvedQuery: LAG, instancesCapped: true, instanceCount: 21,
      judgedActuals: { 'consumer.lag.total': 10000, 'consumer.lag.final': 100 }, judgedMax: 10000,
    }, 40);
    await openServer(page);
    await page.click('.wir-component-card[data-component="events"] .wir-component-add');
    await expect(page.locator('.wir-pick-row[data-metric="consumer.lag.total"] .wir-pick-peak'))
      .toHaveText(/^peak 10,?000 msgs$/);
    await expect(page.locator('.wir-pick-row[data-metric="consumer.lag.final"] .wir-pick-peak'))
      .toHaveText('last 100 msgs');
    await page.click('.wir-pick-name:has-text("Consumer lag, all partitions")');
    await expect(page.locator('.wir-pick-detail')).toContainText('fail > 15,000 msgs · warn > 12,500 msgs');
  });

  test('[U] SF8: a capped series judged over another window than the build\'s proposes nothing from it', async ({ page }) => {
    // The build excluded a ramp-up (E27's file declares no collection block, so it does); a file
    // opened on the page that keeps it judges another window, and the build's 120 answers for
    // the old one only. Shown and seeded anyway, its bands FAILed the run under the file they were
    // saved into. runOf's cache is keyed on the window, so going back brings the number back.
    await patchChartDataBeforeLoad(page, {
      rampUpAfterMs: 10000,
      series: [{ component: 'orders-db', metric: 'transaction.longest.seconds',
        patch: { instancesCapped: true, instanceCount: 21, judgedActuals: { 'transaction.longest.seconds': 120 } } }],
    });
    await openServer(page);
    const peak = page.locator('.wir-pick-row[data-metric="transaction.longest.seconds"] .wir-pick-peak');
    const seeded = () => page.evaluate(() => (window as any).WIR.serverKpis._seedable()
      .some((s: any) => s.metric === 'transaction.longest.seconds'));
    await page.click('.wir-component-card[data-component="orders-db"] .wir-component-add');
    await expect(peak).toHaveText('peak 120 s');
    expect(await seeded()).toBe(true);

    await adoptServerBlock(page, { collection: { excludeRampUp: false } });
    await page.click('.wir-component-card[data-component="orders-db"] .wir-component-add');
    await expect(peak).toHaveText('—');
    await page.click('.wir-pick-name:has-text("Longest open transaction (s)")');
    const facts = page.locator('.wir-pick-detail');
    await expect(facts).toContainText('no number from the build for this window over 21 instances (1 drawn)');
    await expect(facts).toContainText('none — this series reached the page capped, and the build’s number for it '
      + 'was taken over another window');
    expect(await seeded()).toBe(false);

    await adoptServerBlock(page, { collection: { excludeRampUp: true } });
    await page.click('.wir-component-card[data-component="orders-db"] .wir-component-add');
    await expect(peak).toHaveText('peak 120 s');
  });

  test('[U] runOf is keyed on the component\'s match: a redeclared component reads what was collected for it', async ({ page }) => {
    // The match decides the query, and so the series. The cache is filled at the first render, and
    // keyed on component and metric alone it went on showing the old query's run after a file
    // re-pointed orders-db at an instance nothing was collected for.
    await openServer(page);
    const up = page.locator('.wir-pick-row[data-metric="up"] .wir-pick-peak');
    await page.click('.wir-component-card[data-component="orders-db"] .wir-component-add');
    await expect(up).toHaveText('low 0');
    await adoptServerBlock(page, { components: { 'orders-db': { match: 'instance="elsewhere:5432"' } } });
    await page.click('.wir-component-card[data-component="orders-db"] .wir-component-add');
    await expect(up).toHaveText('no data');
  });

  test('[U] SF8: a capped catalogue series the build shipped no number for proposes nothing from the run', async ({ page }) => {
    await patchSeriesBeforeLoad(page, 'orders-db', 'transaction.longest.seconds',
      { instancesCapped: true, instanceCount: 21 });
    await openServer(page);
    // The pane says why there is no band, in the words of the line above it — not "until this
    // metric has data", beside a line saying what the data was.
    await page.click('.wir-component-card[data-component="orders-db"] .wir-component-add');
    await page.click('.wir-pick-name:has-text("Longest open transaction (s)")');
    const facts = page.locator('.wir-pick-detail');
    await expect(facts).toContainText('no number from the build over 21 instances (1 drawn)');
    await expect(facts).toContainText(
      'none — this series reached the page capped, and the build carries no number for this metric');
    await expect(facts).not.toContainText('until this metric has data');
    await page.evaluate(() => (window as any).WIR.serverKpis.closeDialog());
    await pickAndSet(page, 'orders-db', ['transaction.longest.seconds']);
    const row = thresholdsRow(page, 'transaction.longest.seconds');
    await expect(row.locator('td.num')).toHaveText('—');
    await expect(row.locator('[data-wir-dlg="row:transaction.longest.seconds:failValue"]')).toHaveValue('');
    await expect(row).toContainText('needs a band');
    // ...and Generate does not seed it ("or not at all").
    expect(await page.evaluate(() => (window as any).WIR.serverKpis._seedable()
      .some((s: any) => s.metric === 'transaction.longest.seconds'))).toBe(false);
  });

  test('[U] SF9: a slope metric is shown, prefilled and seeded per minute, never from its level', async ({ page }) => {
    // E27's pg-1 grows 1,000,000 B/s from 5 GB: 60,000,000 B/min. The level seeded
    // fail > 7,572,000,000 B, a band that could never fire, under "every one passes it by construction".
    await openServer(page);
    await page.click('.wir-component-card[data-component="orders-db"] .wir-component-add');
    await expect(page.locator('.wir-pick-row[data-metric="database.size.growth"] .wir-pick-peak'))
      .toHaveText(/^slope 60,?000,?000 B\/min$/);
    await page.click('.wir-pick-name:has-text("Database size growth (bytes/min)")');
    await expect(page.locator('.wir-pick-detail')).toContainText(/slope 60,?000,?000 B\/min over 2 instances/);
    await expect(page.locator('.wir-pick-detail')).toContainText('fail > 90,000,000 B/min · warn > 75,000,000 B/min');

    await page.check('[data-wir-dlg="pick:database.size.growth"]');
    await page.click('#serverPickNext');
    const row = thresholdsRow(page, 'database.size.growth');
    await expect(row.locator('[data-wir-dlg="row:database.size.growth:failValue"]')).toHaveValue('90000000');
    await expect(row.locator('[data-wir-dlg="row:database.size.growth:warnValue"]')).toHaveValue('75000000');
    await expect(row.locator('.wir-chip')).toHaveText('PASS');
    await page.click('#serverAddAssertsBtn');
    expect((await block(page)).asserts.find((k: any) => k.metric === 'database.size.growth')).toEqual({
      component: 'orders-db', metric: 'database.size.growth', warn: { gt: 75000000 }, fail: { gt: 90000000 } });

    // Generate seeds the same bands, and the seed passes the run because it is in the right space.
    const seed = await page.evaluate(() => {
      const W = (window as any).WIR.serverKpis;
      W.remove(W._keyOf('orders-db-database-size-growth'));
      const k = W._seedable().find((s: any) => s.metric === 'database.size.growth');
      return k ? { warn: k.warn, fail: k.fail } : null;
    });
    expect(seed).toEqual({ warn: { op: 'gt', value: 75000000 }, fail: { op: 'gt', value: 90000000 } });
  });

  test('[U] SF10/SF11: up is judged on its low, and no headroom rule writes fail < 0', async ({ page }) => {
    // pg-1's up was 0 once. Half of 0 is 0, so "Run + 20%" and "Run + 50%" wrote fail < 0, which
    // never fires: Java warns "can only fire on a negative value". The catalogue's fail < 1 stands in.
    await openServer(page);
    await pickAndSet(page, 'orders-db', ['up', 'cache.hit.pct']);
    // The Actual column names what is compared: the LOW of a metric that gets worse lower, never
    // its best value under a "Peak" heading.
    await expect(page.locator('.wir-thresholds thead th').nth(2)).toHaveText('Actual');
    await expect(thresholdsRow(page, 'up').locator('td.num')).toHaveText('low 0');
    await expect(thresholdsRow(page, 'cache.hit.pct').locator('td.num')).toHaveText('low 97 %');
    for (const rule of ['Suggested', '20% headroom', '50% headroom']) {
      await page.click(`.wir-thresholds-head .wir-filter-chip:has-text("${rule}")`);
      const up = thresholdsRow(page, 'up');
      await expect(up.locator('[data-wir-dlg="row:up:failOp"]'), rule).toHaveValue('lt');
      await expect(up.locator('[data-wir-dlg="row:up:failValue"]'), rule).toHaveValue('1');
      await expect(up.locator('.wir-chip'), rule).toHaveText('FAIL');
      await expect(up.locator('.wir-editor-warning'), rule).toHaveCount(0);
    }
  });

  test('[U] the run\'s line folds instances the way the verdict does: up dips where pg-1 went down', async ({ page }) => {
    // The line was max across instances at each time, so pg-2's 1 hid pg-1's 0 — on the chart of
    // a metric whose verdict is that 0 (across min, SF2).
    await openServer(page);
    await page.click('.wir-component-card[data-component="orders-db"] .wir-component-add');
    await page.click('.wir-pick-name:has-text("Exporter can reach the database")');
    await expect.poll(() => page.evaluate(() => {
      const el = document.getElementById('serverPickChart');
      const chart = el && (window as any).echarts.getInstanceByDom(el);
      if (!chart) return null;
      return Math.min(...chart.getOption().series[0].data.map((p: any) => p[1]));
    })).toBe(0);
  });

  test('[U] SF10: brokers with every broker gone asks for a band — it never reads PASS from a prefill', async ({ page }) => {
    // events' brokers at 0 for the whole run. No safe default in kafka.json, so nothing stands in
    // for the lt 0 the run would give: the row asks, and Generate offers no seed.
    await patchSeriesBeforeLoad(page, 'events', 'brokers', {}, 0);
    await openServer(page);
    // The picker's pane says why it suggests nothing: the run's band could never fire. It said
    // "none until this metric has data" under "low 0 brokers over 1 instance".
    await page.click('.wir-component-card[data-component="events"] .wir-component-add');
    await page.click('.wir-pick-name:has-text("Brokers in the cluster")');
    const facts = page.locator('.wir-pick-detail');
    await expect(facts).toContainText('low 0 brokers over 1 instance');
    await expect(facts).toContainText('none from this run — fail < 0 brokers can only fire on a negative value, so set a band');
    await page.evaluate(() => (window as any).WIR.serverKpis.closeDialog());
    await pickAndSet(page, 'events', ['brokers']);
    const row = thresholdsRow(page, 'brokers');
    await expect(row.locator('td.num')).toHaveText('low 0 brokers');
    for (const rule of ['Suggested', '20% headroom', '50% headroom']) {
      await page.click(`.wir-thresholds-head .wir-filter-chip:has-text("${rule}")`);
      await expect(row.locator('[data-wir-dlg="row:brokers:failValue"]'), rule).toHaveValue('');
      await expect(row, rule).toContainText('needs a band');
      await expect(page.locator('#serverAddAssertsBtn'), rule).toBeDisabled();
    }
    expect(await page.evaluate(() => (window as any).WIR.serverKpis._seedable()
      .some((s: any) => s.metric === 'brokers'))).toBe(false);
  });

  test('[U] SF12: a band box holding only spaces is no band, never 0', async ({ page }) => {
    await openServer(page);
    await pickAndSet(page, 'orders-db', ['up', 'cache.hit.pct', 'deadlocks']);
    // up: a warn of spaces beside its fail < 1 wrote warn lt 0.
    await page.fill('[data-wir-dlg="row:up:warnValue"]', '   ');
    // cache.hit.pct: a fail of one space beside warn < 73 wrote fail lt 0 — PASS on every run.
    await page.fill('[data-wir-dlg="row:cache.hit.pct:failValue"]', ' ');
    // deadlocks: spaces in its only band is no band at all, and the row asks.
    await page.fill('[data-wir-dlg="row:deadlocks:failValue"]', '  ');
    await expect(thresholdsRow(page, 'deadlocks')).toContainText('needs a band');
    await expect(page.locator('#serverAddAssertsBtn')).toBeDisabled();
    await page.fill('[data-wir-dlg="row:deadlocks:failValue"]', '0');
    await page.click('#serverAddAssertsBtn');
    const written = (await block(page)).asserts.filter((k: any) => ['up', 'cache.hit.pct'].includes(k.metric));
    expect(written).toEqual([
      { component: 'orders-db', metric: 'up', fail: { lt: 1 } },
      { component: 'orders-db', metric: 'cache.hit.pct', warn: { lt: 73 } },
    ]);
  });

  test('[U] SF13: Set thresholds judges as the board does — no data reads NEEDS RE-COLLECTION, and the editor\'s warnings show without blocking', async ({ page }) => {
    await openServer(page);
    await pickAndSet(page, 'orders-db', ['replication.lag.seconds', 'cache.hit.pct']);
    // No data, given a band: INVALID with "did not declare this server assert" in the step, and
    // NEEDS RE-COLLECTION on the board after Add — two derivations of one assert.
    await page.fill('[data-wir-dlg="row:replication.lag.seconds:failValue"]', '30');
    const lag = thresholdsRow(page, 'replication.lag.seconds');
    await expect(lag.locator('.wir-chip')).toHaveText('NEEDS RE-COLLECTION');
    // The board's own reason sentence (rowReason), which for a metric picked a moment ago says
    // this collection has no data for it — never that something changed (U8e). Moved deliberately
    // from /nothing collected answers for it/, the sentence every stale row used to share.
    await expect(lag.locator('.wir-chip')).toHaveAttribute('title',
      /^This run’s collection has no data for .+ on orders-db — the exporter may not publish it\./);
    // A warn the fail band covers: what the editor says about the same assert, and Add stays on.
    await page.fill('[data-wir-dlg="row:cache.hit.pct:warnValue"]', '90');
    await page.fill('[data-wir-dlg="row:cache.hit.pct:failValue"]', '95');
    const cache = thresholdsRow(page, 'cache.hit.pct');
    await expect(cache.locator('.wir-editor-warning')).toHaveText([/^warn can never fire: fail < 95 % is tested first/]);
    await expect(cache.locator('.wir-chip')).toHaveText('PASS');
    await expect(page.locator('#serverAddAssertsBtn')).toBeEnabled();
    await page.click('#serverAddAssertsBtn');
    const board = await page.evaluate(() => (window as any).WIR.serverKpis._board()
      .find((r: any) => r.id === 'orders-db-replication-lag-seconds'));
    expect(board.display).toBe('NEEDS RE-COLLECTION');
  });

  test('[U] U8e: a picked metric this collection has no data for says so on the board, never "the source or query changed"', async ({ page }) => {
    await openServer(page);
    await pickAndSet(page, 'orders-db', ['replication.lag.seconds']);
    await page.fill('[data-wir-dlg="row:replication.lag.seconds:failValue"]', '5');
    await page.click('#serverAddAssertsBtn');
    // Nothing changed — the assert is new, and the collector asked for the whole catalogue (D1) and
    // recorded no answer for this one. The state's name on the board, the reason, and the remedy.
    const card = page.locator('.wir-component-card[data-component="orders-db"]');
    const reason = card.locator('.wir-reason-row', { hasText: 'has no data for' });
    await expect(reason).toHaveCount(1);
    await expect(reason).toContainText('on orders-db — the exporter may not publish it. Download the file '
      + 'and re-run wir-collect with this file to judge it; if it still has none, this server assert reads UNMATCHED.');
    await expect(page.locator('#serverBoard')).not.toContainText('source or query changed');
    await expect(page.locator('#serverBoard')).not.toContainText(' changed, so nothing collected');
  });
});

test.describe('SF5: the catalogue reaches every report', () => {
  test('[U] the empty tab on a run with no server block offers every catalogue type', async ({ page }) => {
    // E25 declares no serverMetrics block. GatePayloadBuilder used to return before it put the
    // catalogue, so this tab and + Add component offered only Custom.
    await loadClean(page, 'E25');
    await switchTab(page, 'server');
    await expect(page.locator('#serverBoard .wir-first-component .wir-type-card')).toHaveText(TYPE_CARDS);
    await page.click('#serverBoard .wir-first-component .wir-type-card:has-text("Postgres")');
    await expect(page.locator('#serverDialog .wir-type-card')).toHaveText(TYPE_CARDS);
  });

  test('[U] a file with components and no asserts yet is not "not one of custom", and + metric opens the picker', async ({ page }) => {
    await loadClean(page, 'E25');
    await switchTab(page, 'server');
    await page.evaluate(() => {
      const W = (window as any).WIR;
      const doc = JSON.parse(JSON.stringify((window as any).globalChartData.annotationsDocument));
      doc.serverMetrics = {
        components: { 'orders-db': { type: 'postgres', description: 'Orders DB' }, events: { type: 'kafka' } },
        asserts: [],
      };
      W.document.adopt(doc);
      W.serverKpis.reread();
    });
    const board = page.locator('#serverBoard');
    await expect(page.locator('.wir-component-card')).toHaveCount(2);
    await expect(board).not.toContainText('is not one of custom');
    await expect(page.locator('.wir-component-card[data-component="orders-db"] .wir-component-type'))
      .toHaveText('Postgres · Orders DB');
    await page.click('.wir-component-card[data-component="orders-db"] .wir-component-add');
    await expect(page.locator('#serverDialog .wir-slideover-head')).toContainText('Pick metrics — orders-db · Postgres');
    await expect(page.locator('#serverPickList .wir-pick-row')).toHaveCount(16);
  });

  test('[U] an untouched components-only page projects nothing; an added assert does', async ({ page }) => {
    // The page Java ships for E27's components with asserts [] and E27's payload (V2P): the
    // series, the catalogue and the document, and no judged key at all — the file declares no
    // server assert, so nothing was judged and no component was reported.
    await openServer(page);
    await page.evaluate(() => {
      const W = window as any;
      const d = JSON.parse(JSON.stringify(W.globalChartData));
      d.annotationsDocument.serverMetrics.asserts = [];
      ['serverDeclared', 'serverEvaluated', 'serverFindings', 'serverGates', 'serverKpis',
        'serverPayloadRefused'].forEach((k) => delete d[k]);
      W.globalChartData = d;
      W.WIR.serverKpis.init(d);
      W.WIR.document.start();
      W.WIR.serverKpis.rerender();
    });
    await expect(page.locator('.wir-component-card')).toHaveCount(2);
    await expect(page.locator('#serverUnsavedPill')).toHaveCount(0);
    // Every card read "→ NOT EVALUATED if saved", under the caption about edits, with no edit made.
    await expect(page.locator('.wir-would-be')).toHaveCount(0);
    await expect(page.locator('.wir-gate-note')).toHaveCount(0);
    // ...the note's own words, which an unedited page must never say (SF20).
    await expect(page.locator('#serverBoard')).not.toContainText('these edits');
    expect(await page.evaluate(() =>
      (window as any).WIR.serverKpis._gateStrip().filter((g: any) => g.changed).length)).toBe(0);
    // U8h, and Batch 1's deferred case: the lead says components — a file has no sources since C8 —
    // and, with this run's collection on the page, never that nothing was collected.
    const lead = page.locator('#serverBoard > .wir-empty').first();
    await expect(lead).toContainText('This file declares components and no server asserts yet.');
    await expect(lead).toContainText('This run’s collection is below');
    await expect(page.locator('#serverBoard')).not.toContainText('server-metrics sources');

    // An assert added: a Save would now judge orders-db — FAIL, this run had a deadlock.
    await page.click('.wir-component-card[data-component="orders-db"] .wir-component-add');
    await page.check('[data-wir-dlg="pick:deadlocks"]');
    await page.click('#serverPickNext');
    await page.click('#serverAddAssertsBtn');
    await expect(page.locator('#serverUnsavedPill')).toHaveText('1 unsaved change');
    await expect(page.locator('.wir-component-card[data-component="orders-db"] .wir-would-be'))
      .toHaveText('→ FAIL if saved');
    // The interim lead no longer claims "no collected payload was given": one was, and the
    // added assert is judged against it live.
    await expect(lead).toContainText('declared no server asserts, so the build judged none. The one declared '
      + 'here is judged against this run’s collection as it is written');
    await expect(page.locator('#serverBoard')).not.toContainText('no collected payload was given');
  });

  test('[U] a client-only edit projects once a server assert is declared: the load rule an assert requires', async ({ page }) => {
    // gateStrip's second clause. A server edit is not the only one a Save carries: a client rule an
    // assert's requiresLoad names re-decides that assert, so once the document declares a server
    // assert, ANY edit can move a component. E26's own kafka already FAILs on lag-spike, so it is
    // cut to the shape where the load rule alone decides it — needs-load (requiresLoad
    // load:unreachable, which this run misses) and parked — with the word Java gives that file
    // for kafka, NOT_EVALUATED.
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    await page.evaluate(() => {
      const W = window as any;
      const keep = ['needs-load', 'parked'];
      const d = JSON.parse(JSON.stringify(W.globalChartData));
      d.annotationsDocument.serverMetrics.asserts = d.annotationsDocument.serverMetrics.asserts
        .filter((k: any) => k.component !== 'kafka' || keep.indexOf(k.id) >= 0);
      d.serverKpis = d.serverKpis.filter((k: any) => k.component !== 'kafka' || keep.indexOf(k.id) >= 0);
      d.serverGates = d.serverGates.map((g: any) => (g.id === 'kafka' ? { ...g, status: 'NOT_EVALUATED' } : g));
      W.globalChartData = d;
      W.WIR.serverKpis.init(d);
      W.WIR.document.start();
      W.WIR.serverKpis.rerender();
    });
    const kafka = page.locator('.wir-component-card[data-component="kafka"]');
    await expect(kafka.locator('tr.wir-kpi-row', { hasText: 'needs-load' }).locator('.wir-chip'))
      .toHaveText('LOAD NOT ACHIEVED');
    await expect(page.locator('.wir-would-be')).toHaveCount(0);

    // Only the client rule moves: load:unreachable lowered below what the run achieved.
    await page.evaluate(() => (window as any).WIR.asserts._retune('load:unreachable', 10, 'fail'));
    expect(await page.evaluate(() => (window as any).WIR.document.countOf('server'))).toBe(0);
    expect(await page.evaluate(() => (window as any).WIR.document.count())).toBe(1);
    // needs-load is judged now, on cpu's maximum far over its limit of 1: a Save FAILs kafka.
    await expect(kafka.locator('tr.wir-kpi-row', { hasText: 'needs-load' }).locator('.wir-chip')).toHaveText('FAIL');
    await expect(kafka.locator('.wir-would-be')).toHaveText('→ FAIL if saved');
    await expect(page.locator('.wir-component-card[data-component="db"] .wir-would-be')).toHaveCount(0);
  });
});

test.describe('F2: a proposal stays inside a percentage', () => {
  test('[U] the detail pane suggests inside 100 % from E27\'s 68 % peak — not fail > 102 %', async ({ page }) => {
    // Visible on the unmodified fixture: the metric is asserted, so it cannot be picked, but the
    // pane still says what Suggested would write. It read "fail > 102 % · warn > 85 %".
    await openServer(page);
    await page.click('.wir-component-card[data-component="orders-db"] .wir-component-add');
    await page.click('.wir-pick-name:has-text("Connections used")');
    await expect(page.locator('.wir-pick-detail')).toContainText('peak 68 % over 2 instances');
    await expect(page.locator('.wir-pick-detail')).toContainText('fail > 84 % · warn > 76 %');
  });

  test('[U] a run with no room below the ceiling proposes nothing, and the pane says why', async ({ page }) => {
    // 99.5 % with 50% of the room left is 99.75, which rounds to 100: a band that can never fire.
    // Nothing is proposed and the row asks — in its own words, not SF10's "can only fire on a
    // negative value", which is about a downward band and not this one.
    await patchSeriesBeforeLoad(page, 'orders-db', 'connections.used.pct', {}, 99.5);
    await openServer(page);
    await page.click('.wir-component-card[data-component="orders-db"] .wir-component-add');
    await page.click('.wir-pick-name:has-text("Connections used")');
    const facts = page.locator('.wir-pick-detail');
    await expect(facts).toContainText('peak 99.5 % over 2 instances');
    await expect(facts.locator('dd').last()).toHaveText(
      'none from this run — peak 99.5 % leaves no room below this metric’s ceiling of 100 %, so set a band');
  });
});

test.describe('Generate, over catalogue metrics', () => {
  test('[U] a collected catalogue metric is seeded as its metric, never as its PromQL', async ({ page }) => {
    await openServer(page);
    await page.evaluate(() => (window as any).WIR.serverKpis.generate());
    const seeded = (await block(page)).asserts.filter((k: any) => /seeded from this run/.test(k.description || ''));
    expect(seeded.length).toBeGreaterThan(0);
    seeded.forEach((k: any) => {
      expect(k.metric, JSON.stringify(k)).toBeTruthy();
      expect(k.query, 'a catalogue seed wrote its PromQL').toBeUndefined();
    });
  });
});
