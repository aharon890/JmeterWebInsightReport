/**
 * Load the shipped report.js in Node, with no build step and no browser.
 *
 * Why this exists: report.js is 11,000+ lines and roughly 2,000 of them inside `WIR.asserts`
 * are pure — parsers, formatters, comparators, the evaluator core. Until now the cheapest test
 * for any of them was to boot Chromium against a 1.7 MB generated fixture, so in practice they
 * had no tests at all, and the defects that reached production were exactly the ones a pure
 * function test would have caught in a millisecond: a chip class built with the wrong
 * separator, a band parser that trusted the first key of an object, a rounding rule that could
 * not express 0.0015.
 *
 * Why it is only thirty lines: the file was already nearly loadable. The single piece of
 * module-level DOM contact in all 11,341 lines is one `document.addEventListener` at the
 * bottom, and `WIR.asserts` already exports test seams for the browser suite. So a pair of
 * stubs is enough — no bundler, no module split, no change to the product. The self-contained
 * single-file promise is untouched, which is the reason a module split was rejected.
 *
 * What this is NOT: a replacement for the Playwright suite. Anything that renders, measures or
 * responds to a click still needs a browser. This covers the half that never should have needed
 * one.
 */
'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const REPORT_JS = path.resolve(__dirname,
    '../../jmeter-web-insight-report/src/main/resources/static/js/report.js');

/**
 * A DOM stub that is honest about what it is.
 *
 * Every method throws unless it is one the file calls at load time. That is deliberate: a
 * silent no-op stub would let a test quietly exercise a code path whose DOM effects vanished,
 * and pass while the real page did something else entirely. If a test reaches for the DOM, it
 * belongs in Playwright, and the exception says so by name.
 */
function stubDocument() {
    const refuse = (name) => () => {
        throw new Error(
            'report.js reached document.' + name + '() under the Node harness. Anything that '
            + 'touches the DOM must be tested in Playwright against a real report — see '
            + 'test-plans/playwright/. Only pure functions belong here.');
    };
    return {
        // The one call made at load time, by the DOMContentLoaded registration at the bottom
        // of the file. Swallowed rather than refused, because refusing it would mean the file
        // could not be loaded at all.
        addEventListener() { },
        removeEventListener() { },
        getElementById: refuse('getElementById'),
        querySelector: refuse('querySelector'),
        querySelectorAll: refuse('querySelectorAll'),
        createElement: refuse('createElement'),
        body: null
    };
}

/**
 * Evaluate report.js and hand back its `WIR` namespace.
 *
 * Loaded through `vm` in a fresh context per call so one test cannot leave state behind for the
 * next — `WIR.asserts` keeps a module-level `state` object, and a suite that shared it would
 * pass or fail depending on file order.
 *
 * `globalChartData` is the one page global a pure function reads (the Asserts tab's metric
 * vocabulary, for a rule's unit). Absent by default — a test that needs it hands in what it reads,
 * and nothing more; it is not a page.
 *
 * `options.locale` is the reader's locale: the default a `toLocaleString()` with no locale falls
 * back to inside this context, as a browser set to that language would give it. Without it the
 * context has this process's, which on every machine this runs on is English — so a formatter that
 * took the reader's locale passed every test here and wrote "fail > 3,6 s" in Germany.
 */
function loadReportJs(globalChartData, options) {
    const source = fs.readFileSync(REPORT_JS, 'utf8');
    const sandbox = {
        window: {},
        document: stubDocument(),
        navigator: { userAgent: 'node' },
        console,
        setTimeout,
        clearTimeout,
        localStorage: null,
        // The report inlines ECharts and reads it only when drawing. Present so a reference
        // does not throw at load time; any use of it is a browser concern.
        echarts: undefined
    };
    sandbox.globalThis = sandbox;
    vm.createContext(sandbox);
    if (options && options.locale) {
        /* The context's own Number, so no other test in this process sees a German default. */
        vm.runInContext('(function (reader) {'
            + ' var own = Number.prototype.toLocaleString;'
            + ' Number.prototype.toLocaleString = function (locales, opts) {'
            + '   return own.call(this, locales === undefined ? reader : locales, opts); };'
            + ' })(' + JSON.stringify(options.locale) + ');', sandbox);
    }
    vm.runInContext(source, sandbox, { filename: REPORT_JS });
    if (globalChartData !== undefined) sandbox.globalChartData = globalChartData;

    if (!sandbox.window.WIR || !sandbox.window.WIR.asserts) {
        throw new Error('report.js loaded but exposed no WIR.asserts — the harness is stale');
    }
    /* What a page is handed as chartData.serverCatalogue: the shipped catalogue files. */
    if (sandbox.window.WIR.serverKpis && sandbox.window.WIR.serverKpis.setCatalogue) {
        sandbox.window.WIR.serverKpis.setCatalogue(loadCatalogue());
    }
    return sandbox.window.WIR;
}

/**
 * Evaluate report.js and hand back its whole global scope — its top-level functions as well as
 * `WIR` — for the few top-level helpers that are logic over objects a caller hands in, never the
 * document: the sampler table's companion-row walk takes rows and a tbody. The document is the
 * refusing stub above, so a helper that does reach for the DOM still fails here, by name.
 */
function loadReportScope() {
    const sandbox = {
        window: {},
        document: stubDocument(),
        navigator: { userAgent: 'node' },
        console,
        setTimeout,
        clearTimeout,
        localStorage: null,
        echarts: undefined
    };
    sandbox.globalThis = sandbox;
    vm.createContext(sandbox);
    vm.runInContext(fs.readFileSync(REPORT_JS, 'utf8'), sandbox, { filename: REPORT_JS });
    return sandbox;
}

const CATALOGUE_DIR = path.resolve(__dirname,
    '../../jmeter-report-core/src/main/resources/com/jmeterwebinsightreport/core/servermetrics/catalogue');

/**
 * The built-in catalogue as Catalogue.pageJson() ships it — {order, types} over the same resource
 * files Java reads, so a catalogue metric resolves to one query on both sides from one copy.
 */
function loadCatalogue() {
    const index = JSON.parse(fs.readFileSync(path.join(CATALOGUE_DIR, 'index.json'), 'utf8'));
    const types = {};
    index.types.forEach((key) => {
        types[key] = JSON.parse(fs.readFileSync(path.join(CATALOGUE_DIR, key + '.json'), 'utf8'));
    });
    return { order: index.types.slice(), types };
}

/**
 * A page with nothing on it.
 *
 * The second loader, for the one caller that needs the modules' LIFECYCLE rather than their pure
 * helpers: run-config-tests.sh C38 drives init, an edit and a Save exactly as the report does, and
 * every one of those ends in a render. A render here finds no element to draw into — every module
 * already guards its host (`if (!host) return`), because a tab can be absent from a page — so the
 * state changes and the drawing is skipped. createElement hands back a detached element that keeps
 * nothing (showToast builds one), and body accepts it.
 *
 * Deliberately NOT the stub above. That one refuses the DOM by name so a unit test cannot pass on a
 * code path whose DOM effects vanished. Here vanishing is the point, and what is asserted is what
 * the page DECIDED — verdicts, gates, the document a Download writes — never what it drew.
 */
function emptyPageDocument() {
    const nothing = () => null;
    const none = () => [];
    const detached = () => ({
        style: {},
        dataset: {},
        classList: { add() { }, remove() { }, toggle() { }, contains() { return false; } },
        setAttribute() { },
        getAttribute: nothing,
        removeAttribute() { },
        appendChild(child) { return child; },
        removeChild(child) { return child; },
        addEventListener() { },
        removeEventListener() { },
        querySelector: nothing,
        querySelectorAll: none,
        // Read by timers that outlive the call that set them (showUndoToast's hide asks whether
        // the pointer or focus is still on the toast): a detached element has neither.
        matches() { return false; },
        contains() { return false; },
        // Focus is a drawing effect like any other: Delete puts it on the Undo toast it just built
        // (SF29), and a detached element has nowhere to put it.
        focus() { },
        blur() { },
        click() { },
        remove() { }
    });
    return {
        addEventListener() { },
        removeEventListener() { },
        getElementById: nothing,
        querySelector: nothing,
        querySelectorAll: none,
        getElementsByClassName: none,
        getElementsByTagName: none,
        createElement: detached,
        body: detached(),
        documentElement: detached()
    };
}

/**
 * Load report.js the way the report page runs it, over one report's chartData: both authoring
 * modules initialised, then the document lifecycle — the tail of initCharts, in its order. Returns
 * the page's `WIR`.
 *
 * `script` is the report.js to run — a generated report's own inlined copy (readReportPage), so
 * the page under test is the one the deployed plugin wrote. The working tree's when omitted.
 *
 * No localStorage, so nothing is restored and nothing persists: the page starts as the file it
 * shipped with, every time.
 *
 * `options.toasts`, when given, is an array every toast the page shows is pushed onto, as its text
 * (showToast's message) — what a Save or a drag SAID, which is a decision, not a drawing (L17).
 */
function loadReportPage(chartData, script, options) {
    const source = script === undefined ? fs.readFileSync(REPORT_JS, 'utf8') : script;
    const sandbox = {
        /* A window that takes listeners and never fires one, as emptyPageDocument swallows them: a
           chart wired up here (the drag handles bind one page-level resize listener;
           asserts-row-keys.test.js attaches a chart) is driven by its own events, not by a window
           that is never resized. */
        window: { addEventListener() { }, removeEventListener() { } },
        document: emptyPageDocument(),
        navigator: { userAgent: 'node' },
        console,
        setTimeout,
        clearTimeout,
        localStorage: null,
        echarts: undefined
    };
    sandbox.globalThis = sandbox;
    vm.createContext(sandbox);
    vm.runInContext(source, sandbox, { filename: REPORT_JS });
    const WIR = sandbox.window.WIR;
    if (!WIR || !WIR.asserts || !WIR.serverKpis || !WIR.document) {
        throw new Error('report.js loaded but exposed no WIR.asserts/serverKpis/document');
    }
    if (options && Array.isArray(options.toasts)) {
        /* The page's one showToast is a script-level function, so every module resolves it on the
           context's global at call time: wrapping it there hears every caller. */
        const own = sandbox.showToast;
        const heard = options.toasts;
        sandbox.showToast = function (message) {
            heard.push(String(message));
            return own.apply(this, arguments);
        };
    }
    /* initCharts' first act, and the global every module reads the run from. */
    sandbox.globalChartData = chartData;
    sandbox.chartData = chartData;
    WIR.asserts.init(chartData);
    WIR.serverKpis.init(chartData);
    const restored = WIR.document.start();
    WIR.serverKpis.rerender();
    if (restored) WIR.asserts.rerender();
    return WIR;
}

/**
 * What a generated report runs: the chartData it carries inline — the object the template hands to
 * initCharts — and the report.js it inlined.
 *
 * Thymeleaf writes chartData as one JS string literal holding JSON, so the literal is read as
 * JavaScript and its value parsed. A report whose payload went to a sidecar (externalData) carries
 * none inline, and says so rather than returning the template's '{}' placeholder. The script is
 * the `<script>` block that defines WIR.serverKpis — ECharts is inlined in another one.
 */
function readReportPage(htmlPath) {
    const html = fs.readFileSync(htmlPath, 'utf8');
    const m = html.match(/var chartData = ("(?:[^"\\]|\\.)*");/);
    if (!m) throw new Error('no inline chartData in ' + htmlPath);
    const chartData = JSON.parse(vm.runInNewContext(m[1]));
    if (!chartData || !chartData.runId) {
        throw new Error(htmlPath + ' carries its chart payload in a sidecar (or none at all); '
            + 'generate it below the external-data threshold');
    }
    let script = null;
    for (let at = html.indexOf('<script>'); at >= 0 && script === null;
        at = html.indexOf('<script>', at + 1)) {
        const end = html.indexOf('</script>', at);
        if (end < 0) break;
        const body = html.slice(at + '<script>'.length, end);
        if (body.indexOf('WIR.serverKpis = (function') >= 0) script = body;
    }
    if (script === null) throw new Error('no inlined report.js in ' + htmlPath);
    return { chartData, script };
}

module.exports = { loadCatalogue, loadReportJs, loadReportPage, loadReportScope, readReportPage, REPORT_JS };
