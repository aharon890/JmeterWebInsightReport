#!/usr/bin/env python3
"""
Generate an HTML test execution report from test results.
Combines JUnit (mvn test), JMeter execution tests, and Playwright results.

Usage:
    python generate-html-report.py [--output report.html]
"""

import html
import json
import os
import re
import sys
import argparse
from datetime import datetime
from pathlib import Path


def status_badge_class(status):
    """Badge class for a test status.

    SKIP is neither a pass nor a failure; rendering it with the failure badge made skipped
    tests look like red results in a report whose whole job is to say what went wrong.
    """
    if status == 'PASS':
        return 'badge-pass'
    if status == 'SKIP':
        return 'badge-skip'
    return 'badge-fail'


def esc(value):
    """HTML-escape a value before it goes into the report markup.

    Test names and sampler labels routinely contain & and <, which used to break the
    surrounding markup (and would render injected tags verbatim).
    """
    return html.escape('' if value is None else str(value), quote=True)


def get_project_root():
    return Path(__file__).resolve().parent.parent.parent


def detect_plugin_version(project_root):
    """Read the version from the parent pom, the same way scripts/lib/common.sh does.

    The header used to hardcode "Plugin: 1.0.5" — the exact version-drift pattern the
    audit called out, mislabelling every report generated after a version bump.
    """
    pom = project_root / 'pom.xml'
    try:
        text = pom.read_text(encoding='utf-8')
    except OSError:
        return 'unknown'
    anchor = text.find('<artifactId>jmeter-web-insight-suite</artifactId>')
    if anchor < 0:
        return 'unknown'
    m = re.search(r'<version>([^<]+)</version>', text[anchor:])
    return m.group(1).strip() if m else 'unknown'


def collect_junit_results(project_root):
    """Parse Maven surefire XML reports."""
    results = []
    for module in ['jmeter-report-core', 'jmeter-web-insight-report']:
        reports_dir = project_root / module / 'target' / 'surefire-reports'
        if not reports_dir.exists():
            continue
        for xml_file in reports_dir.glob('TEST-*.xml'):
            try:
                import xml.etree.ElementTree as ET
                tree = ET.parse(xml_file)
                root = tree.getroot()
                suite_name = root.get('name', xml_file.stem)
                tests = int(root.get('tests', 0))
                failures = int(root.get('failures', 0))
                errors = int(root.get('errors', 0))
                skipped = int(root.get('skipped', 0))
                time_val = float(root.get('time', 0))

                for tc in root.findall('testcase'):
                    tc_name = tc.get('name', '')
                    tc_class = tc.get('classname', suite_name)
                    tc_time = float(tc.get('time', 0))
                    failure = tc.find('failure')
                    error = tc.find('error')
                    skip = tc.find('skipped')
                    if failure is not None:
                        status = 'FAIL'
                        message = failure.get('message', '')
                    elif error is not None:
                        status = 'ERROR'
                        message = error.get('message', '')
                    elif skip is not None:
                        status = 'SKIP'
                        message = ''
                    else:
                        status = 'PASS'
                        message = ''
                    results.append({
                        'module': module.replace('jmeter-', ''),
                        'class': tc_class.split('.')[-1],
                        'test': tc_name,
                        'status': status,
                        'time': tc_time,
                        'message': message,
                    })
            except Exception as e:
                print(f'Warning: Could not parse {xml_file}: {e}', file=sys.stderr)
    return results


def collect_jmeter_results(project_root):
    """Collect JMeter execution test results from test-results/ directory."""
    results_dir = project_root / 'test-results'
    results = []

    test_defs = [
        ('E01', 'Happy Path Smoke', 'functional'),
        ('E02', 'Full Load Test (120s)', 'functional'),
        ('E03', 'Error Grouping', 'functional'),
        ('E04', 'Single Sampler', 'edge_case'),
        ('E05', 'Many Samplers (20)', 'edge_case'),
        ('E06', 'Zero Errors', 'edge_case'),
        ('E07', '100% Error Rate', 'edge_case'),
        ('E08', 'Long Sampler Names', 'edge_case'),
        ('E09', 'CLI Properties Override', 'configuration'),
        ('E10', 'Asserts Pass (Lenient)', 'configuration'),
        ('E11', 'Asserts Fail (Strict)', 'configuration'),
        ('E12', 'Full Annotations', 'configuration'),
        ('E13', 'Timeline Markers Only', 'configuration'),
        ('E15', 'Response Code Grouping', 'configuration'),
        # E18 is run by run-all-tests.sh but was missing here, so its results never
        # appeared in the roll-up — and unlike a missing report it was not even
        # reported as FAIL, just absent.
        ('E18', 'High Volume Stress (100 threads)', 'stress'),
        ('E19', 'Many Samplers Stress', 'stress'),
        ('E22', 'Report Stress', 'stress'),
        ('E24', 'Minimal Data', 'regression'),
    ]

    for tid, name, category in test_defs:
        html_path = results_dir / tid / f'{tid}-report.html'
        json_path = results_dir / tid / f'{tid}-report.json'
        html_ok = html_path.exists()
        json_ok = json_path.exists()
        status = 'PASS' if (html_ok and json_ok) else 'FAIL'
        samples = errors = 0
        error_rate = 0.0
        assert_status = 'N/A'
        if json_ok:
            try:
                with open(json_path) as f:
                    d = json.load(f)
                samples = d.get('totalSamples', 0)
                errors = d.get('totalErrors', 0)
                error_rate = d.get('errorRate', 0)
                assert_status = d.get('status', 'UNKNOWN')
            except (OSError, ValueError):
                pass
        results.append({
            'id': tid, 'name': name, 'category': category,
            'status': status, 'samples': samples, 'errors': errors,
            'error_rate': error_rate, 'assert_status': assert_status,
        })

    # Docker — run-docker-tests.sh runs BOTH E16 and E17; only E16 was collected here,
    # so the Full Load container scenario never showed up in the roll-up.
    for tid, name in (('E16', 'Docker Basic'), ('E17', 'Docker Full Load')):
        docker_html = results_dir / 'docker' / tid / f'{tid}-docker-report.html'
        docker_json = results_dir / 'docker' / tid / f'{tid}-docker-report.json'
        docker_ok = docker_html.exists() and docker_json.exists()
        d_samples = d_errors = 0
        d_rate = 0.0
        if docker_ok:
            try:
                with open(docker_json) as f:
                    d = json.load(f)
                d_samples = d.get('totalSamples', 0)
                d_errors = d.get('totalErrors', 0)
                d_rate = d.get('errorRate', 0)
            except (OSError, ValueError):
                pass
        results.append({
            'id': tid, 'name': name, 'category': 'environment',
            'status': 'PASS' if docker_ok else 'FAIL',
            'samples': d_samples, 'errors': d_errors,
            'error_rate': d_rate, 'assert_status': 'N/A',
        })

    return results


def collect_playwright_results(project_root):
    """Collect Playwright test results from JSON report."""
    results = []
    pw_json = project_root / 'test-results' / 'playwright-results.json'
    if not pw_json.exists():
        return results
    try:
        with open(pw_json) as f:
            data = json.load(f)

        def walk_suites(suites, file_name=''):
            for suite in suites:
                fname = suite.get('file', file_name) or file_name
                title = suite.get('title', fname)
                # Process specs at this level
                for spec in suite.get('specs', []):
                    test_name = spec.get('title', '')
                    for test in spec.get('tests', []):
                        for result in test.get('results', []):
                            status = result.get('status', 'unknown')
                            duration = result.get('duration', 0)
                            results.append({
                                'file': title,
                                'test': test_name,
                                'status': 'PASS' if status in ('passed', 'expected') else 'FAIL',
                                'time': duration / 1000.0,
                            })
                # Recurse into nested suites
                if suite.get('suites'):
                    walk_suites(suite['suites'], fname)

        walk_suites(data.get('suites', []))
    except Exception as e:
        print(f'Warning: Could not parse playwright results: {e}', file=sys.stderr)
    return results


def generate_html(junit_results, jmeter_results, playwright_results, output_path, plugin_version="unknown"):
    """Generate a self-contained HTML report."""
    now = datetime.now().strftime('%Y-%m-%d %H:%M:%S')

    # A surefire "error" (test threw) is a failure, not a pass. Counting only 'FAIL'
    # let a run full of errored tests render a green PASS badge. SKIP is neither, so it
    # is excluded from the total rather than silently inflating the pass ratio.
    junit_pass = sum(1 for r in junit_results if r['status'] == 'PASS')
    junit_fail = sum(1 for r in junit_results if r['status'] in ('FAIL', 'ERROR'))
    junit_skip = sum(1 for r in junit_results if r['status'] == 'SKIP')
    junit_total = len(junit_results) - junit_skip

    jmeter_pass = sum(1 for r in jmeter_results if r['status'] == 'PASS')
    jmeter_fail = sum(1 for r in jmeter_results if r['status'] == 'FAIL')
    jmeter_total = len(jmeter_results)

    pw_pass = sum(1 for r in playwright_results if r['status'] == 'PASS')
    pw_fail = sum(1 for r in playwright_results if r['status'] == 'FAIL')
    pw_total = len(playwright_results)

    grand_total = junit_total + jmeter_total + pw_total
    grand_pass = junit_pass + jmeter_pass + pw_pass
    grand_fail = junit_fail + jmeter_fail + pw_fail
    overall = 'PASS' if grand_fail == 0 else 'FAIL'

    # Group JUnit by class
    junit_by_class = {}
    for r in junit_results:
        key = r['class']
        junit_by_class.setdefault(key, []).append(r)

    # Build HTML
    html = f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Web Insight Report — Test Execution Report</title>
<style>
* {{ margin: 0; padding: 0; box-sizing: border-box; }}
body {{ font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #f5f7fa; color: #1a1a2e; line-height: 1.5; }}
.container {{ max-width: 1200px; margin: 0 auto; padding: 20px; }}
header {{ background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%); color: #fff; padding: 30px 40px; border-radius: 12px; margin-bottom: 24px; }}
header h1 {{ font-size: 1.8rem; margin-bottom: 6px; }}
header .subtitle {{ opacity: 0.8; font-size: 0.9rem; }}
.summary-cards {{ display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 16px; margin-bottom: 24px; }}
.card {{ background: #fff; border-radius: 10px; padding: 20px; text-align: center; box-shadow: 0 2px 8px rgba(0,0,0,0.06); }}
.card .value {{ font-size: 2rem; font-weight: 700; }}
.card .label {{ font-size: 0.85rem; color: #666; margin-top: 4px; }}
.card.pass .value {{ color: #22c55e; }}
.card.fail .value {{ color: #ef4444; }}
.card.neutral .value {{ color: #3b82f6; }}
.badge {{ display: inline-block; padding: 3px 10px; border-radius: 4px; font-size: 0.75rem; font-weight: 700; text-transform: uppercase; }}
.badge-pass {{ background: #dcfce7; color: #166534; }}
.badge-fail {{ background: #fee2e2; color: #991b1b; }}
.badge-warn {{ background: #fef9c3; color: #854d0e; }}
.badge-skip {{ background: #f3f4f6; color: #6b7280; }}
.badge-unknown {{ background: #e0e7ff; color: #3730a3; }}
section {{ background: #fff; border-radius: 10px; padding: 24px; margin-bottom: 20px; box-shadow: 0 2px 8px rgba(0,0,0,0.06); }}
section h2 {{ font-size: 1.2rem; margin-bottom: 16px; padding-bottom: 8px; border-bottom: 2px solid #e5e7eb; }}
table {{ width: 100%; border-collapse: collapse; font-size: 0.85rem; }}
th {{ background: #f8fafc; padding: 10px 12px; text-align: left; font-weight: 600; border-bottom: 2px solid #e5e7eb; position: sticky; top: 0; }}
td {{ padding: 8px 12px; border-bottom: 1px solid #f1f5f9; }}
tr:hover {{ background: #f8fafc; }}
.class-header {{ background: #f0f4ff; font-weight: 600; }}
.class-header td {{ padding: 10px 12px; color: #1e40af; border-bottom: 2px solid #dbeafe; }}
.mono {{ font-family: 'JetBrains Mono', 'Fira Code', monospace; font-size: 0.8rem; }}
.time {{ color: #6b7280; font-size: 0.8rem; }}
.category {{ font-size: 0.75rem; padding: 2px 8px; border-radius: 3px; background: #f1f5f9; color: #475569; }}
.progress-bar {{ height: 8px; background: #e5e7eb; border-radius: 4px; overflow: hidden; margin-top: 8px; }}
.progress-fill {{ height: 100%; border-radius: 4px; transition: width 0.3s; }}
.progress-fill.green {{ background: #22c55e; }}
.progress-fill.red {{ background: #ef4444; }}
.tab-nav {{ display: flex; gap: 4px; margin-bottom: 20px; }}
.tab-btn {{ padding: 8px 20px; border: none; background: #e5e7eb; border-radius: 6px 6px 0 0; cursor: pointer; font-size: 0.9rem; font-weight: 500; }}
.tab-btn.active {{ background: #fff; box-shadow: 0 -2px 8px rgba(0,0,0,0.06); }}
.tab-panel {{ display: none; }}
.tab-panel.active {{ display: block; }}
.overall-badge {{ font-size: 1.1rem; padding: 6px 20px; border-radius: 6px; font-weight: 700; }}
.assert-status {{ font-size: 0.75rem; }}
footer {{ text-align: center; padding: 20px; color: #9ca3af; font-size: 0.8rem; }}
</style>
</head>
<body>
<div class="container">
<header>
  <h1>Web Insight Report — Test Execution Report</h1>
  <div class="subtitle">Generated: {now} | Plugin: {plugin_version} | JMeter: 5.6.3 | Java: 17</div>
</header>

<div class="summary-cards">
  <div class="card {'pass' if overall == 'PASS' else 'fail'}">
    <div class="value">{overall}</div>
    <div class="label">Overall Status</div>
  </div>
  <div class="card neutral">
    <div class="value">{grand_pass}/{grand_total}</div>
    <div class="label">Total Tests Passed</div>
    <div class="progress-bar"><div class="progress-fill {'green' if grand_fail == 0 else 'red'}" style="width:{grand_pass*100//max(grand_total,1)}%"></div></div>
  </div>
  <div class="card {'pass' if junit_fail == 0 else 'fail'}">
    <div class="value">{junit_pass}/{junit_total}</div>
    <div class="label">JUnit Tests</div>
  </div>
  <div class="card {'pass' if jmeter_fail == 0 else 'fail'}">
    <div class="value">{jmeter_pass}/{jmeter_total}</div>
    <div class="label">JMeter Execution</div>
  </div>
  <div class="card {'pass' if pw_fail == 0 else 'fail'}">
    <div class="value">{pw_pass}/{pw_total}</div>
    <div class="label">Playwright Browser</div>
  </div>
  <div class="card fail" style="{'display:none' if grand_fail == 0 else ''}">
    <div class="value">{grand_fail}</div>
    <div class="label">Failures</div>
  </div>
</div>

<div class="tab-nav">
  <button class="tab-btn active" onclick="showTab('junit')">JUnit ({junit_total})</button>
  <button class="tab-btn" onclick="showTab('jmeter')">JMeter ({jmeter_total})</button>
  <button class="tab-btn" onclick="showTab('playwright')">Playwright ({pw_total})</button>
</div>

<div id="tab-junit" class="tab-panel active">
<section>
<h2>Java Automation Tests (JUnit 5)</h2>
<table>
<thead><tr><th>Test</th><th>Status</th><th>Time</th></tr></thead>
<tbody>
"""

    for cls, tests in sorted(junit_by_class.items()):
        cls_pass = sum(1 for t in tests if t['status'] == 'PASS')
        cls_total = len(tests)
        cls_badge = 'badge-pass' if cls_pass == cls_total else 'badge-fail'
        html += f'<tr class="class-header"><td colspan="2">{esc(cls)} <span class="badge {cls_badge}">{cls_pass}/{cls_total}</span></td><td class="time">{sum(t["time"] for t in tests):.3f}s</td></tr>\n'
        for t in tests:
            badge = status_badge_class(t['status'])
            html += f'<tr><td class="mono" style="padding-left:28px">{esc(t["test"])}</td><td><span class="badge {badge}">{t["status"]}</span></td><td class="time">{t["time"]:.3f}s</td></tr>\n'

    html += """</tbody></table>
</section>
</div>

<div id="tab-jmeter" class="tab-panel">
<section>
<h2>JMeter Execution Tests</h2>
<table>
<thead><tr><th>ID</th><th>Test Name</th><th>Category</th><th>Status</th><th>Samples</th><th>Error Rate</th><th>Asserts</th></tr></thead>
<tbody>
"""

    for r in jmeter_results:
        badge = status_badge_class(r['status'])
        assert_badge = ''
        if r['assert_status'] == 'PASS':
            assert_badge = '<span class="badge badge-pass">PASS</span>'
        elif r['assert_status'] == 'FAIL':
            assert_badge = '<span class="badge badge-fail">FAIL</span>'
        elif r['assert_status'] == 'WARN':
            assert_badge = '<span class="badge badge-warn">WARN</span>'
        else:
            assert_badge = '<span class="assert-status" style="color:#999">-</span>'
        html += f'<tr><td><strong>{esc(r["id"])}</strong></td><td>{esc(r["name"])}</td><td><span class="category">{esc(r["category"])}</span></td><td><span class="badge {badge}">{r["status"]}</span></td><td>{r["samples"]:,}</td><td>{r["error_rate"]:.1f}%</td><td>{assert_badge}</td></tr>\n'

    html += """</tbody></table>
</section>
</div>

<div id="tab-playwright" class="tab-panel">
<section>
<h2>Playwright Browser Tests</h2>
<table>
<thead><tr><th>Spec File</th><th>Test</th><th>Status</th><th>Time</th></tr></thead>
<tbody>
"""

    if playwright_results:
        for r in playwright_results:
            badge = status_badge_class(r['status'])
            html += f'<tr><td class="mono">{esc(r["file"])}</td><td>{esc(r["test"])}</td><td><span class="badge {badge}">{r["status"]}</span></td><td class="time">{r["time"]:.1f}s</td></tr>\n'
    else:
        html += '<tr><td colspan="4" style="text-align:center;color:#999;padding:20px">No Playwright results found. Run: cd test-plans/playwright && npm test</td></tr>\n'

    html += f"""</tbody></table>
</section>
</div>

<footer>
  Web Insight Report Test Suite | Generated {now}
</footer>
</div>

<script>
function showTab(name) {{
  document.querySelectorAll('.tab-panel').forEach(p => p.classList.remove('active'));
  document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
  document.getElementById('tab-' + name).classList.add('active');
  event.target.classList.add('active');
}}
</script>
</body>
</html>"""

    with open(output_path, 'w', encoding='utf-8') as f:
        f.write(html)
    print(f'HTML report written to: {output_path}')


def main():
    parser = argparse.ArgumentParser(description='Generate HTML test execution report')
    parser.add_argument('--output', '-o', default=None, help='Output HTML path')
    args = parser.parse_args()

    project_root = get_project_root()
    output = args.output or str(project_root / 'test-results' / 'test-report.html')

    os.makedirs(os.path.dirname(output), exist_ok=True)

    junit_results = collect_junit_results(project_root)
    jmeter_results = collect_jmeter_results(project_root)
    playwright_results = collect_playwright_results(project_root)

    generate_html(junit_results, jmeter_results, playwright_results, output,
                  detect_plugin_version(project_root))

    # Print summary
    jt = len(junit_results)
    jp = sum(1 for r in junit_results if r['status'] == 'PASS')
    mt = len(jmeter_results)
    mp = sum(1 for r in jmeter_results if r['status'] == 'PASS')
    pt = len(playwright_results)
    pp = sum(1 for r in playwright_results if r['status'] == 'PASS')
    total = jt + mt + pt
    passed = jp + mp + pp
    print(f'JUnit: {jp}/{jt} | JMeter: {mp}/{mt} | Playwright: {pp}/{pt} | Total: {passed}/{total}')

    # Exit non-zero when anything failed. Every other runner in this project exits with its
    # failure count so CI can consume it; this one always exited 0, so a pipeline step that
    # generated the roll-up could never fail on it — including when the roll-up itself says
    # FAIL. ERROR counts as a failure; SKIP does not.
    failures = (
        sum(1 for r in junit_results if r['status'] in ('FAIL', 'ERROR'))
        + sum(1 for r in jmeter_results if r['status'] in ('FAIL', 'ERROR'))
        + sum(1 for r in playwright_results if r['status'] in ('FAIL', 'ERROR'))
    )
    if failures:
        print(f'{failures} failing test(s)', file=sys.stderr)
    sys.exit(1 if failures else 0)


if __name__ == '__main__':
    main()
