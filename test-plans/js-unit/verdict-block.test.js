/**
 * The Summary tab's Copy Verdict (AF5): a few plain lines a reader pastes into a chat or an email,
 * built from what the page already shows and never judged again.
 *
 * Copy Summary copied the summary cards and the top five — neither verdict, because the gate strip
 * and the server card are not summary cards. The block is the header's test name and time, the
 * client verdict in the header badge's word with the gate strip's own counts (or "none declared"),
 * the server verdict in the header chip's words when server asserts are declared, the strip's "Draft
 * differs" line when it shows one, and the report's file name.
 *
 * Two halves: the text, from the facts the button collects (verdictBlockText — pure, over plain
 * facts); and the client facts, which are the Summary strip's own model (WIR.asserts.verdictFacts,
 * read through a page). That the button collects the rest off the header is the browser's question:
 * verdict-block.spec.ts.
 */
'use strict';

const assert = require('assert');
const { loadReportJs, loadReportPage, loadReportScope } = require('./harness');

const tests = [];
const test = (name, fn) => tests.push({ name, fn });
const plain = (v) => JSON.parse(JSON.stringify(v === undefined ? null : v));

const scope = loadReportScope();
const text = (facts) => scope.verdictBlockText(facts);

const FULL = {
    testName: 'E25 Asserts',
    generated: 'Generated: 16 Oct 2026, 14:02',
    asserts: { badge: 'ASSERTS FAIL', status: 'FAIL', declared: 13,
        counts: [{ n: 3, label: 'failed' }, { n: 6, label: 'passed' }, { n: 1, label: 'invalid' }] },
    server: { word: 'FAIL', label: 'Kafka', others: 1 },
    draftDiffers: true,
    fileName: 'E25-report.html'
};

test('AF5: every line, in order', () => {
    assert.strictEqual(text(FULL), [
        'E25 Asserts — Generated: 16 Oct 2026, 14:02',
        'Asserts: FAIL — 13 rules declared: 3 failed, 6 passed, 1 invalid',
        'Server: FAIL — Kafka · 1 more',
        'Draft differs — see Asserts',
        'E25-report.html'
    ].join('\n'));
});

test('AF5: the client word is the header badge\'s — WARNING, not the strip chip\'s WARN', () => {
    const facts = Object.assign({}, FULL, { asserts: Object.assign({}, FULL.asserts,
        { badge: 'ASSERTS WARNING', status: 'WARN', counts: [{ n: 1, label: 'warned' }] }) });
    assert.strictEqual(text(facts).split('\n')[1], 'Asserts: WARNING — 13 rules declared: 1 warned');
    // A run whose every rule decided nothing: the badge's NOT EVALUATED.
    const none = Object.assign({}, FULL, { asserts: Object.assign({}, FULL.asserts,
        { badge: 'ASSERTS NOT EVALUATED', status: null, declared: 1, counts: [{ n: 1, label: 'unmatched' }] }) });
    assert.strictEqual(text(none).split('\n')[1], 'Asserts: NOT EVALUATED — 1 rule declared: 1 unmatched');
    // No badge in the header (a page without metadata): the strip's own word.
    const bare = Object.assign({}, FULL, { asserts: Object.assign({}, FULL.asserts, { badge: null }) });
    assert.strictEqual(text(bare).split('\n')[1], 'Asserts: FAIL — 13 rules declared: 3 failed, 6 passed, 1 invalid');
});

test('AF5: a run with no client gate says so, and claims nothing', () => {
    const lines = text(Object.assign({}, FULL, { asserts: null, draftDiffers: false })).split('\n');
    assert.strictEqual(lines[1], 'Asserts: none declared');
    assert.ok(!lines.some((l) => /Draft differs/.test(l)), 'a draft line with no strip');
});

// ---------------------------------------------------------------------------
// The client half when the strip has no model: the badge decides
// ---------------------------------------------------------------------------

const facts = (client, badge, unavailable) => plain(scope.verdictAssertsFacts(client, badge, unavailable));
const CLIENT = { status: 'FAIL', declared: 13, counts: FULL.asserts.counts, draftDiffers: false };

test('AF5 review: a page whose payload did not arrive says the header\'s verdict, never "none declared"', () => {
    // The strip has no model (state.gate stayed null), and the Thymeleaf badge still carries Java's
    // verdict. It wrote "Asserts: none declared" under ASSERTS FAIL — the contradiction SF3 took off
    // the Asserts tab.
    for (const word of ['FAIL', 'WARNING', 'PASS', 'NOT EVALUATED']) {
        const a = facts(null, 'ASSERTS ' + word, 'The chart payload could not be loaded');
        assert.deepStrictEqual(a, { badge: 'ASSERTS ' + word, status: null, declared: null, counts: null,
            unread: true }, word);
        assert.strictEqual(text(Object.assign({}, FULL, { asserts: a, draftDiffers: false })).split('\n')[1],
            'Asserts: ' + word + ' — the rules could not be loaded on this page');
    }
    // The badge's word alone when the page has its payload and still no model to read: no count is
    // made up — "0 rules declared" — and no reason either.
    const quiet = facts(null, 'ASSERTS FAIL', null);
    assert.strictEqual(text(Object.assign({}, FULL, { asserts: quiet })).split('\n')[1], 'Asserts: FAIL');
    assert.ok(!/Draft differs/.test(text(Object.assign({}, FULL, { asserts: quiet, draftDiffers: false }))));
});

test('AF5 review: "none declared" only under a badge that says no client rule decided anything', () => {
    assert.strictEqual(facts(null, 'NO ASSERTS DECLARED', null), null);
    assert.strictEqual(facts(null, 'SERVER ASSERTS ONLY', null), null);
    assert.strictEqual(facts(null, 'NO ASSERTS DECLARED', 'The chart payload could not be loaded'), null);
    assert.strictEqual(facts(null, null, null), null, 'no header badge at all');
    assert.strictEqual(facts(null, '', null), null);
});

test('AF5 review: with the strip\'s model, its counts and the badge\'s word, as before', () => {
    assert.deepStrictEqual(facts(CLIENT, 'ASSERTS FAIL', null),
        { badge: 'ASSERTS FAIL', status: 'FAIL', declared: 13, counts: FULL.asserts.counts });
    assert.deepStrictEqual(facts(CLIENT, null, null),
        { badge: null, status: 'FAIL', declared: 13, counts: FULL.asserts.counts });
});

test('AF5: no server line unless server asserts are declared — and the unjudged states are named', () => {
    assert.ok(!/Server:/.test(text(Object.assign({}, FULL, { server: null }))));
    assert.strictEqual(text(Object.assign({}, FULL, { server: { word: 'NOT COLLECTED' } })).split('\n')[2],
        'Server: NOT COLLECTED');
    assert.strictEqual(text(Object.assign({}, FULL, { server: { word: 'REFUSED', label: null, others: 0 } })).split('\n')[2],
        'Server: REFUSED');
    assert.strictEqual(text(Object.assign({}, FULL, { server: { word: 'PASS', label: 'Orders DB', others: 0 } })).split('\n')[2],
        'Server: PASS — Orders DB');
});

test('AF5: at most six lines, none empty, and plain text — nothing escaped for HTML', () => {
    const facts = Object.assign({}, FULL, { testName: 'Load <b>&</b> "soak"', fileName: 'a <b>.html' });
    const lines = text(facts).split('\n');
    assert.ok(lines.length <= 6, lines.length + ' lines');
    assert.ok(lines.every((l) => l.length > 0), JSON.stringify(lines));
    assert.strictEqual(lines[0], 'Load <b>&</b> "soak" — Generated: 16 Oct 2026, 14:02');
    assert.strictEqual(lines[lines.length - 1], 'a <b>.html');
    // What the page could not read is left out, never written as "null".
    const sparse = text({ testName: 'T', generated: null, asserts: null, server: null, draftDiffers: false, fileName: '' });
    assert.strictEqual(sparse, 'T\nAsserts: none declared');
});

// ---------------------------------------------------------------------------
// The feedback both copy buttons share
// ---------------------------------------------------------------------------

test('AF5 review: a second click inside the feedback\'s two seconds still brings the label back', () => {
    // showCopyFeedback read the label to restore at each click, so a second one read "Copied!" and
    // the button said so for good — Copy Summary's since it was written, Copy Verdict's from birth.
    const own = loadReportScope();
    const timers = [];
    own.setTimeout = (fn) => { timers.push({ fn, live: true }); return timers.length; };
    own.clearTimeout = (id) => { if (timers[id - 1]) timers[id - 1].live = false; };
    const btn = { textContent: 'Copy Verdict' };
    own.showCopyFeedback('Copied!', btn);
    own.showCopyFeedback('Copied!', btn);
    assert.strictEqual(btn.textContent, 'Copied!');
    timers.filter((t) => t.live).forEach((t) => t.fn());
    assert.strictEqual(btn.textContent, 'Copy Verdict');
    // And once settled, the next click starts over from the label.
    own.showCopyFeedback('Copied!', btn);
    timers.filter((t) => t.live).forEach((t) => { t.live = false; t.fn(); });
    assert.strictEqual(btn.textContent, 'Copy Verdict');
});

// ---------------------------------------------------------------------------
// The client facts are the Summary strip's
// ---------------------------------------------------------------------------

const A0 = loadReportJs().asserts;
const STRIP = [
    { id: 'a-p95', target: { sampler: 'A' }, metric: 'p95', fail: { gt: 10 } },
    { id: 'a-mean', target: { sampler: 'A' }, metric: 'mean', fail: { gt: 100 } },
    { id: 'gone', target: { sampler: 'Nope' }, metric: 'p95', fail: { gt: 100 } }
];

function javaPage(entries) {
    const chartData = {
        runId: 'verdict-block',
        annotationsDocument: JSON.parse(JSON.stringify({ assertions: entries })),
        samplers: [{ name: 'A', count: 10, p95: 50, mean: 30, throughput: 5 }],
        overall: { totalSamples: 10, p95: 50, mean: 30, throughput: 5 }
    };
    const judged = A0.evaluate(entries.map((r, i) => A0._ruleFromJson(r, i)), chartData);
    chartData.assertions = plain(judged.rules);
    chartData.gate = plain(judged.gate);
    return loadReportPage(chartData);
}

test('AF5: the client facts are the strip\'s counts, as gated, and its draft line', () => {
    const W = javaPage(STRIP);
    const strip = plain(W.asserts._summaryStrip());
    assert.deepStrictEqual(plain(W.asserts.verdictFacts()), {
        status: 'FAIL', declared: 3, counts: strip.counts, draftDiffers: false
    });
    // A draft the strip calls out moves no count: the block speaks for the run as gated.
    W.asserts._retune('a-p95', 1000);
    assert.deepStrictEqual(plain(W.asserts.verdictFacts()), {
        status: 'FAIL', declared: 3, counts: strip.counts, draftDiffers: true
    });
});

test('AF5: a run that declared no client rule has no client facts', () => {
    const W = loadReportPage({ runId: 'verdict-block-none', samplers: [], overall: {} });
    assert.strictEqual(W.asserts.verdictFacts(), null);
});

// ---------------------------------------------------------------------------

let failed = 0;
for (const t of tests) {
    try {
        t.fn();
        console.log('  ok   ' + t.name);
    } catch (e) {
        failed++;
        console.log('  FAIL ' + t.name);
        console.log('       ' + (e && e.message ? e.message.split('\n')[0] : e));
    }
}
console.log('\n' + (tests.length - failed) + '/' + tests.length + ' passed');
process.exit(failed === 0 ? 0 : 1);
