import { test, expect } from '@playwright/test';
import { loadClean, switchTab } from './helpers';

/**
 * XSS Escaping Tests (SEC-1 / FE-1 / FE-2)
 *
 * ECharts renders a tooltip formatter's return value as HTML (default
 * renderMode 'html', inserted via innerHTML). Sampler labels and response
 * codes are attacker-influenceable — they come from the JTL label column and
 * from the system under test — so every data value a formatter interpolates
 * must go through escapeHtml().
 *
 * These tests drive the formatters directly with a hostile payload instead of
 * hovering charts, so they are deterministic and cover formatters whose chart
 * may be empty for a given fixture.
 */

const PAYLOAD = `<img src=x onerror="window.__XSS__=1">`;

/** The payload must never survive as live markup. */
function assertNoLiveMarkup(html: string, context: string) {
  expect(html, `${context} must not emit a raw tag`).not.toContain('<img');
  expect(html, `${context} must not emit an unescaped '<'`).not.toMatch(/<(?!\/?(?:b|br|span|strong)\b)/i);
}

/** The payload must be present, escaped — proves it actually reached the sink. */
function assertEscapedPayloadPresent(html: string, context: string) {
  expect(html, `${context} must escape the payload`).toContain('&lt;img');
}

test.describe('XSS — ECharts tooltip formatters', () => {
  test('[R] escapeHtml neutralizes tags, quotes and ampersands', async ({ page }) => {
    await loadClean(page, 'E02');
    const out = await page.evaluate((p) => (window as any).escapeHtml(p), PAYLOAD);
    assertNoLiveMarkup(out, 'escapeHtml');
    assertEscapedPayloadPresent(out, 'escapeHtml');
    expect(out).toContain('&quot;');
    expect(await page.evaluate(() => (window as any).escapeHtml('a & b'))).toBe('a &amp; b');
  });

  test('[R] escapeHtml handles non-string values (numeric response codes)', async ({ page }) => {
    await loadClean(page, 'E02');
    const out = await page.evaluate(() => {
      const esc = (window as any).escapeHtml;
      return { num: esc(500), zero: esc(0), nul: esc(null), undef: esc(undefined) };
    });
    expect(out.num).toBe('500');
    expect(out.zero).toBe('0');
    expect(out.nul).toBe('');
    expect(out.undef).toBe('');
  });

  test('[C/R] chartTooltipFormatter escapes the sampler name', async ({ page }) => {
    await loadClean(page, 'E02');
    const html = await page.evaluate((p) => (window as any).chartTooltipFormatter([
      { value: [Date.now(), 123], seriesName: p, marker: '<span></span>' },
    ]), PAYLOAD);
    assertNoLiveMarkup(html, 'chartTooltipFormatter');
    assertEscapedPayloadPresent(html, 'chartTooltipFormatter');
  });

  test('[C/R] comparison tooltip formatters escape the sampler name', async ({ page }) => {
    await loadClean(page, 'E02');
    const out = await page.evaluate((p) => {
      const w = window as any;
      const params = [{ value: [1000, 42], seriesName: p, marker: '<span></span>' }];
      return { rt: w.compareTooltipFormatter(params), tp: w.compareTpTooltipFormatter(params) };
    }, PAYLOAD);
    for (const [name, html] of Object.entries(out)) {
      assertNoLiveMarkup(html, `compare formatter ${name}`);
      assertEscapedPayloadPresent(html, `compare formatter ${name}`);
    }
  });

  /**
   * Sweep every live chart: pull the tooltip formatter straight off the ECharts
   * option and feed it a hostile payload in every field a formatter reads.
   * This catches formatters added later that forget to escape.
   */
  test('[C/R] every live chart tooltip formatter escapes hostile data', async ({ page }) => {
    await loadClean(page, 'E02');
    // Visit the tabs that own charts so their instances are created.
    for (const tab of ['summary', 'timeline', 'errors', 'samplers']) {
      await switchTab(page, tab);
    }

    const result = await page.evaluate((p) => {
      const w = window as any;
      const outputs: string[] = [];

      const axisParams = [{
        value: [Date.now(), 100],
        seriesName: p,
        name: p,
        axisValueLabel: p,
        marker: '<span></span>',
        data: [Date.now(), 100],
      }];
      const itemParams = {
        value: [Date.now(), p, 1],
        seriesName: p,
        name: p,
        axisValueLabel: p,
        marker: '<span></span>',
        data: [Date.now(), p],
      };

      (w.chartInstances || []).forEach((chart: any) => {
        let option: any;
        try { option = chart.getOption(); } catch (e) { return; }
        const tooltips = [].concat(option && option.tooltip ? option.tooltip : []);
        tooltips.forEach((tt: any) => {
          if (!tt || typeof tt.formatter !== 'function') return;
          [axisParams, itemParams].forEach((params) => {
            try {
              const out = tt.formatter(params);
              if (typeof out === 'string') outputs.push(out);
            } catch (e) { /* formatter needs a different param shape — skip */ }
          });
        });
      });

      return outputs;
    }, PAYLOAD);

    expect(result.length, 'expected at least one chart tooltip formatter to run').toBeGreaterThan(0);
    result.forEach((html, i) => assertNoLiveMarkup(html, `chart tooltip formatter #${i}`));
    // At least one formatter must have consumed the payload, otherwise the
    // sweep proved nothing.
    expect(
      result.some((html) => html.includes('&lt;img')),
      'no chart tooltip formatter consumed the payload — sweep is not exercising the sinks',
    ).toBe(true);
  });
});

test.describe('XSS — clipboard HTML', () => {
  test('[C/R] copySummaryAsHtml escapes sampler names in the clipboard blob', async ({ page }) => {
    await loadClean(page, 'E02');
    await switchTab(page, 'summary');

    const html = await page.evaluate(async (p) => {
      const w = window as any;
      // Plant the payload in a Top-5 sampler name cell.
      const nameCell = document.querySelector('#tab-summary .top-sampler-name');
      if (nameCell) nameCell.textContent = p;

      // Capture the blob instead of touching the real clipboard.
      let captured = '';
      const realClipboard = navigator.clipboard;
      Object.defineProperty(navigator, 'clipboard', {
        configurable: true,
        value: {
          write: async (items: any[]) => {
            const blob = await items[0].getType('text/html');
            captured = await blob.text();
          },
        },
      });

      try {
        w.copySummaryAsHtml();
        // copySummaryAsHtml resolves through a promise chain.
        await new Promise((r) => setTimeout(r, 200));
      } finally {
        Object.defineProperty(navigator, 'clipboard', { configurable: true, value: realClipboard });
      }
      return captured;
    }, PAYLOAD);

    expect(html, 'clipboard blob should have been captured').not.toBe('');
    expect(html, 'copySummaryAsHtml must not emit a raw tag').not.toContain('<img');
    assertEscapedPayloadPresent(html, 'copySummaryAsHtml');
  });
});

test.describe('XSS — Asserts inline handlers', () => {
  /**
   * The row actions and the "Several" sampler checklist interpolate run-derived strings
   * into onclick="..." / onchange="..." — two parsers deep. The HTML attribute parser
   * decodes entities before the JS parser ever sees the text, so a string that already
   * contains "&quot;" is turned back into a bare quote inside the literal.
   *
   * `JSON.stringify(x).replace(/"/g,'&quot;')` never escaped '&' and so did exactly that:
   * a rule id of `x&quot;);window.__pwned=1;//` executed on click. Rule ids are seeded from
   * sampler names by the ⊕ buttons and the wizard, so this is JTL content, not typed input.
   */
  const ENTITY_BREAKOUT = `x&quot;);window.__pwned=1;//`;
  const QUOTE_BREAKOUT = `x');window.__pwned=1;//`;

  /** Re-init the asserts module with one rule renamed to `id`, and open the tab. */
  async function initWithRuleId(page: any, id: string) {
    await page.evaluate((hostile: string) => {
      const w = window as any;
      const data = JSON.parse(JSON.stringify(w.globalChartData));
      data.assertions[0].id = hostile;
      w.WIR.asserts.init(data);
    }, id);
    await switchTab(page, 'asserts');
  }

  for (const [name, payload] of [['an entity-encoded quote', ENTITY_BREAKOUT],
                                 ['a bare single quote', QUOTE_BREAKOUT]] as const) {
    test(`[C/R] ${name} in a rule id cannot execute from the row actions`, async ({ page }) => {
      await loadClean(page, 'E25');
      await initWithRuleId(page, payload);

      const row = page.locator(`tr[data-rule-id="${payload.replace(/"/g, '\\"')}"]`);
      await expect(row, 'the hostile rule must actually have rendered').toHaveCount(1);

      // The row's handlers carry its KEY, which the page mints, and no part of the rule id
      // (client-id-keyed-handlers): the id reaches no handler at all, so there is nothing in one
      // for the HTML parser to decode into a quote. Still one jsArg literal — the sweep below.
      // jsArg's own breakout vectors are in js-unit (asserts-pure.test.js, _jsArg).
      const handler = await row.locator('.wir-edit-btn').getAttribute('onclick');
      expect(handler, 'the edit handler names something other than the row key')
        .toMatch(/^WIR\.asserts\.edit\('r\d+'\)$/);
      expect(handler, 'the rule id reached the handler').not.toContain('pwned');

      await row.locator('.wir-edit-btn').click();
      expect(await page.evaluate(() => (window as any).__pwned),
        'the injected statement executed').toBeUndefined();

      // The handler still works: it opened the editor on that exact rule.
      await expect(page.locator('#editorId')).toHaveValue(payload);
    });
  }

  test('[C/R] every row action carries the id as data, not as code', async ({ page }) => {
    // Edit / Duplicate / Disable / Delete and the matches toggle all build the same literal.
    //
    // Asserted structurally rather than by searching for the payload: getAttribute returns the
    // string AFTER the HTML parser has decoded it, which is exactly what the JS parser will
    // see, and `;window.__pwned=1` is harmless there as long as it is still inside the quotes.
    // So the claim is that each handler is one call taking one well-formed string literal and
    // nothing after it — which no breakout can satisfy.
    // Both judging modules (D20). The method name is [A-Za-z]+ on purpose — no digits, no
    // underscore — so a test seam like _toggle or a numbered variant cannot be reached from an
    // attribute, and the argument list is at most ONE well-formed literal, so a compound key
    // has to be packed into a single string rather than passed as a second argument.
    const ONE_CALL = /^WIR\.(asserts|serverKpis)\.[A-Za-z]+\((?:'(?:[^'\\]|\\.)*')?\)$/;

    // Handlers built from a CONSTANT string, which no run-derived value can reach. Listed by
    // hand rather than pattern-matched, so a second one has to be added deliberately and is
    // read by whoever reviews the diff — the point of the sweep is that a handler carrying data
    // cannot be written by accident, and an exception nobody notices would give that away.
    const CONSTANT_HANDLERS = new Set([
      "document.getElementById('assertsFileInput').click()",
    ]);

    await loadClean(page, 'E25');
    await initWithRuleId(page, ENTITY_BREAKOUT);

    // Scoped to the whole tab, not to #assertsTable. A structural sweep is only worth having
    // if it covers every inline handler the tab can render, and this one did not: the
    // broken-rule banner lives in #assertsBroken, outside the table, and it interpolated the
    // rule id through esc() — which escapes for the HTML attribute parser and leaves the single
    // quote alone. A live breakout on the one surface whose whole job is to say a rule is
    // broken, in the blind spot of the check written to prevent it.
    const handlers = await page.evaluate(() =>
      Array.from(document.querySelectorAll('#tab-asserts [onclick]'))
        .map((el) => el.getAttribute('onclick') || ''));

    expect(handlers.length).toBeGreaterThan(0);
    handlers.forEach((h) => {
      if (CONSTANT_HANDLERS.has(h)) return;
      expect(h, `handler is not a single well-formed call: ${h}`).toMatch(ONE_CALL);
    });
  });

  test('[C/R] an operator an opened file wrote is text in the editor\'s refusal (C-1)', async ({ page }) => {
    // A band nobody could read is carried on its rule and refused in the editor in the mapper's
    // words, which quote the file's own operator key — content from a file, now on two surfaces
    // (#editorProblem and the preview's empty line). Both go through esc().
    const HOSTILE = '<img src=x onerror=alert(1)>';
    const dialogs: string[] = [];
    page.on('dialog', (d) => { dialogs.push(d.message()); d.dismiss(); });

    await loadClean(page, 'E25');
    await switchTab(page, 'asserts');
    const doc = JSON.parse(await page.evaluate(() => (window as any).WIR.document.text()));
    doc.assertions.find((a: any) => a.id === 'slow-api-p95').fail = { [HOSTILE]: 900 };
    await page.setInputFiles('#assertsFileInput', {
      name: 'hostile-operator.json', mimeType: 'application/json',
      buffer: Buffer.from(JSON.stringify(doc, null, 2)),
    });
    await expect(page.locator('#assertsSource')).toContainText('hostile-operator.json');

    await page.locator('tr[data-rule-id="slow-api-p95"] .wir-edit-btn').click();
    await expect(page.locator('#editorProblem')).toContainText(`Fail operator '${HOSTILE}'`);
    expect(await page.locator('#assertEditor img').count(), 'the operator created an element').toBe(0);
    expect(dialogs, 'the operator executed').toEqual([]);

    const written = JSON.parse(await page.evaluate(() => (window as any).WIR.document.text()))
      .assertions.find((a: any) => a.id === 'slow-api-p95');
    expect(written.fail, 'the band was not written back verbatim').toEqual({ [HOSTILE]: 900 });
  });

  test('[C/R] a band an opened file wrote is text in the server editor, and its × is one call (B4)', async ({ page }) => {
    // The Server tab's band row shows a carried band as the file wrote it (its JSON, operator key
    // included) beside the refusal that quotes the key, and carries a × whose handler is built
    // there. The JSON and the sentence go through esc(); the ×'s one argument is the band's kind.
    const HOSTILE = "<img src=x onerror=alert(1)>');window.__pwned=1//";
    const ONE_CALL = /^WIR\.(asserts|serverKpis)\.[A-Za-z]+\((?:'(?:[^'\\]|\\.)*')?\)$/;
    const dialogs: string[] = [];
    page.on('dialog', (d) => { dialogs.push(d.message()); d.dismiss(); });

    await loadClean(page, 'E26');
    const doc = JSON.parse(await page.evaluate(() => (window as any).WIR.document.text()));
    doc.serverMetrics.asserts.find((k: any) => k.id === 'lag-spike').fail = { [HOSTILE]: 900 };
    await page.setInputFiles('#assertsFileInput', {
      name: 'hostile-server-band.json', mimeType: 'application/json',
      buffer: Buffer.from(JSON.stringify(doc, null, 2)),
    });
    await expect(page.locator('#assertsSource')).toContainText('hostile-server-band.json');
    await switchTab(page, 'server');
    await page.evaluate(() => {
      const W = (window as any).WIR.serverKpis;
      W.edit(W._keyOf('lag-spike'));
    });

    await expect(page.locator('#serverEditor-fail-carried')).toContainText(JSON.stringify({ [HOSTILE]: 900 }));
    await expect(page.locator('#serverEditorProblem')).toContainText(`fail operator '${HOSTILE}'`);
    expect(await page.locator('#serverEditor img').count(), 'the band created an element').toBe(0);
    const handlers = await page.evaluate(() => Array.from(document.querySelectorAll('#serverEditor [onclick]'))
      .map((el) => el.getAttribute('onclick') || ''));
    expect(handlers).toContain("WIR.serverKpis.removeBand('fail')");
    handlers.forEach((h) => expect(h, `handler is not a single well-formed call: ${h}`).toMatch(ONE_CALL));
    expect(dialogs, 'the band executed').toEqual([]);
    expect(await page.evaluate(() => (window as any).__pwned)).toBeUndefined();

    const written = JSON.parse(await page.evaluate(() => (window as any).WIR.document.text()))
      .serverMetrics.asserts.find((k: any) => k.id === 'lag-spike');
    expect(written.fail, 'the band was not written back verbatim').toEqual({ [HOSTILE]: 900 });
  });

  test('[C/R] a PromQL query is content, not code', async ({ page }) => {
    // A query is authored text. The board no longer draws it (design §3: the resolved query is
    // the collector's business), so the surface left is the editor's Query input — reopened
    // after the Save, so the text has round-tripped through the parser, the document and the
    // form. All three of these shapes are ordinary in PromQL or in a label value:
    //   </script>  closes the inlined script block if it is ever written unescaped
    //   " and '    close an attribute and a JS string literal respectively
    //   ${         is the interpolation syntax the collector resolves, so a query legitimately
    //              contains it and it must survive round-tripping without being resolved here
    const HOSTILE = `up{job="</script><img src=x onerror=alert(1)>",p='${'${'}secret.T}'}`;

    await loadClean(page, 'E26');
    await page.click('.tab-btn[data-tab="server"]');

    const result = await page.evaluate((q) => {
      const W = (window as any).WIR.serverKpis;
      W.edit(W._keyOf('lag-spike'));
      W.set('query', q);
      W.applyEditor();
      W.edit(W._keyOf('lag-spike'));
      const input = document.querySelector('#serverEditor [data-field="query"]') as HTMLInputElement | null;
      const value = input ? input.value : null;
      W.closeEditor();
      return {
        // The parser saw the whole string, so nothing was truncated at the quote.
        written: JSON.parse((window as any).WIR.document.text()).serverMetrics.asserts
          .find((k: any) => k.id === 'lag-spike').query,
        // It reached the form as TEXT, and created no elements anywhere on the tab.
        rendered: value,
        injected: document.querySelectorAll('#tab-server img').length,
        scripts: document.querySelectorAll('#tab-server script').length,
      };
    }, HOSTILE);

    expect(result.written, 'the query was truncated or altered on the way to the file')
      .toBe(HOSTILE);
    expect(result.rendered, 'the query did not reach the editor as text').toBe(HOSTILE);
    expect(result.injected, 'the query created an element').toBe(0);
    expect(result.scripts, 'the query created a script').toBe(0);
    expect(await page.evaluate(() => (window as any).__pwned)).toBeUndefined();
  });

  test('[C/R] a component id with a quote cannot break out of a filter handler', async ({ page }) => {
    // The component id goes into an inline handler — WIR.serverKpis.filterGate('<id>') — so it
    // crosses the HTML attribute parser and then the JS one. esc() leaves the single quote
    // alone, which is why jsArg exists; this is the sweep proving the filter chips use it.
    // (It was a source id until C8: a file declares no source, and the one a collection has is
    // not the file's to name.)
    await loadClean(page, 'E26');
    await page.click('.tab-btn[data-tab="server"]');

    const handlers = await page.evaluate(() => {
      const W = (window as any).WIR;
      const doc = JSON.parse(JSON.stringify((window as any).globalChartData.annotationsDocument));
      doc.serverMetrics.components["x');window.__pwned=1//"] = { type: 'custom' };
      doc.serverMetrics.asserts.push({
        id: 'hostile-component', component: "x');window.__pwned=1//", query: 'lag',
        reduce: { kind: 'max' }, fail: { gt: 1 },
      });
      W.document.adopt(doc);
      W.serverKpis.reread();
      return Array.from(document.querySelectorAll('#tab-server [onclick]'))
        .map((el) => el.getAttribute('onclick') || '');
    });

    const ONE_CALL = /^WIR\.(asserts|serverKpis)\.[A-Za-z]+\((?:'(?:[^'\\]|\\.)*')?\)$/;
    // The hostile id did reach a handler — a sweep over handlers that never carried it proves nothing.
    expect(handlers.some((h) => h.startsWith('WIR.serverKpis.filterGate(') && h.includes('pwned')),
      'no filter chip carries the hostile component id').toBe(true);
    handlers.forEach((h) => {
      expect(h, `handler is not a single well-formed call: ${h}`).toMatch(ONE_CALL);
    });
    expect(await page.evaluate(() => (window as any).__pwned)).toBeUndefined();
  });

  test('[C/R] a hostile component id cannot break out of the card\'s ⋯ actions or the edit dialog (SF44)', async ({ page }) => {
    // The ⋯, Edit description / match and Delete component carry the component id into a handler,
    // and the edit dialog writes it into its heading and placeholder.
    const HOSTILE = "x');window.__pwned=1//<img src=x onerror=window.__pwned=2>";
    await loadClean(page, 'E26');
    await page.click('.tab-btn[data-tab="server"]');
    const handlers = await page.evaluate((id) => {
      const W = (window as any).WIR;
      const doc = JSON.parse(JSON.stringify((window as any).globalChartData.annotationsDocument));
      doc.serverMetrics.components[id] = { type: 'postgres', description: id };
      W.document.adopt(doc);
      W.serverKpis.reread();
      W.serverKpis.componentMenu(id);
      const out = Array.from(document.querySelectorAll('#tab-server [onclick]'))
        .map((el) => el.getAttribute('onclick') || '');
      W.serverKpis.editComponent(id);
      document.querySelectorAll('#serverDialog [onclick]').forEach((el) => out.push(el.getAttribute('onclick') || ''));
      return out;
    }, HOSTILE);
    const ONE_CALL = /^WIR\.(asserts|serverKpis)\.[A-Za-z]+\((?:'(?:[^'\\]|\\.)*')?\)$/;
    for (const action of ['componentMenu', 'editComponent', 'deleteComponent']) {
      expect(handlers.some((h) => h.startsWith(`WIR.serverKpis.${action}(`) && h.includes('pwned')),
        `no ${action} handler carries the hostile id`).toBe(true);
    }
    handlers.forEach((h) => expect(h, `handler is not a single well-formed call: ${h}`).toMatch(ONE_CALL));
    await expect(page.locator('#serverDialog .wir-slideover-head strong')).toHaveText('Edit component — ' + HOSTILE);
    expect(await page.locator('#tab-server img').count(), 'the id created an element').toBe(0);
    expect(await page.evaluate(() => (window as any).__pwned)).toBeUndefined();
  });

  test('[C/R] the server tab holds the same line, on its own fixture', async ({ page }) => {
    // A SEPARATE test, on a SEPARATE fixture, and that is the whole point of it existing.
    //
    // Widening the selector of the test above to '#tab-asserts [onclick], #tab-server [onclick]'
    // would have looked like coverage and been none: it loads E25, which declares no server
    // metrics, so the server tab would contribute zero handlers — and the
    // toBeGreaterThan(0) guard would still be satisfied by the asserts handlers alone. Every
    // server handler in the product would have gone unswept behind a green check.
    //
    // E26 is the fixture that renders the server tab, so this sweep counts its handlers itself.
    const ONE_CALL = /^WIR\.(asserts|serverKpis)\.[A-Za-z]+\((?:'(?:[^'\\]|\\.)*')?\)$/;

    await loadClean(page, 'E26');
    await page.click('.tab-btn[data-tab="server"]');

    const handlers = await page.evaluate(() =>
      Array.from(document.querySelectorAll('#tab-server [onclick]'))
        .map((el) => el.getAttribute('onclick') || ''));

    expect(handlers.length,
      'the server tab rendered no inline handlers — the sweep would pass without checking anything')
      .toBeGreaterThan(0);
    handlers.forEach((h) => {
      expect(h, `handler is not a single well-formed call: ${h}`).toMatch(ONE_CALL);
    });
  });

  test('[C/R] the server editor holds the same line, with every handler kind swept', async ({ page }) => {
    // R3-17. The sweep above reads [onclick] with the editor CLOSED, so it never saw the editor —
    // whose every control carried `oninput="WIR.serverKpis.set('field', this.value)"`, two
    // arguments in a module whose own comment said there were none. Open it, on a KPI and on a
    // new one, and sweep oninput and onchange as well.
    const ONE_CALL = /^WIR\.(asserts|serverKpis)\.[A-Za-z]+\((?:'(?:[^'\\]|\\.)*')?\)$/;
    const SELECTOR = '#tab-server [onclick], #tab-server [oninput], #tab-server [onchange]';

    await loadClean(page, 'E26');
    await page.click('.tab-btn[data-tab="server"]');

    for (const open of ['edit', 'add']) {
      const handlers = await page.evaluate(({ how, selector }) => {
        const W = (window as any).WIR.serverKpis;
        if (how === 'edit') W.edit(W._keyOf('lag-sustained'));
        else W.addKpi();
        const out: string[] = [];
        document.querySelectorAll(selector).forEach((el) => {
          ['onclick', 'oninput', 'onchange'].forEach((a) => {
            const v = el.getAttribute(a);
            if (v !== null) out.push(v);
          });
        });
        return {
          all: out,
          editor: document.querySelectorAll('#serverEditor [onclick]').length,
          controls: document.querySelectorAll('#serverEditor [data-field]').length,
        };
      }, { how: open, selector: SELECTOR });

      expect(handlers.editor, 'the editor did not open inside #tab-server').toBeGreaterThan(0);
      expect(handlers.controls, 'the editor rendered no form controls').toBeGreaterThan(5);
      handlers.all.forEach((h) => {
        expect(h, `handler is not a single well-formed call: ${h}`).toMatch(ONE_CALL);
      });
      await page.evaluate(() => (window as any).WIR.serverKpis.closeEditor());
    }

    // Edit document… opens INSIDE #tab-server since SF44, and its textarea carried
    // oninput="WIR.asserts.setDocumentText(this.value)" — the this.value shape R3-17 removed from
    // this tab. Every sweep above ran with it closed.
    await page.click('#serverEditDocumentBtn');
    await expect(page.locator('#tab-server #docEditorText')).toBeVisible();
    const doc = await page.evaluate((selector) => {
      const out: string[] = [];
      document.querySelectorAll(selector).forEach((el) => {
        ['onclick', 'oninput', 'onchange'].forEach((a) => {
          const v = el.getAttribute(a);
          if (v !== null) out.push(`${a}=${v} on #${el.id}`);
        });
      });
      return out;
    }, SELECTOR);
    expect(doc.some((h) => /closeDocument/.test(h)), 'the document editor\'s own handlers were swept').toBe(true);
    doc.forEach((h) => {
      expect(h.replace(/^on[a-z]+=| on #.*$/g, ''), `handler is not a single well-formed call: ${h}`).toMatch(ONE_CALL);
    });
    // Typing still reaches the editor: Apply follows the text, through a bound listener.
    await page.locator('#docEditorText').fill('{');
    await expect(page.locator('#docEditorProblem')).toContainText('Not valid JSON');
  });

  test('[C/R] a hostile sampler name cannot execute from the "Several" checklist', async ({ page }) => {
    await loadClean(page, 'E25');
    await switchTab(page, 'asserts');

    await page.evaluate((hostile: string) => {
      const w = window as any;
      w.globalChartData.samplers = (w.globalChartData.samplers || []).concat([
        { name: hostile, count: 1, errorCount: 0, meanResponseTime: 1 },
      ]);
    }, ENTITY_BREAKOUT);

    await page.click('#addAssertBtn');
    await page.click('.wir-seg[data-mode="samplers"]');

    const boxes = page.locator('#editorSamplers input[type="checkbox"]');
    await expect(boxes.last()).toBeVisible();
    await boxes.last().click();

    expect(await page.evaluate(() => (window as any).__pwned),
      'the injected statement executed from the checklist').toBeUndefined();
    // And the click did what it says: that sampler is now selected.
    await expect(boxes.last()).toBeChecked();
  });
});

test.describe('XSS — Error Details grouped view', () => {
  /**
   * The grouped view builds its rows with innerHTML from chartData.errorRecords,
   * so sampler / message / thread all reach an HTML sink straight from the JTL.
   * buildErrorGroups() is driven directly: it rebuilds the tbody from whatever
   * records it is handed, which makes the hostile input deterministic.
   */
  test('[C/R] buildErrorGroups escapes hostile sampler, message and thread values', async ({ page }) => {
    await loadClean(page, 'E02');
    await switchTab(page, 'errors');

    const html = await page.evaluate((p) => {
      const w = window as any;
      w.buildErrorGroups([
        { timestamp: 1752750000000, sampler: p, code: '500', message: p, thread: p },
      ]);
      const table = document.getElementById('errorGroupsTable');
      return table ? table.innerHTML : '';
    }, PAYLOAD);

    expect(html, 'grouped table should have been rebuilt — E02 must have an errorGroupsTable').not.toBe('');
    expect(html, 'buildErrorGroups must not emit a raw tag').not.toContain('<img');
    assertEscapedPayloadPresent(html, 'buildErrorGroups');

    const fired = await page.evaluate(() => (window as any).__XSS__);
    expect(fired, 'the payload must not have executed').toBeUndefined();
  });

  /**
   * The sampler name is interpolated into title="..." as well as cell text, so a
   * bare double quote would break out of the attribute if escapeHtml stopped
   * escaping it. Asserted on the parsed DOM, not the source string.
   */
  test('[C/R] a quote in the sampler name cannot break out of the title attribute', async ({ page }) => {
    await loadClean(page, 'E02');
    await switchTab(page, 'errors');

    const breakout = `" onmouseover="window.__XSS__=1`;
    const result = await page.evaluate((p) => {
      const w = window as any;
      w.buildErrorGroups([
        { timestamp: 1752750000000, sampler: p, code: '500', message: 'x', thread: 't' },
      ]);
      const cell = document.querySelector('#errorGroupsTable tbody tr[data-group-head] td:nth-child(4)');
      return {
        title: cell ? cell.getAttribute('title') : null,
        hasHandler: cell ? cell.hasAttribute('onmouseover') : false,
      };
    }, breakout);

    expect(result.title, 'the quote must stay inside the title value').toBe(breakout);
    expect(result.hasHandler, 'no event handler attribute may be synthesised').toBe(false);
  });
});
