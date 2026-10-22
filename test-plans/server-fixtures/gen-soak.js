#!/usr/bin/env node
/*
 * E29, the soak: writes soak.jtl and soak.csv into the directory given, from a fixed seed, so
 * every byte - and so the runId, every number and every verdict - is the same on every machine.
 * Generated at fixture time rather than committed: the CSV is ~10 MB.
 *
 *   node test-plans/server-fixtures/gen-soak.js <out-dir>
 *
 * Four hours, three samplers, one sample each per 2 s. Server data: component "fleet", 24
 * instances (the page draws 20 and says so), one point a second (14,401 per instance, so the page
 * holds a thinned copy and says so), CPU near 50 % with one 3-second spike to 97.5 on instance
 * node-17 at 02:30 into the run - the max the build judges, which a thinned line must still reach.
 * Memory climbs slowly on every node, so a slope assert has something to measure.
 */
const fs = require('fs');
const path = require('path');

const out = process.argv[2];
if (!out) {
  process.stderr.write('usage: gen-soak.js <out-dir>\n');
  process.exit(1);
}
fs.mkdirSync(out, { recursive: true });

let seed = 20261022;
function rand() {               // a 32-bit LCG: no Math.random, the same stream everywhere
  seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0;
  return seed / 4294967296;
}

const START = 1800000000000;
const HOURS = 4;
const END = START + HOURS * 3600000;

const SAMPLERS = ['GET /api/products', 'POST /api/checkout', 'GET /api/orders'];
const jtl = ['timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success,failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect'];
for (let t = START; t < END; t += 2000) {
  SAMPLERS.forEach((label, i) => {
    const elapsed = 80 + i * 60 + Math.floor(rand() * 120);
    const ok = rand() > 0.002;
    jtl.push([t + i * 300, elapsed, label, ok ? 200 : 500, ok ? 'OK' : 'Internal Server Error',
      'Soak 1-' + (i + 1), 'text', ok, '', 800, 220, 3, 3, 'null', elapsed - 8, 0, 2].join(','));
  });
}
fs.writeFileSync(path.join(out, 'soak.jtl'), jtl.join('\n') + '\n');

const SPIKE_NODE = 17;
const SPIKE_AT = START + 2.5 * 3600000;
const csv = fs.createWriteStream(path.join(out, 'soak.csv'));
csv.write('time,component,metric,instance,value\n');
const lines = [];
for (let n = 1; n <= 24; n++) {
  const node = 'node-' + String(n).padStart(2, '0');
  for (let t = START; t <= END; t += 1000) {
    let cpu = 45 + rand() * 10;
    if (n === SPIKE_NODE && t >= SPIKE_AT && t < SPIKE_AT + 3000) cpu = 97.5;
    lines.push(t + ',fleet,cpu,' + node + ',' + cpu.toFixed(1));
  }
  for (let t = START; t <= END; t += 1000) {
    const mem = 40 + n * 0.5 + (t - START) / 3600000 * 2 + rand();
    lines.push(t + ',fleet,mem,' + node + ',' + mem.toFixed(2));
  }
  csv.write(lines.join('\n') + '\n');
  lines.length = 0;
}
csv.end();
