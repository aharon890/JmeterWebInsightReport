#!/usr/bin/env bash
# =============================================================================
#  Web Insight Report — Sanity suite (~2 minutes)
#
#  The fast cycle: run it constantly while working. It proves the code compiles,
#  the unit tests pass, the fat JAR is correctly relocated, the plugin still
#  loads inside a real JMeter and produces a report, and the browser suite
#  passes against the fixtures currently on disk.
#
#  What it does NOT do is regenerate those fixtures. If you changed report.js,
#  the template, the CSS or anything under report/template/, this suite runs the
#  browser tests against output from an older build — so it checks that first and
#  tells you to run run-regression.sh instead. Green here with stale fixtures
#  would be exactly the false pass the provenance stamps exist to prevent.
#
#  Exit code is the number of failed stages, so CI and the pre-push hook can
#  consume it directly.
# =============================================================================
set -uo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../../scripts/lib/common.sh"

print_banner "Sanity Suite — Web Insight Report"

require_jmeter

# --- 1. Build (also compiles the tests) --------------------------------------
if build_plugin; then
    record S01 "Build + shaded JAR relocation guard" PASS
else
    record S01 "Build + shaded JAR relocation guard" FAIL "see output above"
    print_summary "SANITY SUMMARY"
    exit $FAIL
fi

# --- 2. Unit tests ------------------------------------------------------------
# Both languages. The JS half runs report.js in node behind two stubs and costs about
# a tenth of a second, so there is no tier it is worth leaving out of.
unit_fail=0
run_java_tests || unit_fail=1
run_js_unit_tests || unit_fail=1
if [[ $unit_fail -eq 0 ]]; then
    record S02 "Unit tests (Java + report.js)" PASS
else
    record S02 "Unit tests (Java + report.js)" FAIL "see output above"
fi

# --- 3. Static checks ---------------------------------------------------------
# Cheap, and the runners are how everything else gets executed — a syntax error
# here means the regression suite cannot run at all. The other three ride along
# because they cost nothing and every one of those failures is otherwise silent: a
# fixture list that drifted is generated-but-never-asserted, a hook committed 100644 is
# skipped without a word on Linux and macOS, and a CRLF blob stays invisible behind
# git's stat cache until the next change to that file becomes a whole-file diff.
#
# These three were also CI steps until the GitHub workflows were removed. They live
# here now, which is the only place they still run.
static_fail=0
for f in "$PROJECT_DIR"/scripts/*.sh "$PROJECT_DIR"/scripts/lib/*.sh \
         "$PROJECT_DIR"/test-plans/scripts/*.sh; do
    [[ -e "$f" ]] || continue
    bash -n "$f" 2>/dev/null || { echo "  SYNTAX FAIL: $f" >&2; static_fail=1; }
done
verify_fixture_lists  || static_fail=1
verify_script_modes   || static_fail=1
verify_line_endings   || static_fail=1
verify_no_nul_bytes   || static_fail=1
verify_report_js_rules || static_fail=1
verify_release_tier   || static_fail=1
verify_jq_idioms      || static_fail=1
selftest_report_js_rules || static_fail=1
selftest_no_nul_bytes    || static_fail=1
selftest_goldens_accept  || static_fail=1
selftest_jq_idioms       || static_fail=1
selftest_purge_old_plugin_jars || static_fail=1
selftest_fixture_stamps  || static_fail=1
selftest_failed_regeneration || static_fail=1
selftest_report_gate_verdict || static_fail=1
if [[ $static_fail -eq 0 ]]; then
    record S03 "Static checks (syntax, fixture lists, exec bits, line endings, NUL bytes, report.js rules, self-tests)" PASS
else
    record S03 "Static checks (syntax, fixture lists, exec bits, line endings, NUL bytes, report.js rules, self-tests)" FAIL "see output above"
fi

# --- 4. Live JMeter smoke -----------------------------------------------------
# See run_live_smoke in common.sh — it deletes the report before running, so the
# assertion cannot be satisfied by the previous cycle's output.
if deploy_plugin >/dev/null; then
    if run_live_smoke; then
        record S04 "JMeter listener smoke (E24)" PASS
    else
        record S04 "JMeter listener smoke (E24)" FAIL "no report generated — see $RESULTS_DIR/E24/jmeter.log"
    fi
else
    record S04 "JMeter listener smoke (E24)" FAIL "deploy failed"
fi

# --- 5. Browser tests, only if the fixtures still mean something --------------
if fixtures_current; then
    if run_playwright; then
        record S05 "Playwright ($(playwright_test_count) tests, existing fixtures)" PASS
    else
        record S05 "Playwright ($(playwright_test_count) tests, existing fixtures)" FAIL "see test-results/playwright-report/"
    fi
else
    # Two plugin JARs means S04's deploy failed on one it could not remove, and regression would
    # fail at R01 on the same file, so the advice is to clear lib/ext first.
    _scan_plugin_jars
    if [[ $_PLUGIN_JAR_N -gt 1 ]]; then
        record S05 "Playwright" FAIL "lib/ext holds $_PLUGIN_JAR_N plugin JARs — clear it and redeploy first"
    else
        record S05 "Playwright" FAIL "fixtures are stale — run run-regression.sh"
    fi
    echo ""
    echo "  Fixtures no longer match the working tree:"
    stale_fixture_reason | sed 's/^/    /'
    echo ""
    echo "  Sanity cannot verify report rendering without regenerating them."
    if [[ $_PLUGIN_JAR_N -gt 1 ]]; then
        echo "  Move the stale JAR out of lib/ext first (S04's error names it and prints the"
        echo "  Move-Item line), then run ./scripts/build-and-deploy.sh, then"
        echo "  ./test-plans/scripts/run-regression.sh."
    else
        echo "  Run: ./test-plans/scripts/run-regression.sh"
    fi
fi

print_summary "SANITY SUMMARY"
exit $FAIL
