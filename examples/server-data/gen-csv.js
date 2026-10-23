// Writes a long-format server-metrics CSV whose timestamps cover the run in a JTL.
// A live run's window is only known after the run, so the CSV is built from it.
// usage: node gen-csv.js results.jtl server-metrics.csv
const fs = require('fs');
const { write } = require('./fake-export');

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

write(start, end, csvPath);
