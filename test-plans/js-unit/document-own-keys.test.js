/**
 * Every top-level key of the annotations file survives WIR.document, `__proto__` included (U3) —
 * driven through the page's own lifecycle (harness.loadReportPage), with no browser.
 *
 * The page reads the document with JSON.parse (the inline payload, Open file…, Edit document…),
 * which makes `"__proto__"` an OWN key like any other, and Java keeps it (ReportAnnotations'
 * @JsonAnySetter, written back by GatePayloadBuilder.documentOf). WIR.document then copied the
 * document's keys into plain `{}` objects in six places, and on a plain object `x["__proto__"]`
 * reads Object.prototype and `x["__proto__"] = v` sets the prototype instead of adding a key. So the
 * key was dropped from Download, a document that added or changed it counted no change, and an Undo
 * (capture → discard → reinstate) lost it again: a hand-written block silently gone from the file,
 * against the promise that a block this build has never heard of survives a round trip.
 *
 * Each case runs for an ordinary unknown key too (`x-custom`), which was always right: the control
 * that says the test asks the page nothing it never promised.
 */
'use strict';

const assert = require('assert');
const { loadReportPage } = require('./harness');

const tests = [];
const test = (name, fn) => tests.push({ name, fn });

const KEYS = ['x-custom', '__proto__'];
const own = (o, k) => Object.prototype.hasOwnProperty.call(o, k);

/** `obj` with `key` appended as an OWN property, as JSON.parse reads a file that wrote it last. */
function withKey(obj, key, value) {
    const text = JSON.stringify(obj);
    return JSON.parse(text.replace(/\}$/, (text === '{}' ? '' : ',') + JSON.stringify(key) + ':'
        + JSON.stringify(value) + '}'));
}

const DOCUMENT = {
    // No client rule: the client slice reads its rules from the judged payload (chartData.assertions),
    // which a page built by hand would have to carry as well. The keys under test are top-level.
    assertions: [],
    serverMetrics: {
        components: { db: { type: 'custom' } },
        asserts: [{ id: 'up', component: 'db', query: 'up', reduce: { kind: 'max' }, fail: { gt: 1 } }]
    }
};

function page(document) {
    return loadReportPage({
        runId: 'document-own-keys',
        annotationsDocument: document,
        samplers: [],
        overall: { totalSamples: 0 },
        serverKpis: [{ id: 'up', status: 'PASS' }],
        serverFindings: []
    });
}

/** What a Download writes, read back as JSON.parse reads it. */
function downloaded(WIR) {
    return JSON.parse(WIR.document.text());
}

function says(WIR, key, value, what) {
    const doc = downloaded(WIR);
    assert.ok(own(doc, key), what + ': the Download dropped ' + JSON.stringify(key)
        + ' — it wrote ' + JSON.stringify(Object.keys(doc)));
    assert.deepStrictEqual(JSON.parse(JSON.stringify(doc[key])), value, what + ': the value moved');
}

for (const key of KEYS) {
    test(key + ': a key the page shipped with is in the Download, and is no change', () => {
        const WIR = page(withKey(DOCUMENT, key, { keep: 'me' }));
        assert.ok(own(WIR.document.asLoaded(), key), 'precondition: the page received it as an own key');
        says(WIR, key, { keep: 'me' }, 'shipped');
        assert.strictEqual(WIR.document.hasDrafts(), false);
        assert.strictEqual(WIR.document.count(), 0);
    });

    test(key + ': a document that adds it counts one change, and the Download carries it', () => {
        const WIR = page(JSON.parse(JSON.stringify(DOCUMENT)));
        assert.strictEqual(WIR.document.replace(withKey(downloaded(WIR), key, { keep: 'me' }), 'opened.json'), null);
        says(WIR, key, { keep: 'me' }, 'opened');
        assert.strictEqual(WIR.document.hasDrafts(), true, 'an added block read as no change at all');
        assert.strictEqual(WIR.document.count(), 1);
    });

    test(key + ': a document that changes it counts one change', () => {
        const WIR = page(withKey(DOCUMENT, key, { keep: 'me' }));
        // Open file… of the same file with that one block changed.
        assert.strictEqual(WIR.document.replace(withKey(DOCUMENT, key, { keep: 'you' }), 'opened.json'), null);
        says(WIR, key, { keep: 'you' }, 'changed');
        assert.strictEqual(WIR.document.count(), 1);
    });

    test(key + ': changed beside another block no slice owns, each counts — two changes, not one', () => {
        // count() never reads 0 while hasDrafts() is true, so one change alone cannot tell a key the
        // per-block count saw from one it lost: the floor makes both 1.
        const WIR = page(withKey(DOCUMENT, key, { keep: 'me' }));
        const opened = withKey(withKey(DOCUMENT, key, { keep: 'you' }), 'samplerNotes', { A: 'slow on Mondays' });
        assert.strictEqual(WIR.document.replace(opened, 'opened.json'), null);
        says(WIR, key, { keep: 'you' }, 'changed');
        assert.strictEqual(WIR.document.count(), 2);
    });

    test(key + ': added or removed as an empty block beside another block no slice owns — two changes', () => {
        // Read on the side that lacks it, "__proto__" was Object.prototype, whose meaning is "{}" —
        // the same as an empty block's, so adding or removing one counted nothing.
        const notes = (doc) => withKey(doc, 'samplerNotes', { A: 'slow on Mondays' });
        let WIR = page(JSON.parse(JSON.stringify(DOCUMENT)));
        assert.strictEqual(WIR.document.replace(notes(withKey(DOCUMENT, key, {})), 'opened.json'), null);
        says(WIR, key, {}, 'added');
        assert.strictEqual(WIR.document.count(), 2, 'added');
        WIR = page(withKey(DOCUMENT, key, {}));
        assert.strictEqual(WIR.document.replace(notes(DOCUMENT), 'opened.json'), null);
        assert.ok(!own(downloaded(WIR), key), 'precondition: the opened file has no ' + JSON.stringify(key));
        assert.strictEqual(WIR.document.count(), 2, 'removed');
    });

    test(key + ': it survives capture → discard → reinstate, as an Undo of Discard drafts runs it', () => {
        const WIR = page(JSON.parse(JSON.stringify(DOCUMENT)));
        assert.strictEqual(WIR.document.replace(withKey(downloaded(WIR), key, { keep: 'me' }), 'opened.json'), null);
        const raw = WIR.document.capture();
        WIR.document.discard();
        assert.ok(!own(downloaded(WIR), key), 'precondition: Discard went back to the shipped file');
        WIR.document.reinstate(raw);
        says(WIR, key, { keep: 'me' }, 'reinstated');
        assert.strictEqual(WIR.document.count(), 1);
    });

    test(key + ': a stored record that holds another collection\'s server draft keeps it in its base', () => {
        // The interim report's server draft, held on this page (SF45): persist writes the record
        // back with the held snapshot's half of the base and the rest from this page's document.
        const WIR = page(withKey(DOCUMENT, key, { keep: 'me' }));
        const store = storage(WIR);
        const doc = downloaded(WIR);
        doc.serverMetrics.asserts[0].fail = { gt: 2 };
        assert.strictEqual(WIR.document.replace(doc, 'opened.json'), null);
        WIR.document.persist();
        const record = JSON.parse(store.getItem(WIR.document.draftKey()));
        assert.ok(record && record.base && record.slices.server, 'precondition: a record with a base and a server snapshot');
        // ...made, as far as this page can tell, against another collection: the interim report's.
        record.slices.server.collectionId = 'the-interim-report';
        store.setItem(WIR.document.draftKey(), JSON.stringify(record));
        WIR.document.start();
        assert.deepStrictEqual(JSON.parse(JSON.stringify(WIR.document.dropped())).map((d) => [d.name, d.count]), [['server', 1]],
            'precondition: the server draft is held, not restored');
        says(WIR, key, { keep: 'me' }, 'restored');
        WIR.document.persist();
        const written = JSON.parse(store.getItem(WIR.document.draftKey()));
        assert.ok(own(written.base, key), 'the record\'s base dropped ' + JSON.stringify(key)
            + ' — it holds ' + JSON.stringify(Object.keys(written.base)));
        assert.deepStrictEqual(written.base.serverMetrics.asserts[0].fail, { gt: 2 }, 'the held half of the base');
    });
}

/*
 * One level down (N2-nested-proto). The top-level copies went through putOwn (U3); the server
 * slice's own copies did not: buildBlock copied the block's keys with `block[k] = loaded[k]`, and an
 * assert's unknown keys travelled through kpiFromJson's `extra`, canonicalJson and kpiToJson the
 * same way. So a "__proto__" key inside serverMetrics, or inside an assert once it was edited on the
 * page, was dropped from the Download.
 */

/** DOCUMENT with `key` as an OWN key of the serverMetrics block, or of its first assert. */
function nested(where, key, value) {
    const doc = JSON.parse(JSON.stringify(DOCUMENT));
    (where === 'block' ? doc.serverMetrics : doc.serverMetrics.asserts[0])['x-marker'] = 0;
    return JSON.parse(JSON.stringify(doc).replace('"x-marker":0', JSON.stringify(key) + ':' + JSON.stringify(value)));
}

function holds(obj, key, value, what) {
    assert.ok(own(obj, key), what + ': the Download dropped ' + JSON.stringify(key)
        + ' — it wrote ' + JSON.stringify(Object.keys(obj)));
    assert.deepStrictEqual(JSON.parse(JSON.stringify(obj[key])), value, what + ': the value moved');
}

for (const key of KEYS) {
    test(key + ' inside the serverMetrics block: in the Download, and no change', () => {
        const WIR = page(nested('block', key, { keep: 'me' }));
        assert.ok(own(WIR.document.asLoaded().serverMetrics, key), 'precondition: shipped as an own key');
        holds(downloaded(WIR).serverMetrics, key, { keep: 'me' }, 'shipped');
        assert.strictEqual(WIR.document.count(), 0);
    });

    test(key + ' inside the serverMetrics block: still there after a server assert is edited', () => {
        const WIR = page(nested('block', key, { keep: 'me' }));
        WIR.serverKpis.toggleEnabled(WIR.serverKpis._keyOf('up'));
        holds(downloaded(WIR).serverMetrics, key, { keep: 'me' }, 'edited');
        assert.strictEqual(downloaded(WIR).serverMetrics.asserts[0].enabled, false, 'precondition: the edit landed');
    });

    test(key + ' inside a server assert: in the Download, unedited and after the assert is edited', () => {
        const WIR = page(nested('assert', key, { keep: 'me' }));
        holds(downloaded(WIR).serverMetrics.asserts[0], key, { keep: 'me' }, 'shipped');
        assert.strictEqual(WIR.document.count(), 0);
        WIR.serverKpis.toggleEnabled(WIR.serverKpis._keyOf('up'));
        const written = downloaded(WIR).serverMetrics.asserts[0];
        assert.strictEqual(written.enabled, false, 'precondition: the edit landed');
        holds(written, key, { keep: 'me' }, 'edited');
        assert.strictEqual(WIR.document.count(), 1, 'one change: the toggle');
    });
}

/**
 * A localStorage for the page — the harness gives it none, so nothing persists — installed on the
 * page's own global, which is where report.js resolves the name.
 */
function storage(WIR) {
    const items = new Map();
    const store = {
        getItem: (k) => (items.has(k) ? items.get(k) : null),
        setItem: (k, v) => { items.set(k, String(v)); },
        removeItem: (k) => { items.delete(k); }
    };
    WIR.document.text.constructor('return this')().localStorage = store;
    return store;
}

test('nothing leaks onto a prototype: the Download is a plain object whatever the file says', () => {
    const WIR = page(withKey(DOCUMENT, '__proto__', { serverMetrics: { phantom: true } }));
    const doc = WIR.document._assemble(false);
    assert.strictEqual(Object.getPrototypeOf(doc), Object.getPrototypeOf(WIR.document._assemble(true)));
    assert.strictEqual(Object.getPrototypeOf(Object.getPrototypeOf(doc)), null,
        'the document\'s prototype is not Object.prototype');
    assert.deepStrictEqual(JSON.parse(JSON.stringify(downloaded(WIR).serverMetrics)), DOCUMENT.serverMetrics);
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
        console.log('       ' + (e && e.message ? e.message.split('\n').slice(0, 4).join(' | ') : e));
    }
}
console.log('\n' + (tests.length - failed) + '/' + tests.length + ' passed');
process.exit(failed === 0 ? 0 : 1);
