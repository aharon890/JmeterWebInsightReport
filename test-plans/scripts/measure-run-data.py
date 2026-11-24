#!/usr/bin/env python3
"""
The build-time and memory figures of spec 001 (task T067, plan "Performance Goals", SC-006). Called by
measure-run-data.sh, which finds JMeter, the JAR and Python; run that, not this.

  repeat  <k6 results.csv.gz> <copies> <out.csv.gz>
      k6's file repeated: copy r starts r * 1.5 s after the first, its VUs renumbered (vu + 1000 r) so every pass
      stays its own. 2358 copies of K2 (57 requests) are 134,400 requests over about an hour, ~37 a second.
  time    <label> <out.json> -- <command...>
      runs the command, and appends {label, seconds, exit} to out.json.
"""
import csv
import gzip
import json
import subprocess
import sys
import time


def repeat(src, copies, dst):
    with gzip.open(src, 'rt', encoding='utf-8', newline='') as f:
        rows = list(csv.reader(f))
    head, body = rows[0], rows[1:]
    t_col, meta_col = head.index('timestamp'), head.index('metadata')
    first = min(int(r[t_col]) for r in body)
    requests = sum(1 for r in body if r[0] == 'http_reqs')
    with gzip.open(dst, 'wt', encoding='utf-8', newline='') as out:
        w = csv.writer(out, lineterminator='\n')
        w.writerow(head)
        for c in range(copies):
            shift = c * 1500
            for r in body:
                r2 = list(r)
                r2[t_col] = str(int(r[t_col]) + shift)
                if r2[meta_col]:
                    parts = dict(p.split('=', 1) for p in r2[meta_col].split('&') if '=' in p)
                    if 'vu' in parts:
                        parts['vu'] = str(int(parts['vu']) + 1000 * c)
                    r2[meta_col] = '&'.join(k + '=' + v for k, v in parts.items())
                w.writerow(r2)
    print(f'{copies} copies of {requests} requests: {copies * requests} requests, first at {first}')


def timed(label, out, command):
    t0 = time.perf_counter()
    rc = subprocess.run(command).returncode
    seconds = round(time.perf_counter() - t0, 2)
    try:
        with open(out, encoding='utf-8') as f:
            results = json.load(f)
    except (OSError, ValueError):
        results = []
    results.append({'label': label, 'seconds': seconds, 'exit': rc})
    with open(out, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(results, f, indent=1)
    print(f'{label}: {seconds} s, exit {rc}')
    return rc


if __name__ == '__main__':
    if sys.argv[1] == 'repeat':
        repeat(sys.argv[2], int(sys.argv[3]), sys.argv[4])
    elif sys.argv[1] == 'time':
        sep = sys.argv.index('--')
        sys.exit(timed(sys.argv[2], sys.argv[3], sys.argv[sep + 1:]))
    else:
        sys.exit('usage: measure-run-data.py repeat|time ...')
