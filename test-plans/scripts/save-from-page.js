/**
 * The M6 promise, end to end: what a Save writes gates the next `jmeter -g` exactly as the page
 * showed it. run-config-tests.sh C38 is the only caller.
 *
 *   node save-from-page.js save    <report.html> <outDir>
 *   node save-from-page.js noop    <report.html> <outDir>
 *   node save-from-page.js compare <page-verdicts.json> <ci.json>
 *
 * `save` loads the shipped report.js over the report's own chartData — both authoring modules and
 * the document lifecycle, as initCharts runs them (harness.loadReportPage) — and writes three cases,
 * each a document plus the verdicts the page showed beside it:
 *
 *   <outDir>/noop/    the document a Download writes on an untouched page
 *   <outDir>/edited/  the document after a scripted session, every edit made through the handler
 *                     the UI calls for it:
 *                       - client  load:unreachable dragged from lt 100000 to lt 10 (its dependant
 *                                 needs-load stops being stood down and is judged)
 *                       - server  lag-spike's fail band dragged to 899.6, which the commit rounds
 *                                 to 900 — across lag's measured 900, so a page that judged one
 *                                 value and saved the other would read FAIL where Java reads WARN
 *                                 lag-p50-warns' warn band retyped in the editor
 *                                 cpu-headroom parked with the row's enabled toggle
 *                                 lag-avg added through + Add KPI on a collected series
 *   <outDir>/seeded/  a fresh page whose cpu asserts were removed, so Generate from this run has a
 *                     collected series to seed: the seed's description, rounded threshold and
 *                     default component — all written by seedKpi, nowhere else — reach the judge
 *
 * `noop` writes the first of those alone, into <outDir> itself, for a page that is not E26's: the
 * scripted session needs E26's asserts, and an untouched Download needs nothing. C38 runs it over
 * G3's committed input, six of whose seven rules Java refuses (healthy-control is the PASS control)
 * — the file a no-op Save once quietly repaired (C-1: an unreadable band dropped, so the next run
 * judged half-readable-band PASS).
 *
 * The verdicts are read from the same seams the browser specs use — `_board()` per row,
 * `_gateStrip()` per server gate, and the client gate as the Asserts tab shows it (the draft gate
 * once there is an edit, the baked one before). Every edit is checked to have LANDED on the page
 * before anything is written: an edit that silently did nothing would leave two documents that
 * agree with Java for the uninteresting reason that nobody changed anything.
 *
 * `compare` diffs one verdicts file against the CI/CD JSON of the `jmeter -g` run over its
 * document, field by field, and exits 1 naming every difference. The page and the file are matched
 * by POSITION — serverAsserts[i] is the verdict on the document's i-th assert, which is how the board
 * looks a baked verdict up too, and two KPIs may share an id.
 */
'use strict';

const fs = require('fs');
const path = require('path');
const { loadReportPage, readReportPage } = require('../js-unit/harness');

/* The CI/CD JSON writes a server actual at four places or more (CiCdJsonSerializer.serverActualOf,
   S2), and the collector writes coverage at 4dp; the page is given Java's own double for the actual
   (F7) and recomputes the rest unrounded. So the page and the JSON agree within the wire's
   rounding, not exactly. */
const WIRE_TOLERANCE = 5e-5 + 1e-9;

function usage() {
    console.error('usage: node save-from-page.js save <report.html> <outDir>\n'
        + '       node save-from-page.js noop <report.html> <outDir>\n'
        + '       node save-from-page.js compare <page-verdicts.json> <ci.json>');
    process.exit(2);
}

/* ---- save ------------------------------------------------------------------------------- */

/** What the page shows, in the shape compare reads. */
function verdictsOf(WIR) {
    const A = WIR.asserts;
    const SK = WIR.serverKpis;
    const shownGate = A._state.draftGate || A._state.gate || null;
    return {
        kpis: SK._board(),
        gates: SK._gateStrip().filter((g) => g.declared).map((g) => ({
            id: g.id, would: g.would, unknown: g.unknown, summary: g.summary
        })),
        client: {
            gate: shownGate,
            rules: A.currentRules().map((r) => ({
                id: r.id, status: r.status || null, notJudged: r.notJudged || null,
                reason: r.notJudgedDetail || null
            }))
        }
    };
}

function writeCase(dir, WIR, edits) {
    fs.mkdirSync(dir, { recursive: true });
    /* Exactly the text the Download button writes (downloadDocument → documentText). */
    fs.writeFileSync(path.join(dir, 'report-annotations.json'), WIR.asserts._documentText());
    const verdicts = verdictsOf(WIR);
    verdicts.edits = edits;
    fs.writeFileSync(path.join(dir, 'page-verdicts.json'), JSON.stringify(verdicts, null, 2) + '\n');
    return verdicts;
}

function rowOf(WIR, id) {
    return WIR.serverKpis._board().find((r) => r.id === id) || null;
}

function save(reportHtml, outDir) {
    /* The report's OWN report.js, not the working tree's: the page under test is the one the
       deployed plugin wrote, so both halves of the comparison come from the same JAR. */
    const page = readReportPage(reportHtml);
    /* The chartData as shipped, for the seeded case's fresh page: nothing the first session does
       may reach it, whatever that session's page writes into the object it was handed. */
    const pristine = JSON.stringify(page.chartData);
    const WIR = loadReportPage(page.chartData, page.script);
    const SK = WIR.serverKpis;
    const A = WIR.asserts;
    const first = SK._board()[0];
    if (!first || !('status' in first) || !SK._gateStrip().every((g) => 'summary' in g)) {
        console.error('[C38] this report\'s report.js has no verdict fields on _board()/_gateStrip()'
            + ' — it was written by a plugin older than C38. Rebuild and redeploy.');
        process.exit(1);
    }
    const problems = [];
    const expectRow = (id, display, why) => {
        const row = rowOf(WIR, id);
        const got = row ? row.display : 'no such row';
        if (got !== display) problems.push(`${why}: ${id} shows ${got}, expected ${display}`);
    };

    if (WIR.document.hasDrafts()) problems.push('an untouched page already reports unsaved changes');
    writeCase(path.join(outDir, 'noop'), WIR, []);

    const keyOf = (id) => {
        const key = SK._keyOf(id);
        if (!key) problems.push(`the page has no assert called ${id} — E26 changed under C38`);
        return key;
    };
    const edits = [];

    // Client: the drag commit, as a handle released at 10 on a throughput chart would call it.
    A._retune('load:unreachable', 10, 'fail');
    edits.push('client load:unreachable fail lt 100000 -> lt 10 (drag)');
    const loadRule = A.currentRules().find((r) => r.id === 'load:unreachable');
    if (!loadRule || !loadRule.fail || loadRule.fail.value !== 10 || loadRule.status !== 'PASS') {
        problems.push('the client drag did not land: load:unreachable is '
            + JSON.stringify(loadRule && { fail: loadRule.fail, status: loadRule.status }));
    }
    expectRow('needs-load', 'FAIL', 'a client retune must re-decide the server assert that requires it');

    // Server: a drag, rounded by the commit exactly as a pointer value would be. 899.6 and the 900
    // it rounds to sit on either side of lag's measured 900: judged at 899.6 the row would FAIL,
    // judged at the saved 900 it WARNs (review:F — 1000.4 read WARN either way, so a page that
    // judged one value and saved the other passed this comparison).
    SK._commitDrag(keyOf('lag-spike'), 'fail', 899.6);
    edits.push('server lag-spike fail gt 500 -> gt 899.6 (drag; rounds to 900, the measured maximum)');
    expectRow('lag-spike', 'WARN', 'the dragged fail band, judged at the value it was rounded to');
    const dragged = rowOf(WIR, 'lag-spike');
    if (!dragged || dragged.actual !== 900) {
        problems.push('lag-spike no longer measures 900, so the rounded drag decides nothing: '
            + JSON.stringify(dragged && dragged.actual));
    }

    // Server: the editor, on an existing row.
    SK.edit(keyOf('lag-p50-warns'));
    SK.set('warn.value', '150');
    SK.applyEditor();
    edits.push('server lag-p50-warns warn gt 100 -> gt 150 (editor)');
    expectRow('lag-p50-warns', 'PASS', 'the retyped warn band');

    // Server: the row's enabled toggle.
    SK.toggleEnabled(keyOf('cpu-headroom'));
    edits.push('server cpu-headroom parked (enabled toggle)');
    expectRow('cpu-headroom', 'DISABLED', 'the parked assert');

    // Server: + Add KPI, on a series the payload collected.
    SK.addKpi();
    [['id', 'lag-avg'], ['component', 'kafka'], ['query', 'lag'], ['unit', 'msgs'], ['kind', 'avg'],
        ['warn.value', '1'], ['fail.value', '123.45']]
        .forEach(([field, value]) => SK.set(field, value));
    SK.applyEditor();
    edits.push('server lag-avg added (editor): avg of lag, warn gt 1, fail gt 123.45, component kafka');
    expectRow('lag-avg', 'FAIL', 'the added assert');

    if (!WIR.document.hasDrafts()) problems.push('the scripted edits left the page with no unsaved changes');
    const edited = writeCase(path.join(outDir, 'edited'), WIR, edits);

    // Seeded (review:F): a fresh page, Generate from this run over a series nothing claims. The
    // edits above never go through seedPlan/seedKpi, so a seed's own spelling — the description,
    // roundThreshold(1.5 x the observed maximum), the first declared component — reached no judge.
    const fresh = loadReportPage(JSON.parse(pristine), page.script);
    const FSK = fresh.serverKpis;
    const seededEdits = [];
    ['cpu-headroom', 'cpu-per-rps', 'needs-load', 'parked', 'typo-reducer'].forEach((id) => {
        const key = FSK._keyOf(id);
        if (!key) problems.push(`the page has no assert called ${id} — E26 changed under C38`);
        else FSK.remove(key);
    });
    seededEdits.push('server every assert on cpu removed (cpu-headroom, cpu-per-rps, needs-load, parked, typo-reducer)');
    // A series is its component and its query (backends plan §8b): kafka's cpu and db's cpu are
    // two series, freed together, and each is seeded onto its own component.
    const seeds = FSK._seedable();
    const offered = seeds.map((k) => k.component + ':' + k.query).sort();
    if (JSON.stringify(offered) !== JSON.stringify(['db:cpu', 'kafka:cpu'])) {
        problems.push('Generate from this run should offer exactly the freed cpu series, one per component, offered: '
            + JSON.stringify(offered));
    }
    FSK.generate();
    seededEdits.push('server Generate from this run: one seed on cpu per component (kafka, db)');
    const seededDocs = JSON.parse(fresh.asserts._documentText()).serverMetrics.asserts
        .filter((k) => k.query === 'cpu');
    if (JSON.stringify(seededDocs.map((k) => k.component).sort()) !== JSON.stringify(['db', 'kafka'])) {
        problems.push('the seeds were not written onto their own components: ' + JSON.stringify(seededDocs));
    }
    seededDocs.forEach((doc) => {
        const row = rowOf(fresh, doc.id);
        if (!row || row.display !== 'PASS' || !row.live) {
            problems.push(`the seed ${doc.id} is not a live PASS on the run it was taken from: `
                + JSON.stringify(row && { display: row.display, live: row.live }));
        }
        if (!/seeded from the observed maximum/.test(doc.description || '')) {
            problems.push('the seed was not written as seedKpi writes one: ' + JSON.stringify(doc));
        }
    });
    writeCase(path.join(outDir, 'seeded'), fresh, seededEdits);

    /* Every row must carry a verdict — a withheld or stale one cannot be compared, and a
       comparison that skipped it would be weaker than it reads. */
    ['noop', 'edited', 'seeded'].forEach((c) => {
        const v = JSON.parse(fs.readFileSync(path.join(outDir, c, 'page-verdicts.json'), 'utf8'));
        v.kpis.filter((r) => !r.status).forEach((r) => problems.push(
            `${c}: the page decided nothing for ${r.id} (${r.display}), so it cannot be compared`));
    });
    const db = edited.gates.find((g) => g.id === 'db');
    if (!db || db.would !== 'PASS') problems.push('the db gate does not read PASS after the edits');

    if (problems.length) {
        problems.forEach((p) => console.error('[C38] ' + p));
        process.exit(1);
    }
    console.log(`[C38] saved noop, edited (${edits.length} edits) and seeded documents under ${outDir}`);
}

/* ---- noop ------------------------------------------------------------------------------- */

/** An untouched page's Download and the verdicts beside it, for any report with an inline payload. */
function noop(reportHtml, outDir) {
    const page = readReportPage(reportHtml);
    const WIR = loadReportPage(page.chartData, page.script);
    if (WIR.document.hasDrafts()) {
        console.error('[C38] an untouched page already reports unsaved changes');
        process.exit(1);
    }
    writeCase(outDir, WIR, []);
    console.log(`[C38] saved an untouched page's Download under ${outDir}`);
}

/* ---- compare ---------------------------------------------------------------------------- */

const NOT_JUDGED_KEYS = ['total', 'disabled', 'noMatch', 'noValue', 'invalidRule', 'sourceFailed',
    'lowCoverage', 'loadNotAchieved'];
const COUNT_KEYS = ['declared', 'evaluated', 'passed', 'warned', 'failed'];

function compare(pageFile, ciFile) {
    const page = JSON.parse(fs.readFileSync(pageFile, 'utf8'));
    const ci = JSON.parse(fs.readFileSync(ciFile, 'utf8'));
    const diffs = [];
    const same = (what, mine, theirs) => {
        if (mine !== theirs) diffs.push(`${what}: page ${JSON.stringify(mine)}, jmeter -g ${JSON.stringify(theirs)}`);
    };
    const near = (what, mine, theirs) => {
        if (mine === null || theirs === null || mine === undefined || theirs === undefined) {
            same(what, mine === undefined ? null : mine, theirs === undefined ? null : theirs);
        } else if (Math.abs(mine - theirs) > WIRE_TOLERANCE) {
            diffs.push(`${what}: page ${mine}, jmeter -g ${theirs}`);
        }
    };
    /* The fold says null for "decided nothing"; the wire says NOT_EVALUATED. */
    const gateStatus = (s) => (s === null || s === undefined ? 'NOT_EVALUATED' : s);
    const summary = (what, mine, theirs) => {
        if (!mine || !theirs) {
            same(what + ' present', !!mine, !!theirs);
            return;
        }
        same(what + '.status', gateStatus(mine.status), gateStatus(theirs.status));
        COUNT_KEYS.forEach((k) => same(`${what}.${k}`, mine[k], theirs[k]));
        NOT_JUDGED_KEYS.forEach((k) => same(`${what}.notJudged.${k}`,
            (mine.notJudged || {})[k], (theirs.notJudged || {})[k]));
    };

    // Server asserts, by position — `serverAsserts` on the wire since D25.
    const kpis = ci.serverAsserts || [];
    same('server assert count', page.kpis.length, kpis.length);
    page.kpis.forEach((mine, i) => {
        const theirs = kpis[i];
        if (!theirs) return;
        const at = `serverAsserts[${i}] ${mine.id}`;
        same(at + ' id', mine.id, theirs.id);
        same(at + ' status', mine.status, theirs.status);
        same(at + ' notJudged', mine.notJudged, theirs.notJudged || null);
        same(at + ' reason', mine.detail, theirs.reason || null);
        near(at + ' actual', mine.actual, theirs.actual);
        near(at + ' coverage', mine.coverage, theirs.coverage);
        same(at + ' instances', mine.instances, theirs.instances);
        const breached = theirs.breached
            ? { op: Object.keys(theirs.breached)[0], value: theirs.breached[Object.keys(theirs.breached)[0]] }
            : null;
        same(at + ' breached.op', mine.breached ? mine.breached.op : null, breached ? breached.op : null);
        near(at + ' breached.value', mine.breached ? mine.breached.value : null,
            breached ? breached.value : null);
    });

    // Server gates, by id — the DECLARED ones. The CI/CD JSON also carries a reserved "(no gate)"
    // entry over the asserts that named no declared gate (ServerGateEvaluation.NO_GATE_ID, A1-6),
    // and the page's gate strip deliberately has no badge for it: it lists what the document
    // declares. Its asserts are still compared one by one above, by position.
    const NO_GATE_ID = '(no gate)';
    const gates = (ci.serverComponents || []).filter((g) => g.id !== NO_GATE_ID);
    same('server gate ids', page.gates.map((g) => g.id).join(','), gates.map((g) => g.id).join(','));
    page.gates.forEach((mine) => {
        const theirs = gates.find((g) => g.id === mine.id);
        if (!theirs) return;
        if (mine.unknown) diffs.push(`serverComponents ${mine.id}: the page cannot say what it would read`);
        same(`serverComponents ${mine.id} status (the badge's "if saved")`, gateStatus(mine.would),
            gateStatus(theirs.status));
        summary(`serverComponents ${mine.id}`, mine.summary, theirs);
    });

    // The client gate the Asserts tab shows, and each rule.
    summary('gate', page.client.gate, ci.gate || null);
    const rules = ci.assertions || [];
    same('client assert count', page.client.rules.length, rules.length);
    page.client.rules.forEach((mine, i) => {
        const theirs = rules[i];
        if (!theirs) return;
        const at = `assertions[${i}] ${mine.id}`;
        same(at + ' id', mine.id, theirs.id);
        same(at + ' status', mine.status, theirs.status);
        same(at + ' notJudged', mine.notJudged, theirs.notJudged || null);
        /* The sentence too, for a rule its declaration made unrunnable: a Save that drops a refused
           band from a rule the next run then refuses on other grounds leaves it INVALID_RULE and
           changes only this (G3's retired-outside-operator, refused for having no band once a Save
           took its one). Only there, because the wire writes a phrase for every other NOT_JUDGED
           reason where the page holds no sentence. */
        if (mine.notJudged === 'INVALID_RULE') same(at + ' reason', mine.reason, theirs.reason || null);
    });

    const label = path.basename(path.dirname(pageFile));
    if (diffs.length) {
        console.error(`[C38] ${label}: the page and jmeter -g disagree on ${diffs.length} field(s):`);
        diffs.forEach((d) => console.error('  ' + d));
        process.exit(1);
    }
    console.log(`[C38] ${label}: ${page.kpis.length} server asserts, ${page.gates.length} server gates, `
        + `the client gate and ${page.client.rules.length} client rules agree field by field`);
}

const [mode, a, b] = process.argv.slice(2);
if (mode === 'save' && a && b) save(a, b);
else if (mode === 'noop' && a && b) noop(a, b);
else if (mode === 'compare' && a && b) compare(a, b);
else usage();
