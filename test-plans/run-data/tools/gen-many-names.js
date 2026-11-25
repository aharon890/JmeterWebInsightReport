#!/usr/bin/env node
/**
 * A k6 run with many request names, for spec 002's SC-010: every table opens and every control answers in under half a
 * second on a run with 300 or more request names and about 1,200 thresholds. The 134,406-request run of spec 001 has
 * only 12 names, so it cannot show this.
 *
 * Writes k6's two files, as `k6 run --out csv` and handleSummary write them, from a fixed seed, so the run is the same
 * on every machine:
 *   <out>/results.csv.gz   20 groups of 16 requests (320 names), 3 passes each by 2 VUs, every HTTP timing part
 *   <out>/summary.json     4 thresholds per request (p(95)<=600 on its duration, and k6's always-true count>=0,
 *                          rate>=0 and max>=0), so 1,280 in all, some failing; every value as k6 writes it
 *
 * It is not a fixture: nothing commits its output. Build its report with
 *   java -jar <plugin jar> build-report --from k6 --k6-results <out>/results.csv.gz --k6-summary <out>/summary.json \
 *        --out <dir>
 *
 * usage: node test-plans/run-data/tools/gen-many-names.js <out dir> [groups] [requests per group]
 */
'use strict';

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const out = process.argv[2];
if (!out) {
    console.error('usage: node gen-many-names.js <out dir> [groups] [requests per group]');
    process.exit(2);
}
const GROUPS = Number(process.argv[3] || 20);
const PER_GROUP = Number(process.argv[4] || 16);
const PASSES = 3;
const VUS = 2;
const START = 1795456065000;

/* mulberry32: a fixed seed, the same run everywhere */
let seed = 20261125;
function random() {
    seed |= 0; seed = (seed + 0x6D2B79F5) | 0;
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
}

const HEADER = 'metric_name,timestamp,metric_value,check,error,error_code,expected_response,group,method,name,proto,'
    + 'scenario,service,status,subproto,tls_version,url,extra_tags,metadata';
const PARTS = ['blocked', 'connecting', 'tls_handshaking', 'sending', 'waiting', 'receiving'];

const lines = [HEADER];
const perName = new Map(); // name -> {durations: [], parts: {part: []}, count}
let clock = START;

function row(metric, time, value, group, name, meta) {
    const req = name !== null;
    lines.push([metric, time, value.toFixed(6), '', '', '', req ? 'true' : '', group, req ? 'GET' : '', req ? name : '',
        req ? 'HTTP/1.1' : '', 'many_names', '', req ? '200' : '', '', '', req ? 'http://stub/' + encodeURIComponent(name) : '',
        '', meta].join(','));
}

for (let pass = 0; pass < PASSES; pass++) {
    for (let vu = 1; vu <= VUS; vu++) {
        const meta = 'vu=' + vu + '&iter=' + pass;
        for (let g = 0; g < GROUPS; g++) {
            const group = '::Flow ' + String(g + 1).padStart(2, '0');
            const groupStart = clock;
            for (let r = 0; r < PER_GROUP; r++) {
                const name = 'Flow ' + String(g + 1).padStart(2, '0') + ' step ' + String(r + 1).padStart(2, '0');
                const fresh = r === 0;
                const connecting = fresh ? 1 + random() * 3 : 0;
                const tls = 0;
                const blocked = connecting + tls + random() * 0.05;
                const sending = 0.01 + random() * 0.05;
                /* one request in seven is slow, so some p(95)<=600 thresholds fail */
                const waiting = (r % 7 === 3 ? 500 : 5) + random() * (r % 7 === 3 ? 400 : 60);
                const receiving = 0.02 + random() * 0.3;
                const duration = sending + waiting + receiving;
                const t = clock;
                row('http_reqs', t, 1, group, name, meta);
                row('http_req_duration', t, duration, group, name, meta);
                const values = [blocked, connecting, tls, sending, waiting, receiving];
                PARTS.forEach((p, i) => row('http_req_' + p, t, values[i], group, name, meta));
                row('http_req_failed', t, 0, group, name, meta);
                const n = perName.get(name) || { durations: [], parts: {}, count: 0 };
                n.durations.push(duration);
                PARTS.forEach((p, i) => (n.parts[p] = n.parts[p] || []).push(values[i]));
                n.count++;
                perName.set(name, n);
                clock += Math.ceil(duration) + 1;
            }
            row('group_duration', clock, clock - groupStart, group, null, meta);
        }
        row('iteration_duration', clock, 1, '', null, meta);
        row('iterations', clock, 1, '', null, meta);
    }
}

/* k6's summary values: avg, min, med, max, p(90), p(95), p(99), count, with k6's interpolated percentiles */
function percentile(sorted, p) {
    if (sorted.length === 1) return sorted[0];
    const rank = (p / 100) * (sorted.length - 1);
    const lo = Math.floor(rank);
    const hi = Math.ceil(rank);
    return sorted[lo] + (sorted[hi] - sorted[lo]) * (rank - lo);
}
function trend(values) {
    const s = values.slice().sort((a, b) => a - b);
    return {
        avg: s.reduce((a, b) => a + b, 0) / s.length, min: s[0], med: percentile(s, 50), max: s[s.length - 1],
        'p(90)': percentile(s, 90), 'p(95)': percentile(s, 95), 'p(99)': percentile(s, 99), count: s.length,
    };
}

const seconds = (clock - START) / 1000;
const metrics = {};
const all = [];
for (const [name, n] of perName) {
    all.push(...n.durations);
    const d = trend(n.durations);
    metrics['http_req_duration{name:' + name + '}'] = { type: 'trend', contains: 'time', values: d,
        thresholds: { 'p(95)<=600': { ok: d['p(95)'] <= 600 }, 'max>=0': { ok: true } } };
    metrics['http_reqs{name:' + name + '}'] = { type: 'counter', contains: 'default',
        values: { count: n.count, rate: n.count / seconds }, thresholds: { 'count>=0': { ok: true } } };
    metrics['http_req_failed{name:' + name + '}'] = { type: 'rate', contains: 'default',
        values: { rate: 0, passes: 0, fails: n.count }, thresholds: { 'rate>=0': { ok: true } } };
}
metrics.http_req_duration = { type: 'trend', contains: 'time', values: trend(all) };
const requests = all.length;
metrics.http_reqs = { type: 'counter', contains: 'default', values: { count: requests, rate: requests / seconds } };
metrics.iterations = { type: 'counter', contains: 'default', values: { count: PASSES * VUS, rate: PASSES * VUS / seconds } };
metrics.vus_max = { type: 'gauge', contains: 'default', values: { value: VUS, min: VUS, max: VUS } };

const summary = {
    root_group: { name: '', path: '', id: '', groups: [], checks: [] },
    options: { summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)', 'count'], summaryTimeUnit: '',
        noColor: false },
    state: { isStdErrTTY: false, testRunDurationMs: clock - START, isStdOutTTY: false },
    metrics,
};

fs.mkdirSync(out, { recursive: true });
fs.writeFileSync(path.join(out, 'results.csv.gz'), zlib.gzipSync(lines.join('\n') + '\n'));
fs.writeFileSync(path.join(out, 'summary.json'), JSON.stringify(summary, null, 2) + '\n');
const thresholds = Object.values(metrics).reduce((n, m) => n + Object.keys(m.thresholds || {}).length, 0);
console.log('gen-many-names: ' + perName.size + ' request names, ' + requests + ' requests, ' + thresholds
    + ' thresholds -> ' + out);
