#!/usr/bin/env python3
"""
Data integrity: the report's counts against JMeter's own (R07).

Compares one report's CI/CD JSON with JMeter's Aggregate Report, which AggregateReportReferee.java
computes with JMeter's own classes from two files of the same run:

  --every-row      the referee's CSV over the run's results.jtl (written with sub-results). Each label's
                   row counts every sample of that name, as the report's Samplers row does.
  --in-test-plan   the referee's CSV over the file the plan's own Simple Data Writer wrote with "Save
                   sub-results" off: one row per sample a listener in the test plan received. Its TOTAL
                   is the population of the report's whole-run figures (totalSamples, totalErrors), the
                   Aggregate Report in the test plan's TOTAL row.

Checked, exactly:
  - whole run: totalSamples and totalErrors equal the --in-test-plan TOTAL;
  - each sampler: present on both sides, sampleCount equal to its --every-row row, and its error count,
    which the JSON carries only as a 2-decimal errorRate, equal as well. The count is taken as the one
    whole number of errors that rounds to that rate; when two do (a row of more than about 10,000
    samples), the check says UNDECIDED rather than pass or fail;
  - with --rebuilt-report (the `jmeter -g` report of the same results.jtl): totalSamples, totalErrors and
    runId equal the run report's.

Mean, throughput and the percentiles are not compared here (TEST-COVERAGE-GAPS.md 11.3-11.5).

--require-no-nesting <jtl> refuses a results file with a transaction row or a renamed sub-result row.
R07 uses it on the -g fixtures' committed inputs, which have no in-test-plan file: their every row is
the population only because nothing in them nests.

Usage:
    python verify-data-integrity.py --fixture E02 --report E02-report.json \
        --every-row rows.csv --in-test-plan in-test-plan.csv [--rebuilt-report g.json] [--output out.csv]
    python verify-data-integrity.py --require-no-nesting input.jtl

Exit codes: 0 every check passed (UNDECIDED included), 1 a check failed, 2 an input could not be read.
"""

import argparse
import csv
import json
import math
import re
import sys

TRANSACTION_MARKER = 'Number of samples in transaction : '


def read_json(path, what):
    try:
        with open(path, encoding='utf-8') as f:
            return json.load(f)
    except (OSError, ValueError) as e:
        print(f'ERROR: could not read {what} {path}: {e}', file=sys.stderr)
        sys.exit(2)


def read_referee(path):
    """label -> (samples, errors), and the TOTAL row, which the referee writes last (a sampler that is
    itself named "TOTAL" comes before it)."""
    try:
        with open(path, encoding='utf-8', newline='') as f:
            entries = [(r['label'], (int(r['samples']), int(r['errors']))) for r in csv.DictReader(f)]
    except (OSError, ValueError, KeyError) as e:
        print(f'ERROR: could not read the referee output {path}: {e}', file=sys.stderr)
        sys.exit(2)
    if not entries or entries[-1][0] != 'TOTAL':
        print(f'ERROR: {path} does not end with a TOTAL row', file=sys.stderr)
        sys.exit(2)
    return dict(entries[:-1]), entries[-1][1]


def java_round2(v):
    """CiCdJsonSerializer.round2: Math.round(v * 100) / 100.0, half up."""
    return math.floor(v * 100 + 0.5) / 100.0


def errors_behind(rate, samples):
    """Every whole number of errors that a 2-decimal error rate of `samples` samples could stand for."""
    if samples <= 0:
        return [0]
    guess = int(round(rate * samples / 100.0))
    return [k for k in range(max(0, guess - 3), min(samples, guess + 3) + 1)
            if java_round2(k * 100.0 / samples) == rate]


def compare(fixture, report, every_row, in_test_plan_total, rebuilt):
    results = []

    def add(subject, metric, status, ours, jmeter, message):
        results.append({'fixture': fixture, 'subject': subject, 'metric': metric, 'status': status,
                        'report': ours, 'jmeter': jmeter, 'message': message})

    samples, errors = in_test_plan_total
    for metric, ours, theirs in (('totalSamples', report.get('totalSamples'), samples),
                                 ('totalErrors', report.get('totalErrors'), errors)):
        add('whole run', metric, 'PASS' if ours == theirs else 'FAIL', ours, theirs,
            'equals the Aggregate Report in the test plan' if ours == theirs
            else f'the Aggregate Report in the test plan counts {theirs}')

    ours_by_name = {s['name']: s for s in report.get('samplers', [])}
    for name, (n, e) in every_row.items():
        s = ours_by_name.get(name)
        if s is None:
            add(name, 'presence', 'FAIL', None, n, 'in the results file, missing from the report')
            continue
        count = s.get('sampleCount')
        add(name, 'sampleCount', 'PASS' if count == n else 'FAIL', count, n,
            'equal' if count == n else f'the Aggregate Report counts {n}')
        candidates = errors_behind(s.get('errorRate', 0), count or 0)
        if not candidates:
            add(name, 'errors', 'FAIL', s.get('errorRate'), e,
                f'errorRate {s.get("errorRate")} is no whole number of errors of {count}')
        elif e not in candidates:
            add(name, 'errors', 'FAIL', candidates[0] if len(candidates) == 1 else candidates, e,
                f'the Aggregate Report counts {e} errors')
        elif len(candidates) == 1:
            add(name, 'errors', 'PASS', e, e, 'equal')
        else:
            add(name, 'errors', 'UNDECIDED', candidates, e,
                f'errorRate {s.get("errorRate")} stands for any of {candidates} errors')
    for name in ours_by_name:
        if name not in every_row:
            add(name, 'presence', 'FAIL', ours_by_name[name].get('sampleCount'), None,
                'in the report, absent from the results file')

    if rebuilt is not None:
        for key in ('totalSamples', 'totalErrors', 'runId'):
            ours, theirs = report.get(key), rebuilt.get(key)
            add('rebuilt report', key, 'PASS' if ours == theirs else 'FAIL', ours, theirs,
                'the -g report agrees' if ours == theirs else f'the -g report says {theirs}')
    return results


def nesting_rows(jtl):
    """Rows that make a file's every row differ from what a listener in the plan received."""
    found = []
    try:
        with open(jtl, encoding='utf-8', newline='') as f:
            rows = list(csv.DictReader(f))
    except (OSError, ValueError) as e:
        print(f'ERROR: could not read {jtl}: {e}', file=sys.stderr)
        sys.exit(2)
    labels = {r.get('label') for r in rows}
    renamed = re.compile(r'^(.+)-\d+$')
    for i, r in enumerate(rows, start=2):
        if (r.get('responseMessage') or '').startswith(TRANSACTION_MARKER):
            found.append(f'line {i}: transaction row "{r.get("label")}"')
        m = renamed.match(r.get('label') or '')
        if m and m.group(1) in labels:
            found.append(f'line {i}: sub-result row "{r.get("label")}" of "{m.group(1)}"')
        if len(found) >= 5:
            break
    return found


def print_results(fixture, results):
    failed = [r for r in results if r['status'] == 'FAIL']
    undecided = [r for r in results if r['status'] == 'UNDECIDED']
    for r in failed + undecided:
        print(f'  [{fixture}] {r["status"]}: {r["subject"]} {r["metric"]}: report {r["report"]}, '
              f'JMeter {r["jmeter"]} ({r["message"]})')
    print(f'  [{fixture}] {len(results)} checks: {len(results) - len(failed) - len(undecided)} pass, '
          f'{len(failed)} fail, {len(undecided)} undecided')
    return not failed


def write_csv(results, path):
    with open(path, 'w', encoding='utf-8', newline='') as f:
        w = csv.DictWriter(f, fieldnames=['fixture', 'subject', 'metric', 'status', 'report', 'jmeter', 'message'])
        w.writeheader()
        w.writerows(results)


def main():
    p = argparse.ArgumentParser(description="The report's counts against JMeter's Aggregate Report (R07)")
    p.add_argument('--fixture', default='report')
    p.add_argument('--report', help="the report's CI/CD JSON")
    p.add_argument('--every-row', help="the referee's CSV over results.jtl")
    p.add_argument('--in-test-plan', help="the referee's CSV over the in-test-plan file")
    p.add_argument('--rebuilt-report', help='the -g report of the same results.jtl')
    p.add_argument('--require-no-nesting', metavar='JTL', help='refuse a file with transaction or sub-result rows')
    p.add_argument('--output', '-o', help='write every check to this CSV')
    a = p.parse_args()

    if a.require_no_nesting:
        found = nesting_rows(a.require_no_nesting)
        if found:
            print(f'  {a.require_no_nesting} nests samples, so its every row is not what a listener in the plan '
                  f'received: ' + '; '.join(found))
            sys.exit(1)
        sys.exit(0)

    if not (a.report and a.every_row and a.in_test_plan):
        p.error('--report, --every-row and --in-test-plan are required')
    report = read_json(a.report, 'the report')
    every_row, _ = read_referee(a.every_row)
    _, in_test_plan_total = read_referee(a.in_test_plan)
    rebuilt = read_json(a.rebuilt_report, 'the rebuilt report') if a.rebuilt_report else None

    results = compare(a.fixture, report, every_row, in_test_plan_total, rebuilt)
    ok = print_results(a.fixture, results)
    if a.output:
        write_csv(results, a.output)
    sys.exit(0 if ok else 1)


if __name__ == '__main__':
    main()
