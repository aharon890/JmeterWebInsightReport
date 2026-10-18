import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { getReportPath, getReportUrl, loadClean, expectNoJsErrors, switchTab } from './helpers';

/**
 * External-data mode: the report's chart payload lives in a sibling .js file loaded with a
 * <script src>, so the page is only correct if that file is the RIGHT one.
 *
 * Two reports written into one directory used to share a single payload named
 * web-insight-data.js, and the second run's payload won. The resulting page was not merely
 * mislabelled: the header cards and the sampler table are Thymeleaf-rendered from run 1 while
 * the charts and the sampler filter checkboxes are built by report.js from run 2's payload, so
 * the page offered filter controls for samplers absent from its own table. Nothing detected it,
 * because the only guard tested window.__WIR_DATA__ for presence.
 *
 * The identity check that closed that hole used to WARN and draw the charts anyway, on the
 * reasoning that stale charts with a banner beat no charts. That trade has been reversed and a
 * mismatch is now fatal: the page inserts an error block naming both runs, rewrites every
 * .chart-container to say the chart was withheld, and never calls initCharts at all. A banner
 * above a plausible-looking chart is read by nobody once the numbers below it look reasonable,
 * so wrong numbers that are trusted are worse than absent numbers that are explained.
 *
 * That is why the mismatch test below asserts the ABSENCE OF CHARTS and not merely the presence
 * of a message: a spec that only matched the banner text would have passed identically on both
 * sides of the reversal, which makes it no test of the behaviour that actually changed.
 *
 * Grepping the HTML (which the C17 shell scenario does) proves the reference is right. Only a
 * browser proves the payload is actually loaded, that charts are built from it, and that a
 * foreign payload leaves no chart behind.
 */
test.describe('External data mode', () => {
  const REPORT = 'E19';

  test('[R] the payload filename is derived from the report filename', () => {
    const html = fs.readFileSync(getReportPath(REPORT), 'utf-8');

    // Derived, not the old constant. A hardcoded name is what let two reports collide.
    expect(html).toContain('src="E19-report-data.js"');
    expect(html).not.toContain('src="web-insight-data.js"');

    const payload = path.join(path.dirname(getReportPath(REPORT)), 'E19-report-data.js');
    expect(fs.existsSync(payload), `${payload} should sit next to the report`).toBe(true);
  });

  test('[C/R] the payload actually loads and the charts come from it', async ({ page }) => {
    await loadClean(page, REPORT);

    // The page must have taken its data from the external file, not the inline "{}" placeholder.
    const loaded = await page.evaluate(() => ({
      hasPayload: typeof (window as any).__WIR_DATA__ === 'object'
        && (window as any).__WIR_DATA__ !== null,
      runId: (window as any).__WIR_DATA__?.runId,
      samplerCount: ((window as any).__WIR_DATA__?.samplers ?? []).length,
    }));

    expect(loaded.hasPayload).toBe(true);
    expect(loaded.runId, 'the payload must declare which run it belongs to').toBeTruthy();
    expect(loaded.samplerCount).toBeGreaterThan(0);

    // ...and a chart really built its series from that payload, rather than the page falling
    // back to empty data. Asked of ECharts directly, the way the interaction specs do, because
    // counting <canvas> elements only proves a container exists.
    // The Summary tab's chart, because charts are initialised per visible tab — asking about
    // one on a hidden tab would report "no instance" and prove nothing.
    const seriesCount = await page.evaluate(() => {
      const echarts = (window as any).echarts;
      const el = document.getElementById('summary-response-time-chart');
      if (!echarts || !el) return -1;
      const chart = echarts.getInstanceByDom(el);
      if (!chart) return -1;
      const series = chart.getOption()?.series ?? [];
      return series.length;
    });
    expect(seriesCount, 'a chart should have series built from the external payload')
      .toBeGreaterThan(0);

    // "charts unavailable" is the MISSING-payload wording specifically — the mismatch block
    // says something else entirely — and this report's payload loaded, so that is the case
    // worth naming here. The guard against the fatal path being over-eager is the assertion
    // above rather than this one: if the identity check ever fired on a report whose payload is
    // genuinely its own, initCharts would never run and seriesCount would be -1 — no chart
    // instance at all — which is why that assertion is the one not to weaken.
    await expect(page.locator('body')).not.toContainText('charts unavailable');
  });

  test('[R] the page and its payload agree on the run id', async ({ page }) => {
    await loadClean(page, REPORT);

    const payloadRunId = await page.evaluate(() => (window as any).__WIR_DATA__?.runId);
    const html = fs.readFileSync(getReportPath(REPORT), 'utf-8');
    const match = html.match(/pageRunId = "([^"]*)"/);

    expect(match, 'the report must carry its own run id for the guard to work').not.toBeNull();
    expect(payloadRunId).toBe(match![1]);

    // Agreement means the page raised no payload error of any kind. Anchored on the attribute
    // rather than on a phrase, because the attribute is the one part of the two error blocks
    // that is meant to be stable — the wording is expected to drift, and a negative assertion
    // against prose stops testing anything the moment it does, without ever going red.
    await expect(page.locator('[data-wir-payload-error]')).toHaveCount(0);
  });

  test('[C] a payload from a different run withholds every chart', async ({ page }) => {
    // The corruption this guard exists for, reproduced deterministically: serve the page a
    // payload whose runId is not its own. Before the guard, this rendered silently; before the
    // guard turned fatal, it rendered a banner over a full set of charts drawn from the wrong
    // run, which is the same wrong numbers with a note attached.
    // Intercept the payload's own assignment and relabel it as belonging to another run,
    // keeping everything else intact — which is exactly the shape of the real corruption:
    // a complete, valid payload from the wrong test.
    // The foreign id is sixteen hex characters because that is what RunIdentity emits now
    // (SHA-256 truncated to 64 bits, widened from the old 32-bit hashCode). A shorter stand-in
    // still trips the !== comparison, so the test would not have noticed the widening — which
    // is precisely why it should not be carrying a value the product can no longer produce.
    await page.addInitScript(() => {
      let stored: unknown;
      Object.defineProperty(window, '__WIR_DATA__', {
        configurable: true,
        set(value) {
          stored = { ...value, runId: '9f2c41ab7de05631', runTestName: 'SOME-OTHER-RUN' };
        },
        get() {
          return stored;
        },
      });
    });

    // The fatal path takes report.js somewhere it is never taken in a healthy run: initCharts
    // is skipped entirely, and the DOMContentLoaded bootstrap that follows (restoreChartLayout,
    // initChartDragDrop) then runs against panes that have had their contents replaced. A page
    // that throws on the way through is the likely regression here, and none of the assertions
    // below would see it — a thrown error leaves the error block and the withheld panes exactly
    // as they are.
    await expectNoJsErrors(page, async () => {
      await page.goto(getReportUrl(REPORT));
      await page.waitForLoadState('load');
    });

    // Found by attribute rather than by prose, so that rewording the message cannot silently
    // turn this into a test that asserts nothing. The kind matters as well as the presence:
    // "missing" and "mismatch" are different failures with different remedies.
    const errorBlock = page.locator('[data-wir-payload-error="mismatch"]');
    await expect(errorBlock).toHaveCount(1);
    await expect(errorBlock).toContainText('This report cannot show its charts.');

    // Both runs have to be named. "The data belongs to another run" is not actionable; "it
    // belongs to SOME-OTHER-RUN, and you are looking at this one" is what tells a reader which
    // of the two reports in the directory they are holding.
    await expect(errorBlock).toContainText('SOME-OTHER-RUN');
    await expect(errorBlock).toContainText('9f2c41ab7de05631');

    // ...and the whole point of the change: NO CHART. Mirrors the positive control above and
    // asks ECharts directly, because the error block sits at the top of <body> and would be
    // perfectly happy above twelve charts built from the wrong run — which is exactly what the
    // page used to do, and what an assertion on the message alone cannot tell apart.
    const chartState = await page.evaluate(() => {
      const echarts = (window as any).echarts;
      const el = document.getElementById('summary-response-time-chart');
      const chart = echarts && el ? echarts.getInstanceByDom(el) : null;
      return {
        echartsLoaded: !!echarts,
        paneFound: !!el,
        hasInstance: !!chart,
        seriesCount: chart ? (chart.getOption()?.series ?? []).length : -1,
      };
    });

    // Both preconditions are asserted rather than assumed. "No chart instance" is also true of
    // a page where ECharts failed to load or the pane was removed from the document, and either
    // would make the two assertions after them pass for a reason that has nothing to do with
    // the guard.
    expect(chartState.echartsLoaded, 'ECharts must still be loaded — the guard withholds the '
      + 'charts, it does not unload the library').toBe(true);
    expect(chartState.paneFound, 'the chart pane must still be in the document — it is '
      + 'repurposed to carry the explanation, not deleted').toBe(true);
    expect(chartState.hasInstance, 'no chart may be built from a foreign payload').toBe(false);
    expect(chartState.seriesCount, 'and so there is no series to count').toBe(-1);

    // The panes say so themselves rather than sitting empty, which would read as a rendering
    // bug in the report rather than as a deliberate refusal.
    await expect(page.locator('.chart-container').first())
      .toContainText('belongs to a different test run');
  });

  test('[C] a payload that cannot say which run it belongs to is refused too', async ({ page }) => {
    // The bypass in the guard above: it read
    //   if (pageRunId && chartData.runId && chartData.runId !== pageRunId)
    // so a payload with NO id skipped the comparison entirely and drew every chart. The one
    // payload that cannot prove it belongs here was the one payload that walked through.
    //
    // Two ways to reach it, neither contrived. This run's payload rename can fail while the
    // HTML's succeeds — ArtifactPublication.publish treats only the HTML as critical — leaving a
    // brand-new page beside whatever web-insight-data.js was already in the directory. And a
    // payload written by a plugin from before run identity existed has no id at all, which is
    // every report produced before 2.2.0 and still sitting in someone's output directory.
    //
    // Deleting the key rather than blanking it, because `undefined` is what an old payload
    // actually has; the empty-string case is the same falsy branch.
    await page.addInitScript(() => {
      let stored: unknown;
      Object.defineProperty(window, '__WIR_DATA__', {
        configurable: true,
        set(value) {
          const copy = { ...value, runTestName: 'A-RUN-FROM-BEFORE-IDENTITY' };
          delete (copy as Record<string, unknown>).runId;
          stored = copy;
        },
        get() {
          return stored;
        },
      });
    });

    await expectNoJsErrors(page, async () => {
      await page.goto(getReportUrl(REPORT));
      await page.waitForLoadState('load');
    });

    // Refused as a mismatch, not reported as a missing payload: the file is present and loaded,
    // it simply cannot establish that it is this report's. The two kinds have different remedies
    // and the data attribute is what a reader keys on.
    const errorBlock = page.locator('[data-wir-payload-error="mismatch"]');
    await expect(errorBlock).toHaveCount(1);
    await expect(errorBlock).toContainText('A-RUN-FROM-BEFORE-IDENTITY');
    await expect(errorBlock).toContainText('unknown');

    // And the assertion that actually distinguishes the fix from the bug. Before it, this page
    // drew twelve charts from a foreign payload under this run's headings, silently.
    const chartState = await page.evaluate(() => {
      const echarts = (window as any).echarts;
      const el = document.getElementById('summary-response-time-chart');
      const chart = echarts && el ? echarts.getInstanceByDom(el) : null;
      return {
        echartsLoaded: !!echarts,
        paneFound: !!el,
        hasInstance: !!chart,
      };
    });
    expect(chartState.echartsLoaded).toBe(true);
    expect(chartState.paneFound).toBe(true);
    expect(chartState.hasInstance,
      'an unidentified payload must withhold its charts exactly as a foreign one does')
      .toBe(false);

    await expect(page.locator('.chart-container').first())
      .toContainText('belongs to a different test run');
  });

  test('[C] with the sidecar missing or foreign, the Server tab speaks for itself', async ({ page }) => {
    // R3-15. The template handed the Server tab the Asserts tab's sentence verbatim — "so the
    // assert rules and their verdicts could not be loaded" — beside a header that can carry a
    // server chip. And nothing pinned the Server tab here at all, so a regression back to the
    // template's "the scripts did not run" placeholder (which is false: they ran, the data did
    // not arrive) would have gone unnoticed.
    //
    // Missing: the setter drops the assignment, which is what a sidecar that never loaded
    // leaves behind. Over file:// a route cannot abort a <script src>, so this is the honest way
    // to remove it.
    await page.addInitScript(() => {
      Object.defineProperty(window, '__WIR_DATA__', {
        configurable: true,
        set() { /* the sidecar never arrived */ },
        get() { return undefined; },
      });
    });
    await expectNoJsErrors(page, async () => {
      await page.goto(getReportUrl(REPORT));
      await page.waitForLoadState('load');
    });
    await expect(page.locator('[data-wir-payload-error="missing"]')).toHaveCount(1);
    await switchTab(page, 'server');
    const board = page.locator('#serverBoard');
    await expect(board).toContainText('so the server metrics and their verdicts could not be loaded');
    await expect(board).toContainText('Could not load E19-report-data.js');
    await expect(board).not.toContainText('assert rules');
    await expect(board).not.toContainText('scripts did not run');
    // The template still calls initCharts with its inline '{}' placeholder after raising this,
    // so the other tabs render what they have. That init used to wipe the reason, and the tab
    // then claimed "This run declared no server metrics" about a report whose declaration was
    // in the very file that did not arrive.
    await expect(board).not.toContainText('declared no server metrics');
  });

  /**
   * SF3. The HTML forwarded on its own — a COPY of E19's report in a directory without its
   * -data.js, opened over file://, which is how the reader actually receives it. The template
   * raises unavailable() and then still calls initCharts on its inline '{}' placeholder, so the
   * other tabs render; WIR.asserts.init used to wipe the reason there, and the tab said "No asserts
   * declared. This run was reported, not judged" — under a header verdict — while its Download
   * wrote {"assertions": []} over the team's file, and every draft was filed under 'unknown'.
   */
  function forwardedCopy(testInfo: any, name: string, edit?: (html: string) => string): string {
    const dir = testInfo.outputPath(name);
    fs.mkdirSync(dir, { recursive: true });
    const html = fs.readFileSync(getReportPath(REPORT), 'utf-8');
    const target = path.join(dir, 'E19-report.html');
    fs.writeFileSync(target, edit ? edit(html) : html);
    expect(fs.existsSync(path.join(dir, 'E19-report-data.js')), 'the copy travels without its sidecar').toBe(false);
    return target;
  }

  test('[C] SF3: with the sidecar missing, the Asserts tab keeps its reason, nothing downloads, drafts are keyed by the run',
    async ({ page }, testInfo) => {
      const copy = forwardedCopy(testInfo, 'forwarded');
      const pageRunId = fs.readFileSync(copy, 'utf-8').match(/pageRunId = "([^"]*)"/)![1];
      const downloads: string[] = [];
      page.on('download', (d) => downloads.push(d.suggestedFilename()));
      await expectNoJsErrors(page, async () => {
        await page.goto('file://' + copy);
        await page.waitForLoadState('load');
      });
      await expect(page.locator('[data-wir-payload-error="missing"]')).toHaveCount(1);

      // The Summary strip and the tab both say why — after init has run on the placeholder.
      await expect(page.locator('#gateStrip')).toContainText('UNAVAILABLE');
      await expect(page.locator('#gateStrip')).toContainText('Could not load E19-report-data.js');
      await switchTab(page, 'asserts');
      const tab = page.locator('#tab-asserts');
      await expect(tab).toContainText('UNAVAILABLE');
      await expect(tab).toContainText('Could not load E19-report-data.js, so the assert rules and their verdicts could not be loaded.');
      await expect(tab).not.toContainText('This run was reported, not judged');
      await expect(tab).not.toContainText('No asserts declared');
      await expect(page.locator('#saveAssertsBtn')).toHaveCount(0);

      // Nothing offers to write a file this page never received: the Notes tab's Save is off and
      // says why, and the Download itself refuses whoever calls it.
      await switchTab(page, 'notes');
      const save = page.locator('#saveAnnotationsBtn');
      await expect(save).toBeDisabled();
      await expect(save).toHaveAttribute('title', /Download is off on this page\. Could not load E19-report-data\.js/);
      await page.evaluate(() => {
        (window as any).WIR.document.download();
        (window as any).WIR.serverKpis.download();
      });
      await expect(page.locator('#wir-toast')).toContainText('Download is off on this page.');
      await page.waitForTimeout(500);
      expect(downloads, 'a report-annotations.json was written from a page without its payload').toEqual([]);

      // The clipboard is the same file by hand, so both Copy actions refuse in the same words and
      // copy nothing.
      await page.evaluate(() => {
        const w = window as any;
        w.__copied = [];
        Object.defineProperty(navigator, 'clipboard', {
          configurable: true,
          value: { writeText: (t: string) => { w.__copied.push(t); return Promise.resolve(); } },
        });
        document.execCommand = ((cmd: string) => { w.__copied.push('execCommand:' + cmd); return true; }) as any;
      });
      for (const copy of ['copyDocument', 'copyAssertions']) {
        await page.evaluate(() => { document.getElementById('wir-toast')!.textContent = ''; });
        await page.evaluate((name) => (window as any).WIR.asserts[name](), copy);
        await expect(page.locator('#wir-toast'), copy).toHaveText(/^Download is off on this page\. /);
      }
      await page.waitForTimeout(200);
      expect(await page.evaluate(() => (window as any).__copied), 'nothing reached the clipboard').toEqual([]);

      // Filed under this run, never 'unknown' — and nothing is filed while there is no document,
      // not even an edit: the base filed this note under wir_doc_drafts_unknown.
      expect(await page.evaluate(() => (window as any).WIR.document.draftKey())).toBe('wir_doc_drafts_' + pageRunId);
      await page.fill('#notesTextarea', 'typed on a page without its payload');
      await page.locator('#notesTextarea').dispatchEvent('input');
      await page.evaluate(() => (window as any).WIR.document.persist());
      await page.waitForTimeout(300);
      expect(await page.evaluate(() => Object.keys(localStorage).filter((k) => k.startsWith('wir_doc_drafts_'))))
        .toEqual([]);

      // rerender is public — the Server tab's commit, its add-component dialog and its delete Undo
      // call it — and must not replace the reason with the ordinary tab and its toolbar.
      await switchTab(page, 'asserts');
      await page.evaluate(() => (window as any).WIR.asserts.rerender());
      await expect(tab).toContainText('UNAVAILABLE');
      await expect(tab).toContainText('Could not load E19-report-data.js');
      await expect(page.locator('#assertsToolbar button')).toHaveCount(0);
      await expect(page.locator('#gateStrip')).toContainText('UNAVAILABLE');

      // E19 declares no server block, so its header has no server chip and the Summary carries no
      // server card — the chip is the only word on this page that server asserts were declared.
      await expect(page.locator('.wir-server-chip')).toHaveCount(0);
      await expect(page.locator('#serverStrip')).toBeEmpty();
    });

  test('[C] SF3: a draft made on the full report is not restored over the copy that lost its sidecar',
    async ({ page }, testInfo) => {
      // Drafts key on pageRunId since SF3, so a draft typed on the full report is filed under the
      // very key the forwarded copy reads — on the base that copy read wir_doc_drafts_unknown and
      // could never see it. restore() stands down while a slice is unavailable; without that the
      // note came back, the Asserts tab lost its reason, and "+ Add assert" and Download were
      // offered over a page without its payload.
      const copy = forwardedCopy(testInfo, 'forwarded-draft');
      const pageRunId = fs.readFileSync(copy, 'utf-8').match(/pageRunId = "([^"]*)"/)![1];
      await loadClean(page, REPORT);
      await switchTab(page, 'notes');
      await page.fill('#notesTextarea', 'a draft made where the payload arrived');
      await page.locator('#notesTextarea').dispatchEvent('input');
      await expect.poll(() => page.evaluate(() => Object.keys(localStorage)
        .filter((k) => k.startsWith('wir_doc_drafts_')))).toEqual(['wir_doc_drafts_' + pageRunId]);

      await expectNoJsErrors(page, async () => {
        await page.goto('file://' + copy);
        await page.waitForLoadState('load');
      });
      await expect(page.locator('[data-wir-payload-error="missing"]')).toHaveCount(1);
      await switchTab(page, 'asserts');
      const tab = page.locator('#tab-asserts');
      await expect(tab).toContainText('UNAVAILABLE');
      await expect(tab).not.toContainText('unsaved change');
      await expect(page.locator('#assertsToolbar button')).toHaveCount(0);
      await expect(page.locator('#notesTextarea')).toHaveValue('');
      expect(await page.evaluate(() => (window as any).WIR.document.count())).toBe(0);
      // Left where it was, for the report it belongs to.
      expect(await page.evaluate(() => Object.keys(localStorage).filter((k) => k.startsWith('wir_doc_drafts_'))))
        .toEqual(['wir_doc_drafts_' + pageRunId]);
    });

  test('[C] SF3: with the sidecar missing beside a server chip, the Summary server card says why', async ({ page }, testInfo) => {
    // The header exactly as Java renders it for a run whose server gate failed (E26's), spliced
    // into the forwarded copy: the chip is rendered from the run's own evaluation, and it is what
    // the card keys on. The card used to be blank beside it.
    const chip = '<button type="button" class="status-badge wir-server-chip badge-fail" data-wir-tab="server"\n'
      + '                        title="The worst server component - open the Server tab for every component">'
      + 'Server · Kafka FAIL · 1 more</button>';
    const copy = forwardedCopy(testInfo, 'forwarded-chip', (html) => {
      const anchor = 'NO ASSERTS DECLARED</span>';
      expect(html.indexOf(anchor), 'E19\'s header badge').toBeGreaterThan(0);
      return html.replace(anchor, anchor + '\n                ' + chip);
    });
    await expectNoJsErrors(page, async () => {
      await page.goto('file://' + copy);
      await page.waitForLoadState('load');
    });
    const card = page.locator('#serverStrip .wir-server-strip');
    await expect(card).toBeVisible();
    await expect(card.locator('.wir-chip-lg')).toHaveText('UNAVAILABLE');
    await expect(card).toContainText('Could not load E19-report-data.js, so the server metrics and their verdicts could not be loaded.');
    await expect(card).not.toContainText('FAIL');
    await switchTab(page, 'server');
    await expect(page.locator('#serverBoard')).toContainText('so the server metrics and their verdicts could not be loaded');
  });

  for (const [state, word, sentence] of [
    ['NOT_COLLECTED', 'NOT COLLECTED', 'Nothing has been collected for this run yet, so no server assert was judged.'],
    ['REFUSED', 'REFUSED', 'The server-metrics payload given to this report was refused, so no server assert was judged.'],
  ]) {
    test(`[C] SF47: with the sidecar missing beside a ${word} chip, the Summary server card claims no verdict`, async ({ page }, testInfo) => {
      // Since SF47 the chip is on interim and refused runs too. The card keyed on the chip alone,
      // so a forwarded interim report read "the server metrics and their verdicts could not be
      // loaded. The server verdict in the header above came from the run itself and is correct" —
      // beside Server · NOT COLLECTED, which is not a verdict, on a run that has no metrics.
      const chip = '<button type="button" class="status-badge wir-server-chip badge-none" data-wir-tab="server"\n'
        + `                        data-wir-chip-state="${state}" title="open the Server tab">Server · ${word}</button>`;
      const copy = forwardedCopy(testInfo, 'forwarded-' + state.toLowerCase(), (html) => {
        const anchor = 'NO ASSERTS DECLARED</span>';
        expect(html.indexOf(anchor), 'E19\'s header badge').toBeGreaterThan(0);
        return html.replace(anchor, anchor + '\n                ' + chip);
      });
      await expectNoJsErrors(page, async () => {
        await page.goto('file://' + copy);
        await page.waitForLoadState('load');
      });
      const card = page.locator('#serverStrip .wir-server-strip');
      await expect(card).toBeVisible();
      await expect(card.locator('.wir-chip-lg')).toHaveText(word);
      await expect(card).toContainText(sentence);
      await expect(card).toContainText('Could not load E19-report-data.js');
      await expect(card).not.toContainText('verdict in the header');
      await expect(card).not.toContainText('their verdicts');

      // The chip itself, 12px bold in badge-none, reaches 4.5:1 in both themes: its #64748b on
      // #f1f5f9 was 4.34:1, now on every interim and refused run's header.
      for (const dark of [false, true]) {
        if (dark) await page.evaluate(() => (window as any).toggleDarkMode());
        const ratio = await page.locator('.wir-server-chip').evaluate((el) => {
          const parse = (c: string) => (c.match(/[\d.]+/g) || []).slice(0, 3).map(Number);
          const lin = (v: number) => { const s = v / 255; return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4); };
          const lum = (c: number[]) => 0.2126 * lin(c[0]) + 0.7152 * lin(c[1]) + 0.0722 * lin(c[2]);
          const s = getComputedStyle(el);
          const [hi, lo] = [lum(parse(s.color)), lum(parse(s.backgroundColor))].sort((a, b) => b - a);
          return (hi + 0.05) / (lo + 0.05);
        });
        expect(ratio, `${dark ? 'dark' : 'light'}: Server · ${word}`).toBeGreaterThanOrEqual(4.5);
      }
    });
  }

  test('[C] with a foreign sidecar, the Server tab says the server metrics were withheld', async ({ page }) => {
    await page.addInitScript(() => {
      let stored: unknown;
      Object.defineProperty(window, '__WIR_DATA__', {
        configurable: true,
        set(value) { stored = { ...value, runId: '9f2c41ab7de05631', runTestName: 'SOME-OTHER-RUN' }; },
        get() { return stored; },
      });
    });
    await page.goto(getReportUrl(REPORT));
    await page.waitForLoadState('load');
    await switchTab(page, 'server');
    const board = page.locator('#serverBoard');
    await expect(board).toContainText('belongs to a different test run, so the server metrics and their verdicts could not be loaded');
    await expect(board).not.toContainText('assert rules');
  });
});
