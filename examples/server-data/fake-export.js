// Stands in for a monitoring tool's export: writes this example's three series, one row a second,
// over an epoch-millisecond window, as a long-format server-data CSV. The hooks call it the way
// they would call your tool's CLI or API.
// usage: node fake-export.js <startMillis> <endMillis> server.csv
const fs = require('fs');

function write(startMillis, endMillis, csvPath) {
  const first = Math.floor(startMillis / 1000) * 1000;
  const last = Math.ceil(endMillis / 1000) * 1000;
  // component, metric, value at t seconds into the window
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
}

module.exports = { write };

if (require.main === module) {
  const [start, end, csvPath] = process.argv.slice(2);
  if (!/^\d+$/.test(start || '') || !/^\d+$/.test(end || '') || !csvPath) {
    console.error('usage: node fake-export.js <startMillis> <endMillis> server.csv');
    process.exit(1);
  }
  write(Number(start), Number(end), csvPath);
}
