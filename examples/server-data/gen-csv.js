// Writes a long-format server-metrics CSV whose timestamps cover the run in a JTL.
// A live run's window is only known after the run, so the CSV is built from it.
// usage: node gen-csv.js results.jtl server-metrics.csv
const fs = require('fs');

const [jtlPath, csvPath] = process.argv.slice(2);
if (!jtlPath || !csvPath) {
  console.error('usage: node gen-csv.js results.jtl server-metrics.csv');
  process.exit(1);
}

const lines = fs.readFileSync(jtlPath, 'utf8').split(/\r?\n/).filter(Boolean);
const header = lines[0].split(',');
const tsCol = header.indexOf('timeStamp');
const elCol = header.indexOf('elapsed');
if (tsCol < 0 || elCol < 0) {
  console.error('not a CSV JTL: no timeStamp/elapsed column in ' + jtlPath);
  process.exit(1);
}

let start = Infinity;
let end = -Infinity;
for (const line of lines.slice(1)) {
  const cells = line.split(',');
  const ts = Number(cells[tsCol]);
  const el = Number(cells[elCol]);
  if (!Number.isFinite(ts)) continue;
  start = Math.min(start, ts);
  end = Math.max(end, ts + (Number.isFinite(el) ? el : 0));
}
if (!Number.isFinite(start)) {
  console.error('no samples in ' + jtlPath);
  process.exit(1);
}

const first = Math.floor(start / 1000) * 1000;
const last = Math.ceil(end / 1000) * 1000;

// component, metric, value at t seconds into the run
const series = [
  ['app', 'cpu.pct', t => 35 + 25 * Math.abs(Math.sin(t / 8))],  // peaks at 60: stays under its bands
  ['app', 'mem.pct', t => 40 + 0.3 * t],                         // crosses 50 about 33 s in
  ['db', 'connections', t => 20 + 1.1 * t],                      // climbs steadily
];

const rows = ['time,component,metric,instance,value'];
for (let ms = first; ms <= last; ms += 1000) {
  const t = (ms - first) / 1000;
  const time = new Date(ms).toISOString().replace('.000Z', 'Z');
  for (const [component, metric, valueAt] of series) {
    rows.push([time, component, metric, '', valueAt(t).toFixed(2)].join(','));
  }
}
fs.writeFileSync(csvPath, rows.join('\n') + '\n');
console.log('wrote ' + (rows.length - 1) + ' rows covering ' + new Date(first).toISOString() +
  ' .. ' + new Date(last).toISOString() + ' to ' + csvPath);
