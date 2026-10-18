#!/usr/bin/env node
/*
 * example-server-metrics.js <results.jtl> <out.csv> [step seconds, default 5]
 *
 * DEMO server data for reportExample/, and nothing else. refresh-report-example.sh runs it after
 * the listener run, collects what it writes with `wir-collect --csv`, and judges that with
 * `jmeter -g -o`: the documented three steps, end to end, on a machine with no servers to measure.
 *
 * Every value is ILLUSTRATIVE. A CSV cannot carry a comment line, so test-plans/annotations/
 * example.json says so where a reader of the report sees it: each component's label ends
 * "(demo data)", and the report's notes say the script generated it and no server was measured.
 *
 * What it writes is wir-collect's long CSV, `time,component,metric,instance,value`, one block of
 * rows per series in time order, one point per step from the JTL's first sample to one step past
 * its last sample's end - the collector's window (WirCollect: first timeStamp to the latest
 * timeStamp + elapsed), so every series covers it whole. The rows are the components example.json
 * declares: the asserted metrics, and a few unasserted catalogue metrics beside them so the Server
 * tab's metric picker has this run's values to show.
 *
 * Shaped by the run's own load: each step's load is half its active threads and half its sample
 * rate, each relative to the run's own peak, so a slower machine draws the same shapes and the
 * asserts land the same way - orders-db PASS, app-host WARN, events FAIL (a consumer-lag spike at
 * 55% of every run). Deterministic: the noise is a seeded PRNG per series, so two runs differ only
 * through their windows and their load.
 *
 * Plain node, no dependencies. Exits 1 with a sentence on anything it cannot do.
 */
'use strict';

const fs = require('fs');

function die(message) {
    process.stderr.write('example-server-metrics: ' + message + '\n');
    process.exit(1);
}

// ---- the JTL -----------------------------------------------------------------------------------

/** RFC 4180 rows: quoted fields, doubled quotes, a comma or a line break inside quotes. */
function parseCsv(text) {
    const rows = [];
    let row = [];
    let field = '';
    let quoted = false;
    for (let i = 0; i < text.length; i++) {
        const c = text[i];
        if (quoted) {
            if (c === '"') {
                if (text[i + 1] === '"') {
                    field += '"';
                    i++;
                } else {
                    quoted = false;
                }
            } else {
                field += c;
            }
        } else if (c === '"') {
            quoted = true;
        } else if (c === ',') {
            row.push(field);
            field = '';
        } else if (c === '\n' || c === '\r') {
            if (c === '\r' && text[i + 1] === '\n') i++;
            row.push(field);
            field = '';
            if (row.length > 1 || row[0] !== '') rows.push(row);
            row = [];
        } else {
            field += c;
        }
    }
    if (field !== '' || row.length) {
        row.push(field);
        rows.push(row);
    }
    return rows;
}

function readSamples(jtlPath) {
    let text;
    try {
        text = fs.readFileSync(jtlPath, 'utf8');
    } catch (e) {
        die('cannot read the JTL ' + jtlPath + ' - ' + e.message);
    }
    // A byte-order mark is not part of the first header name.
    const rows = parseCsv(text.charCodeAt(0) === 0xFEFF ? text.slice(1) : text);
    if (rows.length < 2) die(jtlPath + ' holds no samples');
    const header = rows[0].map(h => h.trim());
    const col = name => header.indexOf(name);
    const ts = col('timeStamp');
    const elapsed = col('elapsed');
    const threads = col('allThreads') >= 0 ? col('allThreads') : col('grpThreads');
    if (ts < 0 || elapsed < 0) {
        die(jtlPath + ' is not a CSV JTL with a header naming timeStamp and elapsed (found: '
            + header.slice(0, 6).join(',') + ')');
    }
    const samples = [];
    for (let r = 1; r < rows.length; r++) {
        const t = Number(rows[r][ts]);
        const e = Number(rows[r][elapsed]);
        if (!Number.isFinite(t) || !Number.isFinite(e)) continue;
        const n = threads >= 0 ? Number(rows[r][threads]) : NaN;
        samples.push({ start: t, end: t + e, threads: Number.isFinite(n) ? n : 0 });
    }
    if (!samples.length) die(jtlPath + ' holds no readable samples');
    return samples;
}

// ---- the load, per step ------------------------------------------------------------------------

/**
 * One entry per point: its time, the run's progress (0..1), the samples per second and the
 * largest active-thread count around it, and the load (0..1) relative to the run's own peak.
 */
function loadPerStep(samples, stepMs) {
    let start = Infinity;
    let end = -Infinity;
    for (const s of samples) {
        if (s.start < start) start = s.start;
        if (s.end > end) end = s.end;
    }
    const last = Math.max(2, Math.ceil((end - start) / stepMs));
    const steps = [];
    for (let k = 0; k <= last; k++) {
        const at = start + k * stepMs;
        // Around the point, clipped to the run: a point on an edge counts half a step.
        const from = Math.max(start, at - stepMs / 2);
        const to = Math.min(end, at + stepMs / 2);
        let count = 0;
        let threads = 0;
        for (const s of samples) {
            if (s.start >= from && s.start < to) {
                count++;
                if (s.threads > threads) threads = s.threads;
            }
        }
        const seconds = (to - from) / 1000;
        const previous = steps[steps.length - 1];
        // Under a second of run around the point (the one past the end): carry the last one.
        const rate = seconds >= 1 ? count / seconds : previous ? previous.rate : 0;
        steps.push({ k, at, rate, threads: threads || (previous ? previous.threads : 0) });
    }
    const peakRate = Math.max(...steps.map(s => s.rate)) || 1;
    const peakThreads = Math.max(...steps.map(s => s.threads)) || 1;
    for (const s of steps) {
        s.progress = s.k / last;
        s.load = 0.5 * (s.threads / peakThreads) + 0.5 * (s.rate / peakRate);
    }
    return { start, end, last, steps };
}

// ---- seeded noise ------------------------------------------------------------------------------

function fnv1a(text) {
    let h = 0x811c9dc5;
    for (let i = 0; i < text.length; i++) {
        h ^= text.charCodeAt(i);
        h = Math.imul(h, 0x01000193) >>> 0;
    }
    return h;
}

/** mulberry32: a small PRNG whose sequence is fixed by its seed. */
function rng(seed) {
    let a = seed >>> 0;
    return function () {
        a = (a + 0x6d2b79f5) >>> 0;
        let t = a;
        t = Math.imul(t ^ (t >>> 15), t | 1);
        t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
        return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
}

// ---- the series --------------------------------------------------------------------------------

const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));

/**
 * Every series written, as [component, metric, instance, decimals, value(step, noise)]. noise(a)
 * is uniform in [-a, a]. The asserted four (example.json): connections.used.pct, cpu.busy.pct,
 * consumer.lag.max and brokers. consumer.lag.total and consumer.lag.final are left out on purpose:
 * they share one catalogue query, so a CSV row of one would answer for both.
 */
function seriesFor(run) {
    const spike = Math.round(run.last * 0.55);
    const connPct = (s, noise) => clamp(20 + 36 * s.load + noise(2), 0, 100);
    return [
        // orders-db (postgres): comfortably inside its pool - PASS.
        ['orders-db', 'up', 'pg-1', 0, () => 1],
        ['orders-db', 'connections.used.pct', 'pg-1', 2, connPct],
        ['orders-db', 'connections.active', 'pg-1', 0, (s, noise) => connPct(s, noise) * 2],
        ['orders-db', 'cache.hit.pct', 'pg-1', 2, (s, noise) => clamp(99.7 - 1.1 * s.load + noise(0.15), 0, 100)],
        ['orders-db', 'transaction.longest.seconds', 'pg-1', 2, (s, noise) => Math.max(0, 0.2 + 1.4 * s.load + noise(0.1))],
        ['orders-db', 'locks.waiting', 'pg-1', 0, (s, noise) => Math.max(0, 2.5 * s.load + noise(0.8))],
        ['orders-db', 'deadlocks', 'pg-1', 0, () => 0],
        ['orders-db', 'throughput.commits', 'pg-1', 2, (s, noise) => Math.max(0, 1.6 * s.rate + noise(0.3))],
        // app-host (host-linux): CPU climbs with the load into its warn band - WARN.
        ['app-host', 'cpu.busy.pct', 'app-1', 2, (s, noise) => clamp(18 + 62 * s.load + noise(2), 0, 100)],
        ['app-host', 'load.per.core', 'app-1', 2, (s, noise) => Math.max(0, 0.12 + 0.75 * s.load + noise(0.04))],
        ['app-host', 'memory.available.pct', 'app-1', 2,
            (s, noise) => clamp(66 - 10 * s.progress - 8 * s.load + noise(0.8), 0, 100)],
        ['app-host', 'disk.await.ms', 'app-1', 2, (s, noise) => Math.max(0, 1.6 + 2.8 * s.load + noise(0.3))],
        ['app-host', 'network.bytes.rate', 'app-1', 0, (s, noise) => Math.max(0, 4000 + 52000 * s.rate + noise(1500))],
        // events (kafka): a consumer-lag spike in the middle of the run - FAIL; brokers all up.
        ['events', 'consumer.lag.max', 'orders-consumer', 0, (s, noise) => Math.max(0, 25 + 140 * s.load + noise(15))
            + (s.k === spike ? 2450 : Math.abs(s.k - spike) === 1 ? 900 : 0)],
        ['events', 'brokers', 'events-cluster', 0, () => 3],
        ['events', 'messages.in.rate', 'events-cluster', 2, (s, noise) => Math.max(0, 3 * s.rate + noise(0.5))],
        ['events', 'partitions.under_replicated', 'events-cluster', 0, () => 0]
    ];
}

/** A number as the CSV backend reads one: '.' for the point, no exponent, no thousands separator. */
function text(value, decimals) {
    return Number(value.toFixed(decimals)).toString();
}

function main(argv) {
    if (argv.length < 2 || argv.length > 3) {
        die('usage: node example-server-metrics.js <results.jtl> <out.csv> [step seconds, default 5]');
    }
    const [jtlPath, outPath] = argv;
    const stepSeconds = argv.length === 3 ? Number(argv[2]) : 5;
    if (!(stepSeconds > 0)) die('the step must be a positive number of seconds, not "' + argv[2] + '"');
    const run = loadPerStep(readSamples(jtlPath), stepSeconds * 1000);
    const lines = ['time,component,metric,instance,value'];
    const series = seriesFor(run);
    for (const [component, metric, instance, decimals, valueAt] of series) {
        const next = rng(fnv1a(component + '/' + metric + '/' + instance));
        for (const s of run.steps) {
            const noise = a => (next() * 2 - 1) * a;
            lines.push([new Date(s.at).toISOString(), component, metric, instance,
                text(valueAt(s, noise), decimals)].join(','));
        }
    }
    try {
        fs.writeFileSync(outPath, lines.join('\n') + '\n');
    } catch (e) {
        die('cannot write ' + outPath + ' - ' + e.message);
    }
    console.log('example-server-metrics: wrote ' + outPath + ' - ' + series.length + ' series, '
        + run.steps.length + ' points each at ' + stepSeconds + 's over the run\'s '
        + ((run.end - run.start) / 1000).toFixed(1) + 's');
}

main(process.argv.slice(2));
