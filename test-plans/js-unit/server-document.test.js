/**
 * Unit tests for what a Save and an Apply do to the serverMetrics block (WIR.serverKpis as a
 * WIR.document slice).
 *
 * Two promises, both about the FILE rather than the screen:
 *
 *   - a Save never quietly repairs a KPI Java refuses. The parsed model has nowhere to hold a
 *     value it could not read, so kpiToJson writes those back verbatim (C-1). It used to drop an
 *     unreadable fail band beside a readable warn band, which turned an INVALID_RULE — a config
 *     error and exit 1 — into a KPI judged on warn alone that can never FAIL.
 *   - a document is checked by every slice before any of them changes (R1-8). `"asserts": {}` (then `"kpis"`) used
 *     to pass the Edit document… check, adopt the document, replace the rules, and only then
 *     throw inside the Server tab's read.
 *   - a flag (enabled, failsBuild, excludeRampUp) means what Jackson says it means. `"enabled": 0`
 *     parks a KPI in Java; the browser read it as enabled and a no-op save wrote `true`. The
 *     verdict half is in server-parity-vectors.json, so Java, node and the browser all run it.
 *
 * The shapes in the R1-8 half were each run through AnnotationLoader + ServerMetricsMapper
 * (2026-10-02): "refused" below means Jackson refused the whole file, "accepted" means it loaded.
 *
 * The rule for what belongs here is asserts-pure.test.js's: no DOM, no fixture, no browser.
 */
'use strict';

const assert = require('assert');
const { loadReportJs } = require('./harness');

const WIR = loadReportJs();
const SK = WIR.serverKpis;
const DOC = WIR.document;

if (!SK || !SK._kpiToJson || !DOC || !DOC.check || !DOC.replace) {
    throw new Error('report.js exposed no WIR.document.check/replace or WIR.serverKpis._kpiToJson'
        + ' — the harness is stale');
}

const tests = [];
const test = (name, fn) => tests.push({ name, fn });

/** Strip the vm realm off a value before comparing it (see asserts-pure.test.js). */
const plain = (v) => JSON.parse(JSON.stringify(v === undefined ? null : v));

/* One custom component: every assert here writes its own query (C9). */
const COMPONENTS = { c: { type: 'custom' } };

/** What ServerMetricsMapper says about `"fail": {"gtt": 90}`. */
const GTT_REFUSAL = "fail operator 'gtt' is not one of gt, gte, lt, lte — so this server assert "
    + 'declares a limit it cannot apply, and would report PASS without ever testing fail';

function blockWith(kpi) {
    return { components: COMPONENTS, asserts: [kpi] };
}

/** Make `block` the document's serverMetrics, parse it, and write its first KPI back. */
function noOpSave(block) {
    DOC.adopt({ serverMetrics: block });
    const kpis = SK.kpisFromDocument(block);
    return { kpi: kpis[0], written: plain(SK._kpiToJson(kpis[0])) };
}

// ---------------------------------------------------------------------------
// C-1 — an unreadable value is written back, never dropped
// ---------------------------------------------------------------------------

test('C-1: a no-op save keeps an unreadable fail band beside a readable warn band', () => {
    const { kpi, written } = noOpSave(blockWith({
        id: 'lag', component: 'c', query: 'lag', reduce: { kind: 'max' },
        warn: { gt: 30 }, fail: { gtt: 90 }
    }));
    // KpiUsability.unreadableBand's sentence, word for word (C-3): the mirror used to say only
    // "fail band could not be read".
    assert.deepStrictEqual(plain(kpi.problems), [GTT_REFUSAL]);
    assert.deepStrictEqual(written.warn, { gt: 30 });
    assert.deepStrictEqual(written.fail, { gtt: 90 },
        'the unreadable fail band was dropped, so the KPI can now never FAIL');
});

test('C-1: every field the parser could not read is written back verbatim', () => {
    const declared = {
        id: 'all-wrong', component: 'c', query: 'cpu',
        reduce: { kind: 'maxx' }, across: 'middle',
        warn: { gt: 'lots' }, fail: { gt: 1, lt: 2 }
    };
    const { written } = noOpSave(blockWith(declared));
    assert.deepStrictEqual(written.reduce, { kind: 'maxx' });
    assert.strictEqual(written.across, 'middle',
        "an unreadable across was rewritten as 'max', silently judging every instance");
    assert.deepStrictEqual(written.warn, { gt: 'lots' });
    assert.deepStrictEqual(written.fail, { gt: 1, lt: 2 });
});

test('C-1: the KPI is still refused after a round trip through the draft record', () => {
    // A persisted draft is kpiToJson's output parsed again by kpiFromJson. Two laps must neither
    // drop the band nor make the KPI judgeable.
    const block = blockWith({
        id: 'lag', component: 'c', query: 'lag', reduce: { kind: 'max' },
        warn: { gt: 30 }, fail: { gtt: 90 }
    });
    const first = noOpSave(block).written;
    const again = noOpSave(blockWith(first));
    assert.deepStrictEqual(again.written, first);
    assert.ok(plain(again.kpi.problems).includes(GTT_REFUSAL));
});

test('C-1: a readable band is written from the model, so a cleared band stays cleared', () => {
    // The fallback is carried on the PARSED KPI, not looked up by id in the file: an editor that
    // cleared a readable band must not have the file's band resurrected under the same id.
    DOC.adopt({ serverMetrics: blockWith({
        id: 'lag', component: 'c', query: 'lag', reduce: { kind: 'max' },
        warn: { gt: 30 }, fail: { gt: 90 }
    }) });
    const cleared = SK._kpiFromJson({
        id: 'lag', component: 'c', query: 'lag', reduce: { kind: 'max' }, fail: { gt: 90 }
    }, null, ['rec']);
    const written = plain(SK._kpiToJson(cleared));
    assert.strictEqual(written.warn, undefined);
    assert.deepStrictEqual(written.fail, { gt: 90 });
});

// ---------------------------------------------------------------------------
// A flag is read the way Jackson reads it, and written back the way the file wrote it
// ---------------------------------------------------------------------------

[
    [0, false], [1, true], [2, true], ['false', false], ['False', false], [' FALSE ', false],
    ['true', true], ['True', true], ['', true], ['null', true], [null, true], [false, false],
].forEach(([flag, enabled]) => {
    test('flags: enabled ' + JSON.stringify(flag) + ' parses as ' + (enabled ? 'enabled' : 'parked')
        + ' and a no-op save writes it back as written', () => {
        const declared = Object.assign({}, KPI, { enabled: flag });
        const { kpi, written } = noOpSave(blockWith(declared));
        assert.strictEqual(kpi.enabled, enabled,
            'the browser and ServerMetricsMapper disagree about whether this KPI is parked');
        // null is not "written" (Jackson reads it as absent), so a save may omit it.
        if (flag !== null) {
            assert.strictEqual(written.enabled, flag,
                'a no-op save rewrote the flag, and with `true` changed what Java decides');
        }
    });
});

test('flags: a toggled flag is written as a real boolean', () => {
    const { kpi } = noOpSave(blockWith(Object.assign({}, KPI, { enabled: 0 })));
    const toggled = Object.assign({}, kpi, { enabled: true });
    assert.strictEqual(plain(SK._kpiToJson(toggled)).enabled, true);
});

test('flags: excludeRampUp 0 or "false" keeps the ramp-up, as Java does', () => {
    [0, 'false', ' False '].forEach((flag) => {
        assert.strictEqual(SK.configFromDocument({ collection: { excludeRampUp: flag } }).excludeRampUp,
            false, 'excludeRampUp ' + JSON.stringify(flag));
    });
    ['', null, 1, 'TRUE'].forEach((flag) => {
        assert.strictEqual(SK.configFromDocument({ collection: { excludeRampUp: flag } }).excludeRampUp,
            true, 'excludeRampUp ' + JSON.stringify(flag));
    });
});

test('C-1: an unedited readable KPI still round-trips exactly', () => {
    const declared = {
        id: 'lag-spike', description: 'breaches', component: 'c', query: 'lag', unit: 'msgs',
        reduce: { kind: 'max', over: { sustainedFor: '5s' } }, across: 'max',
        warn: { gt: 300 }, fail: { gt: 500 }
    };
    assert.deepStrictEqual(noOpSave(blockWith(declared)).written, declared);
});

// ---------------------------------------------------------------------------
// SF1 — a query is read trimmed, and a file is still written back as it was
// ---------------------------------------------------------------------------

test('SF1: a padded query is the trimmed series, and a no-op save keeps the file\'s spelling', () => {
    const declared = { id: 'lag', component: 'c', query: '  lag\t', reduce: { kind: 'max' }, fail: { gt: 500 } };
    const { kpi, written } = noOpSave(blockWith(declared));
    // ServerKpi's constructor trims (String.trim); the mirror trims with javaTrim, so both look
    // the series up as "lag" — padded, it was a series nobody collected (NEEDS RE-COLLECTION).
    assert.strictEqual(kpi.query, 'lag');
    assert.strictEqual(kpi.writtenQuery, 'lag');
    assert.deepStrictEqual(written, declared, 'an unedited entry is the file, byte for byte');
});

test('SF1: an edit elsewhere keeps the query as the file spelled it', () => {
    const declared = { id: 'lag', component: 'c', query: ' lag ', reduce: { kind: 'max' }, fail: { gt: 500 } };
    const { kpi } = noOpSave(blockWith(declared));
    kpi.fail = { op: 'gt', value: 600 };
    const written = plain(SK._kpiToJson(kpi));
    assert.strictEqual(written.query, ' lag ', 'the untouched query keeps its spelling');
    assert.deepStrictEqual(written.fail, { gt: 600 });
});

test('SF2: across min is read, judged by its own key, and round-trips', () => {
    const declared = { id: 'up', component: 'c', query: 'up', reduce: { kind: 'min' }, across: 'min', fail: { lt: 1 } };
    const { kpi, written } = noOpSave(blockWith(declared));
    assert.deepStrictEqual(plain(kpi.problems), []);
    assert.strictEqual(kpi.across, 'min');
    assert.deepStrictEqual(written, declared);
});

/* SF2 review. canonicalJson wrote `across` only when it was not 'max', and since SF2 postgres up
   and cache.hit.pct default to min: an explicit max was the base's spelling, kpiToJson returned
   the file's entry, the edit counted for nothing — and the saved file FAILed the next run with
   the catalogue's min while the board showed PASS. */
const PG = { pair: { type: 'postgres' } };

for (const metric of ['up', 'cache.hit.pct']) {
    test(`SF2: across max on a catalogue ${metric} assert overrides the catalogue's min, and is written`, () => {
        const declared = { component: 'pair', metric, fail: { lt: 1 } };
        const block = { components: PG, asserts: [declared] };
        DOC.adopt({ serverMetrics: block });
        const [kpi] = SK.kpisFromDocument(block);
        assert.strictEqual(kpi.across, 'min', "unwritten, it is the catalogue's");
        assert.deepStrictEqual(plain(SK._kpiToJson(kpi)), declared, 'a no-op save is the file');

        // What applyEditor commits: the form re-parsed, keeping the row's file entry and base.
        const edited = SK._kpiFromJson({ component: 'pair', metric, across: 'max', fail: { lt: 1 } }, PG);
        edited.declared = kpi.declared;
        edited.base = kpi.base;
        assert.strictEqual(edited.across, 'max');
        assert.deepStrictEqual(plain(SK._kpiToJson(edited)), { component: 'pair', metric, fail: { lt: 1 }, across: 'max' });

        // A new assert, and a Duplicate (declared null, a JSON copy of the row), the same.
        const fresh = SK._kpiFromJson({ component: 'pair', metric, across: 'max', fail: { lt: 1 } }, PG);
        assert.strictEqual(plain(SK._kpiToJson(fresh)).across, 'max');
        const copy = JSON.parse(JSON.stringify(edited));
        copy.declared = null;
        delete copy.base;
        assert.strictEqual(plain(SK._kpiToJson(copy)).across, 'max');

        // The catalogue's own fold is still not written on a new assert, and a file that wrote it
        // keeps it.
        const same = SK._kpiFromJson({ component: 'pair', metric, across: 'min', fail: { lt: 1 } }, PG);
        assert.strictEqual(plain(SK._kpiToJson(same)).across, undefined);
        const wrote = { component: 'pair', metric, across: 'min', fail: { lt: 1 } };
        assert.deepStrictEqual(noOpSave({ components: PG, asserts: [wrote] }).written, wrote);
    });
}

test('SF2: a custom assert still writes no across for max, the default it has', () => {
    const fresh = SK._kpiFromJson({ id: 'q', component: 'c', query: 'q', reduce: { kind: 'max' }, across: 'max', fail: { gt: 1 } },
        COMPONENTS);
    assert.strictEqual(plain(SK._kpiToJson(fresh)).across, undefined);
});

// ---------------------------------------------------------------------------
// R1-8 — every slice reads the document before any of them changes
// ---------------------------------------------------------------------------

const KPI = { id: 'k', component: 'c', query: 'q', reduce: { kind: 'max' }, fail: { gt: 1 } };

function docWith(serverMetrics) {
    return { assertions: [], serverMetrics: serverMetrics };
}

function kpiDoc(extra) {
    return docWith({ components: COMPONENTS, asserts: [Object.assign({}, KPI, extra)] });
}

const REFUSED = [
    ['serverMetrics is a list', docWith([]), 'serverMetrics'],
    ['serverMetrics is text', docWith('x'), 'serverMetrics'],
    ['asserts is an object', docWith({ asserts: {} }), 'asserts'],
    ['asserts is text', docWith({ asserts: 'x' }), 'asserts'],
    ['an assert is null', docWith({ asserts: [null] }), 'asserts[0]'],
    ['an assert is a number', docWith({ asserts: [5] }), 'asserts[0]'],
    ['an assert is a list', docWith({ asserts: [[]] }), 'asserts[0]'],
    ['sources is a list', docWith({ sources: [] }), 'sources'],
    ['a source is a number', docWith({ sources: { a: 5 } }), 'sources.a'],
    ['a source auth is a number',
        docWith({ sources: { a: { type: 'prometheus', url: 'http://x', auth: 5 } } }), 'auth'],
    ['a source url is an object', docWith({ sources: { a: { type: 'prometheus', url: {} } } }), 'url'],
    ['components is a list', docWith({ components: [] }), 'components'],
    ['a component is a number', docWith({ components: { g: 5 } }), 'components.g'],
    ['a component type is an object', docWith({ components: { g: { type: {} } } }), 'type'],
    ['a component match is a list', docWith({ components: { g: { type: 'postgres', match: [] } } }), 'match'],
    ['metric is an object', kpiDoc({ metric: {} }), 'metric'],
    ['environments is a list', docWith({ environments: [] }), 'environments'],
    ['vars is a number', docWith({ environments: { e: { vars: 5 } } }), 'vars'],
    ['a var is an object', docWith({ environments: { e: { vars: { k: {} } } } }), 'vars.k'],
    ['collection is a list', docWith({ collection: [] }), 'collection'],
    ['excludeRampUp is "x"', docWith({ collection: { excludeRampUp: 'x' } }), 'excludeRampUp'],
    ['defaultEnv is an object', docWith({ defaultEnv: {} }), 'defaultEnv'],
    ['block requiresLoad is an object', docWith({ requiresLoad: {} }), 'requiresLoad'],
    ['reduce is a number', kpiDoc({ reduce: 5 }), 'reduce'],
    ['reduce is text', kpiDoc({ reduce: 'max' }), 'reduce'],
    ['warn is a number', kpiDoc({ warn: 5 }), 'warn'],
    ['warn is empty text', kpiDoc({ warn: '' }), 'warn'],
    ['fail is a list', kpiDoc({ fail: [] }), 'fail'],
    ['fail is true', kpiDoc({ fail: true }), 'fail'],
    ['id is an object', kpiDoc({ id: {} }), 'id'],
    ['query is a list', kpiDoc({ query: [] }), 'query'],
    ['enabled is "yes"', kpiDoc({ enabled: 'yes' }), 'enabled'],
    // Jackson matches three casings, not any casing; a case-insensitive test let these by.
    ['enabled is "tRUE"', kpiDoc({ enabled: 'tRUE' }), 'enabled'],
    ['enabled is "fAlse"', kpiDoc({ enabled: 'fAlse' }), 'enabled'],
    ['enabled is "1" (a digit in a string is not a flag)', kpiDoc({ enabled: '1' }), 'enabled'],
    ['enabled is 1.5', kpiDoc({ enabled: 1.5 }), 'enabled'],
    ['across is an object', kpiDoc({ across: {} }), 'across'],
    ['pack is an object', kpiDoc({ pack: {} }), 'pack'],
    ['assertions is an object', { assertions: {} }, 'assertions'],
    ['an assertion is a number', { assertions: [5] }, 'assertions[0]'],
];

const ACCEPTED = [
    ['serverMetrics is null', docWith(null)],
    ['asserts is null', docWith({ asserts: null })],
    // D25: `kpis` is an unknown key to Jackson, bound whatever it holds. The mapper refuses it as a
    // retired key (a finding, exit 1), never as a whole-file shape — so these LOAD, in any shape,
    // and the page says why the next run will refuse them (blockProblems, the parity blockCases).
    ['a leftover kpis that is an object', docWith({ kpis: {} })],
    ['a leftover kpis that is text', docWith({ kpis: 'x' })],
    ['a leftover kpis holding a null', docWith({ kpis: [null] })],
    ['a component is null (a finding, not a refusal)', docWith({ components: { g: null } })],
    // `gates` is retired and no longer bound: any shape loads, and the mapper refuses it by name.
    ['a leftover gates that is a list', docWith({ gates: [] })],
    ['a leftover gates holding a number', docWith({ gates: { g: 5 } })],
    // An unknown component key is a finding (failsBuild, C6), never a file refusal.
    ['a component with failsBuild "yes"', docWith({ components: { g: { type: 'custom', failsBuild: 'yes' } } })],
    ['a source is null (a finding, not a refusal)', docWith({ sources: { a: null } })],
    ['a var is a number', docWith({ environments: { e: { vars: { k: 5 } } } })],
    ['an id is a number', kpiDoc({ id: 5 })],
    ['enabled is 0', kpiDoc({ enabled: 0 })],
    ['enabled is "true"', kpiDoc({ enabled: 'true' })],
    ['enabled is " FALSE " (trimmed)', kpiDoc({ enabled: ' FALSE ' })],
    // A blank string and the text "null" are null to Jackson, not a refusal of the file.
    ['enabled is ""', kpiDoc({ enabled: '' })],
    ['enabled is "null"', kpiDoc({ enabled: 'null' })],
    ['excludeRampUp is ""', docWith({ collection: { excludeRampUp: '' } })],
    ['excludeRampUp is "  "', docWith({ collection: { excludeRampUp: '  ' } })],
    ['requiresLoad is a number', kpiDoc({ requiresLoad: 5 })],
    ['an unreadable band OBJECT (a KPI finding, not a file refusal)', kpiDoc({ fail: { gtt: 9 } })],
];

REFUSED.forEach(([name, doc, mentions]) => {
    test('R1-8: refused — ' + name, () => {
        const problem = DOC.check(doc);
        assert.ok(problem, 'a shape the plugin refuses as a whole file was accepted');
        assert.ok(problem.indexOf(mentions) >= 0,
            'the sentence does not name "' + mentions + '": ' + problem);
    });
});

ACCEPTED.forEach(([name, doc]) => {
    test('R1-8: accepted — ' + name, () => {
        assert.strictEqual(DOC.check(doc), null);
    });
});

test('R1-8: a refused document changes nothing at all', () => {
    const before = { assertions: [], serverMetrics: blockWith(KPI) };
    DOC.adopt(before, 'before.json');
    const problem = DOC.replace({ assertions: [], serverMetrics: { asserts: {} } }, 'broken.json');
    assert.ok(problem && problem.indexOf('asserts') >= 0, String(problem));
    assert.strictEqual(DOC.loaded(), before, 'the broken document was adopted anyway');
    assert.strictEqual(DOC.source(), 'before.json');
});

test('R1-8: a parser that throws is a refusal, not a half-applied document', () => {
    // Belt and braces: whatever a slice's parser throws on, replace reports it and adopts nothing.
    const before = { assertions: [] };
    DOC.adopt(before, null);
    const sneaky = { assertions: [] };
    Object.defineProperty(sneaky, 'serverMetrics', {
        enumerable: true,
        get() { throw new Error('boom'); }
    });
    const problem = DOC.replace(sneaky, 'sneaky.json');
    assert.ok(problem && problem.indexOf('boom') >= 0, String(problem));
    assert.strictEqual(DOC.loaded(), before);
});

// ---------------------------------------------------------------------------
// R1-2 / R2-2 — a KPI the file does not name is judged under the id Java derives, and that id is
// never written back
// ---------------------------------------------------------------------------

const PROM = { prom: { type: 'custom' } };

test('R1-2: two id-less KPIs are prom-up and prom-lag, not two KPIs called ""', () => {
    const kpis = SK.kpisFromDocument({ components: PROM, asserts: [
        { component: 'prom', query: 'up', reduce: { kind: 'max' }, fail: { gt: 5 } },
        { component: 'prom', query: 'lag', reduce: { kind: 'max' }, fail: { gt: 100 } },
    ] });
    assert.deepStrictEqual(kpis.map((k) => k.id), ['prom-up', 'prom-lag']);
    assert.deepStrictEqual(kpis.map((k) => k.idDeclared), [false, false]);
});

test('R2-2: the derived id is ServerKpi.deriveId — trimmed component, 40-character query slug', () => {
    const one = (kpi) => SK.kpisFromDocument({ components: PROM, asserts: [kpi] })[0].id;
    assert.strictEqual(one({ component: ' prom ', query: 'cpu' }), 'prom-cpu');
    assert.strictEqual(one({ component: 'prom', query: 'max(Kafka_ConsumerGroup_Lag{group="order", topic="payments"})' }),
        'prom-max-kafka-consumergroup-lag-group-order', 'cut at 40 characters, trailing dash dropped');
    assert.strictEqual(one({ component: 'prom', query: '{}' }), 'prom-x', 'a query with no slug is x');
    assert.strictEqual(one({ query: 'cpu' }), 'metrics-cpu', 'no component: the collection source');
    assert.strictEqual(one({ component: 'prom' }), 'prom-query', 'no query');
    assert.strictEqual(one({ id: '  named  ', component: 'prom', query: 'cpu' }), 'named', 'a written id is trimmed');
});

test('R1-2: a no-op save of an id-less KPI is the file, byte for byte — no "id": "", and the pack kept', () => {
    const declared = { component: 'prom', query: 'up', reduce: { kind: 'max' }, across: 'max',
        fail: { gt: 5 }, pack: 'kafka@1', team: 'payments' };
    const { kpi, written } = noOpSave({ components: PROM, asserts: [declared] });
    assert.strictEqual(kpi.id, 'prom-up');
    assert.strictEqual(JSON.stringify(written), JSON.stringify(declared),
        'an unedited KPI must be written exactly as the file has it');
});

test('R1-2: an edited id-less KPI still writes no id, and keeps what it did not touch', () => {
    const block = { components: PROM, asserts: [{ component: 'prom', query: 'up', reduce: { kind: 'max' },
        fail: { gt: 5 }, pack: 'kafka@1' }] };
    const kpi = SK.kpisFromDocument(block)[0];
    const edited = plain(kpi);
    edited.fail = { op: 'gt', value: 7 };
    const written = plain(SK._kpiToJson(edited));
    assert.strictEqual(written.id, undefined, 'a derived id was written into the file');
    assert.deepStrictEqual(written.fail, { gt: 7 });
    assert.strictEqual(written.pack, 'kafka@1');
});

test('R3-8: Generate never seeds two KPIs with one id — the batch it is building counts', () => {
    // idFor checked the declared KPIs only, so one pass over `cpu{a}` and `cpu.a` (or one query
    // on two components) seeded the same id twice — a duplicate the board could not tell apart.
    const batch = [{ id: 'cpu-a' }];
    assert.strictEqual(SK._idFor('cpu{a}', []), 'cpu-a');
    assert.strictEqual(SK._idFor('cpu.a', batch), 'cpu-a-2');
    assert.strictEqual(SK._idFor('cpu.a', batch.concat([{ id: 'cpu-a-2' }])), 'cpu-a-3');
});

// ---------------------------------------------------------------------------
// C-10 — what a Save writes is the file's own spelling wherever the edit did not reach
// ---------------------------------------------------------------------------

test('C-10: a no-op save writes no gate — the component is the only grouping', () => {
    // kpiFromJson carries the component as the KPI's gate internally, and must not write it back
    // as a "gate" key, which the mapper now refuses as retired.
    const declared = { id: 'k', component: 'c', query: 'q', reduce: { kind: 'max' }, fail: { gt: 1 } };
    assert.strictEqual(JSON.stringify(noOpSave(blockWith(declared)).written), JSON.stringify(declared));
});

test('C-10: an edit keeps every untouched field as the file spelled it, in the file\'s order', () => {
    const declared = { id: 'lag', component: 'c', query: 'lag', unit: ' ms ',
        reduce: { kind: 'MAX', over: { sustainedFor: '60s' } }, fail: { GT: 500 }, warn: { gt: 300 } };
    const kpi = SK.kpisFromDocument(blockWith(declared))[0];
    const edited = plain(kpi);
    edited.warn = { op: 'gt', value: 350 };
    const written = plain(SK._kpiToJson(edited));
    assert.deepStrictEqual(Object.keys(written), Object.keys(declared), 'the key order moved');
    assert.deepStrictEqual(written.reduce, declared.reduce, '"60s" was re-spelled "1m", or MAX as max');
    assert.deepStrictEqual(written.fail, declared.fail);
    assert.strictEqual(written.gate, undefined);
    assert.deepStrictEqual(written.warn, { gt: 350 });
});

test('C-10: unknown keys ride on the KPI — a rename keeps them, a re-used id does not inherit them', () => {
    const block = blockWith({ id: 'old', component: 'c', query: 'q', reduce: { kind: 'max' },
        fail: { gt: 1 }, pack: 'kafka@1', _ticket: 'PERF-441' });
    DOC.adopt({ serverMetrics: block });
    const kpi = SK.kpisFromDocument(block)[0];
    const renamed = plain(kpi);
    renamed.id = 'new';
    const written = plain(SK._kpiToJson(renamed));
    assert.strictEqual(written.id, 'new');
    assert.strictEqual(written.pack, 'kafka@1', 'a rename lost the pack stamp');
    assert.strictEqual(written._ticket, 'PERF-441');

    // A brand-new KPI that happens to take the old id is not the old KPI.
    const fresh = SK._kpiFromJson({ id: 'old', component: 'c', query: 'q', reduce: { kind: 'max' },
        fail: { gt: 1 } }, COMPONENTS);
    const reused = plain(SK._kpiToJson(fresh));
    assert.strictEqual(reused.pack, undefined, "a re-used id resurrected the deleted KPI's pack");
    assert.strictEqual(reused._ticket, undefined);
});

// ---------------------------------------------------------------------------
// R1-11 — a retired key keeps the KPI refused until it is gone, and is gone once removed
// ---------------------------------------------------------------------------

test('R1-11: a KPI-level sustainedFor is refused in the mapper\'s words and kept on a no-op save', () => {
    const declared = { id: 'lag', component: 'c', query: 'lag', reduce: { kind: 'max' },
        sustainedFor: '30s', fail: { gt: 500 } };
    const { kpi, written } = noOpSave(blockWith(declared));
    assert.ok(plain(kpi.problems).some((p) => p.indexOf(
        '"sustainedFor" at the top level of a server assert is ignored and belongs under reduce.over') === 0),
        plain(kpi.problems));
    assert.strictEqual(written.sustainedFor, '30s', 'a no-op save must not repair the file either');
});

test('R1-11: once the author removes the retired key, the KPI is valid and the key is not written', () => {
    const kpi = SK.kpisFromDocument(blockWith({ id: 'lag', component: 'c', query: 'lag',
        reduce: { kind: 'max', over: { sustainedFor: '30s' } }, sustainedFor: '30s', fail: { gt: 500 } }))[0];
    const fixed = plain(kpi);
    delete fixed.extra.sustainedFor;
    const written = plain(SK._kpiToJson(fixed));
    assert.strictEqual(written.sustainedFor, undefined, 'the retired key survived its removal');
    assert.deepStrictEqual(written.reduce, { kind: 'max', over: { sustainedFor: '30s' } });
    const reparsed = SK._kpiFromJson(written, COMPONENTS);
    assert.deepStrictEqual(plain(reparsed.problems), []);
});

// ---------------------------------------------------------------------------
// F1 (M5 exploratory) — "unchanged" is decided on meaning, not spelling
//
// The page ships Java's re-serialisation of the file, not the file: a model's key order,
// version/testNotes filled in, unset record fields written as null. Compared as text, opening the
// very file a report was generated from read as 14 unsaved changes. WIR.document.meaning is the
// one comparison every slice now uses; these pin what it may and may not forget.
// ---------------------------------------------------------------------------

const M = DOC.meaning;

test('F1: a record\'s key order is not a change — a server assert with component before or after its bands', () => {
    const file = { id: 'cpu', component: 'c', query: 'cpu', fail: { gt: 95 } };
    const java = { id: 'cpu', query: 'cpu', fail: { gt: 95 }, component: 'c' };
    assert.strictEqual(M(file, 'serverMetrics.asserts[]'), M(java, 'serverMetrics.asserts[]'));
    assert.notStrictEqual(M(file, 'serverMetrics.asserts[]'),
        M(Object.assign({}, java, { fail: { gt: 96 } }), 'serverMetrics.asserts[]'), 'a real edit vanished');
});

test('F1: a record field written as null is the field left out — Jackson reads both alike', () => {
    const file = { timestamp: 1, label: 'Ramp-up complete', type: 'info' };
    const java = { timestamp: 1, label: 'Ramp-up complete', type: 'info', description: null, color: null };
    assert.strictEqual(M([file], 'timelineMarkers'), M([java], 'timelineMarkers'));
});

test('F1: a MAP keeps its order and its null entries — those are data, not spelling', () => {
    const components = { kafka: { type: 'kafka' }, db: { type: 'postgres' } };
    const swapped = { db: { type: 'postgres' }, kafka: { type: 'kafka' } };
    assert.notStrictEqual(M(components, 'serverMetrics.components'), M(swapped, 'serverMetrics.components'),
        'reordering the components is an edit: it is the order the badges and serverGates[] come in');
    const sources = { rec: { type: 'file', path: 'rec' } };
    assert.notStrictEqual(M(sources, 'serverMetrics.sources'),
        M(Object.assign({ spare: null }, sources), 'serverMetrics.sources'),
        'a null source is a finding for the mapper, not an absent one');
    // ...while the entries themselves are records.
    assert.strictEqual(M({ rec: { type: 'file', path: 'rec' } }, 'serverMetrics.sources'),
        M({ rec: { path: 'rec', type: 'file' } }, 'serverMetrics.sources'));
});

test('F1: an array keeps its order — reordering asserts is still an edit', () => {
    const a = [{ id: 'a' }, { id: 'b' }];
    assert.notStrictEqual(M(a, 'serverMetrics.asserts'), M(a.slice().reverse(), 'serverMetrics.asserts'));
});

// CN22: a band is a Map<String, Object> in Java on both sides (AssertionConfig.warn/fail, KpiJson.warn/
// fail), so its null entry is data — the band reader refuses {"gt": null} as "fail gt must be a number,
// not null" and reads {} as no band at all. Read as a record, both {"gt": null} and {"lt": null} meant
// "{}", so an edit from one to the other counted 0 changes while the rule read EDITED.
test('CN22: a client band\'s null entry is data — {"gt": null}, {"lt": null} and {} are three bands', () => {
    const rule = (fail) => ({ id: 'r', target: { sampler: 'A' }, metric: 'p95', fail });
    const gt = M(rule({ gt: null }), 'assertions[]');
    assert.notStrictEqual(gt, M(rule({ lt: null }), 'assertions[]'), 'an operator change vanished');
    assert.notStrictEqual(gt, M(rule({}), 'assertions[]'), 'a refused band read as no band');
    assert.notStrictEqual(M(rule({ gt: null }), 'assertions[]').indexOf('"gt":null'), -1);
    assert.notStrictEqual(M({ assertions: [rule({ gt: null })] }, ''), M({ assertions: [rule({ lt: null })] }, ''),
        'through the whole document');
    const warn = (band) => ({ id: 'r', target: { sampler: 'A' }, metric: 'p95', warn: band, fail: { gt: 9 } });
    assert.notStrictEqual(M(warn({ gte: null }), 'assertions[]'), M(warn({ lte: null }), 'assertions[]'));
    // The rule itself is still a record: its key order and a null field are spelling.
    assert.strictEqual(M({ id: 'r', metric: 'p95', fail: { gt: 1 }, description: null }, 'assertions[]'),
        M({ fail: { gt: 1 }, metric: 'p95', id: 'r' }, 'assertions[]'));
});

test('CN22: a server assert\'s band\'s null entry is data too', () => {
    const kpi = (band) => ({ id: 'k', component: 'c', query: 'q', warn: band, fail: { gt: 9 } });
    assert.notStrictEqual(M(kpi({ gt: null }), 'serverMetrics.asserts[]'), M(kpi({ lt: null }), 'serverMetrics.asserts[]'));
    assert.notStrictEqual(M(kpi({ gt: null }), 'serverMetrics.asserts[]'), M(kpi({}), 'serverMetrics.asserts[]'));
    const failing = (band) => ({ serverMetrics: { asserts: [{ id: 'k', component: 'c', query: 'q', fail: band }] } });
    assert.notStrictEqual(M(failing({ gt: null }), ''), M(failing({ lt: null }), ''), 'through the whole document');
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
