#!/usr/bin/env python3
"""
Data Integrity Verification Script
Compares Web Insight Report JSON output against JMeter native statistics.json

Usage:
    python verify-data-integrity.py <our-report.json> <native-statistics.json> [--output results.csv]

Exit codes:
    0 = all checks pass
    1 = one or more checks failed
"""

import json
import sys
import csv
import argparse
from pathlib import Path

# Tolerance thresholds. Every entry here is actually compared — see METRIC_MAP below.
# The table used to advertise errorCount/min/max/p90 as well, but none of them was ever
# checked: the comparison loop only looked at sampleCount, mean, p95 and p99, so a
# throughput or error-rate divergence between the plugin and JMeter's own report passed
# "data integrity" silently. They are not listed now because the plugin's CI/CD JSON does
# not expose those four fields — adding them there is a separate change.
TOLERANCES = {
    'sampleCount': ('exact', 0),
    'mean': ('absolute', 1.0),         # ±1ms rounding
    'p95': ('percent', 5.0),           # ±5% (different percentile algorithms)
    'p99': ('percent', 5.0),
    'throughput': ('percent', 2.0),    # ±2% (different windows)
    'errorRate': ('absolute', 0.1),    # both sides report a percentage
}

# our CI/CD JSON key -> JMeter statistics.json key (already normalised in
# load_native_report). Keep in step with TOLERANCES.
METRIC_MAP = {
    'sampleCount': ('sampleCount', 'sampleCount'),
    'mean': ('mean', 'mean'),
    'p95': ('p95', 'p95'),
    'p99': ('p99', 'p99'),
    'throughput': ('throughput', 'throughput'),
    'errorRate': ('errorRate', 'errorRate'),
}


def load_our_report(path):
    """Load our web-insight-report.json"""
    with open(path) as f:
        data = json.load(f)
    samplers = {}
    for s in data.get('samplers', []):
        samplers[s['name']] = s
    return {
        'totalSamples': data.get('totalSamples', 0),
        'totalErrors': data.get('totalErrors', 0),
        'errorRate': data.get('errorRate', 0),
        'samplers': samplers,
    }


def load_native_report(path):
    """Load JMeter native statistics.json"""
    with open(path) as f:
        data = json.load(f)
    samplers = {}
    for name, stats in data.items():
        if name == 'Total':
            continue
        samplers[name] = {
            'name': name,
            'sampleCount': stats.get('sampleCount', 0),
            'errorCount': stats.get('errorCount', 0),
            'errorRate': stats.get('errorPct', 0),
            'mean': stats.get('meanResTime', 0),
            'min': stats.get('minResTime', 0),
            'max': stats.get('maxResTime', 0),
            'p90': stats.get('pct1ResTime', 0),  # JMeter default percentile labels
            'p95': stats.get('pct2ResTime', 0),
            'p99': stats.get('pct3ResTime', 0),
            'throughput': stats.get('throughput', 0),
        }
    return samplers


def check_metric(metric_name, our_value, native_value):
    """Compare a single metric within tolerance. Returns (pass, message)."""
    tol_type, tol_value = TOLERANCES.get(metric_name, ('exact', 0))

    if tol_type == 'exact':
        passed = our_value == native_value
        msg = f'{our_value} == {native_value}' if passed else f'{our_value} != {native_value}'
    elif tol_type == 'absolute':
        diff = abs(our_value - native_value)
        passed = diff <= tol_value
        msg = f'{our_value} vs {native_value} (diff={diff:.2f}, tol=±{tol_value})'
    elif tol_type == 'percent':
        if native_value == 0:
            passed = our_value == 0
            msg = f'{our_value} vs {native_value} (baseline=0)'
        else:
            pct_diff = abs(our_value - native_value) / abs(native_value) * 100
            passed = pct_diff <= tol_value
            msg = f'{our_value:.2f} vs {native_value:.2f} (diff={pct_diff:.1f}%, tol=±{tol_value}%)'
    else:
        passed = True
        msg = 'unknown tolerance type'

    return passed, msg


def compare(our_data, native_samplers):
    """Compare all metrics. Returns list of result dicts."""
    results = []
    all_pass = True

    for name, native in native_samplers.items():
        our = our_data['samplers'].get(name)
        if our is None:
            results.append({
                'sampler': name,
                'metric': 'presence',
                'status': 'FAIL',
                'message': f'Sampler missing from our report',
            })
            all_pass = False
            continue

        for metric, (our_key, native_key) in METRIC_MAP.items():
            our_val = our.get(our_key, 0)
            native_val = native.get(native_key, 0)

            if isinstance(our_val, str):
                try:
                    our_val = float(our_val)
                except ValueError:
                    our_val = 0

            passed, msg = check_metric(metric, our_val, native_val)
            status = 'PASS' if passed else 'FAIL'
            if not passed:
                all_pass = False

            results.append({
                'sampler': name,
                'metric': metric,
                'status': status,
                'our_value': our_val,
                'native_value': native_val,
                'message': msg,
            })

    # Reverse presence check: the loop above only walks JMeter's samplers, so a sampler
    # that exists ONLY in our report — a phantom invented by a parsing bug, say — went
    # completely unnoticed.
    for name in our_data['samplers']:
        if name not in native_samplers:
            results.append({
                'sampler': name,
                'metric': 'presence',
                'status': 'FAIL',
                'message': 'Sampler present in our report but absent from JMeter\'s',
            })
            all_pass = False

    return results, all_pass


def print_results(results, all_pass):
    """Print colorized results table."""
    print()
    print('=' * 80)
    print('  DATA INTEGRITY VERIFICATION RESULTS')
    print('=' * 80)
    print(f'  {"Sampler":<35} {"Metric":<15} {"Status":<8} {"Details"}')
    print('-' * 80)

    for r in results:
        status_str = r['status']
        mark = '+' if status_str == 'PASS' else 'X'
        sampler_short = r['sampler'][:34]
        print(f'  {sampler_short:<35} {r["metric"]:<15} [{mark}] {r["message"]}')

    print('-' * 80)
    passed = sum(1 for r in results if r['status'] == 'PASS')
    failed = sum(1 for r in results if r['status'] == 'FAIL')
    print(f'  TOTAL: {len(results)}   PASS: {passed}   FAIL: {failed}')
    print(f'  OVERALL: {"PASS" if all_pass else "FAIL"}')
    print('=' * 80)


def write_csv(results, output_path):
    """Write results to CSV."""
    with open(output_path, 'w', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=['sampler', 'metric', 'status', 'our_value', 'native_value', 'message'])
        writer.writeheader()
        writer.writerows(results)
    print(f'  Results written to: {output_path}')


def main():
    parser = argparse.ArgumentParser(description='Verify data integrity between Web Insight and native JMeter reports')
    parser.add_argument('our_report', help='Path to web-insight-report.json')
    parser.add_argument('native_stats', help='Path to JMeter native statistics.json')
    parser.add_argument('--output', '-o', default=None, help='Output CSV path')
    args = parser.parse_args()

    # A missing or malformed file is an operator mistake, not a stack trace.
    try:
        our_data = load_our_report(args.our_report)
    except (OSError, ValueError) as e:
        print(f'ERROR: could not read our report {args.our_report}: {e}', file=sys.stderr)
        sys.exit(2)
    try:
        native_samplers = load_native_report(args.native_stats)
    except (OSError, ValueError) as e:
        print(f'ERROR: could not read JMeter statistics {args.native_stats}: {e}', file=sys.stderr)
        print('       Generate it with: jmeter -g <results.jtl> -o <dir>', file=sys.stderr)
        sys.exit(2)

    results, all_pass = compare(our_data, native_samplers)
    print_results(results, all_pass)

    if args.output:
        write_csv(results, args.output)

    sys.exit(0 if all_pass else 1)


if __name__ == '__main__':
    main()
