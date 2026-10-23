#!/usr/bin/env node
/*
 * Writes e28-metrics.csv: server data over the goldens' plain.jtl window (1700000000026 ..
 * 1700000048490), one series per way a real export goes wrong, beside one that is right.
 * Committed with its output; rerun only to change the cases:
 *
 *   node test-plans/server-fixtures/gen-e28.js > test-plans/server-fixtures/e28-metrics.csv
 *
 * Each series is the e28-server.json assert of the same name:
 *   aligned   every second, inside the run          -> judged (PASS)
 *   shifted   the same rows 3 h late                -> SOURCE FAILED, "usually a time zone"
 *   heap_used the metric the file calls heap.used   -> SOURCE FAILED, both spellings named
 *   blank     rows inside the run with no value     -> SOURCE FAILED, no value
 *   spaced    every 4 s, under sustainedFor 30s     -> INVALID_RULE, the measured 4s spacing
 *             (E28 collects at an explicit 15 s step; measured, the file's step is 1 s)
 *   split     one row split by an unquoted "90,9"   -> SOURCE FAILED, naming the line
 */
const START = 1700000000000;
const END = 1700000049000;
const rows = ['time,component,metric,instance,value'];
for (let t = START; t <= END; t += 1000) rows.push(`${t},app,aligned,,${40 + (t / 1000) % 7}`);
for (let t = START; t <= END; t += 1000) rows.push(`${t + 3 * 3600000},app,shifted,,55`);
for (let t = START; t <= END; t += 1000) rows.push(`${t},app,heap_used,,70`);
for (let t = START; t <= END; t += 1000) rows.push(`${t},app,blank,,`);
for (let t = START; t <= END; t += 4000) rows.push(`${t},app,spaced,,200`);
for (let t = START; t <= END; t += 1000) {
  rows.push(t === START + 20000 ? `${t},app,split,,90,9` : `${t},app,split,,30`);
}
process.stdout.write(rows.join('\n') + '\n');
