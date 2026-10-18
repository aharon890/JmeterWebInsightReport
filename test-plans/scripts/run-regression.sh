#!/usr/bin/env bash
# =============================================================================
#  Web Insight Report — Regression suite (~5½ min, ~1½ when fixtures are current)
#
#  Everything the sanity suite covers, plus the part it deliberately skips:
#  regenerating the report fixtures from real JMeter runs and then asserting
#  against them with the full browser suite.
#
#  It regenerates only the 9 scenarios Playwright actually opens (E02, E05-E12)
#  plus the CMP comparison fixture. The other 8 in run-all-tests.sh (E01, E03,
#  E04, E13, E15, E20, E22, E24) are validated only by "an HTML and a JSON file
#  appeared" — E20 additionally checks the external-data payload — and all 19
#  run in ~4½ minutes. Run run-all-tests.sh before a release.
#
#  Regeneration is skipped when the provenance stamps say the fixtures were built
#  from exactly the source now in the tree — the common case for a change that
#  touched no plugin source at all. The stamps hash every file under both modules'
#  src/main, so "could this have changed a report?" is decided by content rather
#  than by a list of paths someone has to keep current. Force with --force-fixtures.
#
#  When regeneration IS skipped, the live E24 smoke still runs: without it a skipped
#  cycle launches JMeter zero times and cannot notice the plugin failing to load in
#  lib/ext, which made this tier quietly weaker than sanity.
#
#  Exit code is the number of failed stages.
# =============================================================================
set -uo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../../scripts/lib/common.sh"

FORCE_FIXTURES=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --force-fixtures) FORCE_FIXTURES=1; shift ;;
        -h|--help) sed -n '2,25p' "$0"; exit 0 ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done

print_banner "Regression Suite — Web Insight Report"

require_jmeter

# --- 1. Build and deploy ------------------------------------------------------
# Deploy, not just build. The fixture stage runs real JMeter against JMX files
# that name the listener class, so the shaded JAR has to be in $JMETER_HOME/lib/ext
# or every scenario dies with CannotResolveClassException before a single sample.
# Building alone was enough on a developer machine that already had the plugin
# installed from earlier work, and failed on every fresh checkout.
if build_plugin && deploy_plugin; then
    record R01 "Build, deploy + shaded JAR relocation guard" PASS
else
    record R01 "Build, deploy + shaded JAR relocation guard" FAIL "see output above"
    print_summary "REGRESSION SUMMARY"
    exit $FAIL
fi

# --- 2. Unit tests ------------------------------------------------------------
# Both languages. The JS half runs report.js in node behind two stubs and costs about
# a tenth of a second, so there is no tier it is worth leaving out of.
unit_fail=0
run_java_tests || unit_fail=1
run_js_unit_tests || unit_fail=1
if [[ $unit_fail -eq 0 ]]; then
    record R02 "Unit tests (Java + report.js)" PASS
else
    record R02 "Unit tests (Java + report.js)" FAIL "see output above"
fi

# --- 3. Static checks ---------------------------------------------------------
# These were CI steps until the GitHub workflows were removed. Since this suite is
# what the run-tests skill runs before a change is pushed — and therefore before it
# can be merged — they belong here as well as in sanity, or they would only ever run
# on whichever tier a given change happened to get.
#
# All four fail silently in the wild: a syntax error means the next runner cannot
# start, a drifted fixture list means something is generated and never asserted on
# (or demanded and never generated), a hook committed 100644 is skipped without a
# word on Linux and macOS — which would disable this very gate — and a CRLF blob hides
# behind git's stat cache until something touches the file, then turns the next change
# to it into a whole-file diff nobody can review.
static_fail=0
for f in "$PROJECT_DIR"/scripts/*.sh "$PROJECT_DIR"/scripts/lib/*.sh \
         "$PROJECT_DIR"/test-plans/scripts/*.sh; do
    [[ -e "$f" ]] || continue
    bash -n "$f" 2>/dev/null || { echo "  SYNTAX FAIL: $f" >&2; static_fail=1; }
done
verify_fixture_lists || static_fail=1
verify_script_modes  || static_fail=1
verify_line_endings  || static_fail=1
verify_no_nul_bytes  || static_fail=1
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
    record R03 "Static checks (syntax, fixture lists, exec bits, line endings, NUL bytes, report.js rules, self-tests)" PASS
else
    record R03 "Static checks (syntax, fixture lists, exec bits, line endings, NUL bytes, report.js rules, self-tests)" FAIL "see output above"
fi

# --- 4. Fixtures --------------------------------------------------------------
# The expensive stage, and the only one worth avoiding. Skipping it when the
# stamps match is not a shortcut: identical source produces an identical report,
# which is the same reasoning global-setup.ts uses to decide the fixtures are
# still valid to test against.
#
# A drifted fixture list (caught above) makes this stage generate a different set
# than the browser stage demands, so stop rather than spend minutes on it.
if ! verify_fixture_lists >/dev/null 2>&1; then
    record R04 "Report fixtures" FAIL "FIXTURE_IDS and REQUIRED_FIXTURES disagree — see R03"
    record R05 "Playwright ($(playwright_test_count) tests)" FAIL "skipped: fixture lists disagree"
    print_summary "REGRESSION SUMMARY"
    exit $FAIL
fi

if [[ $FORCE_FIXTURES -eq 0 ]] && fixtures_current; then
    echo "[FIXTURES] Current — regeneration skipped (--force-fixtures to override)."
    # Skipping regeneration also skips every JMeter execution in this suite, and that
    # was measured, not theorised: a full regression run took 106 seconds and left the
    # E24 report's mtime untouched. So the tier named "regression" could pass without
    # ever loading the plugin into a JMeter — while sanity, the cheaper tier, does
    # exactly that in S04. SKILL.md has always claimed regression "does everything
    # sanity does"; this is what makes that true. Twenty seconds.
    if run_live_smoke; then
        record R04 "Report fixtures + live JMeter smoke" PASS "fixtures already current"
    else
        record R04 "Report fixtures + live JMeter smoke" FAIL \
            "plugin produced no report in a live JMeter — see $RESULTS_DIR/E24/jmeter.log"
    fi
else
    if [[ $FORCE_FIXTURES -eq 1 ]]; then
        echo "[FIXTURES] Forced regeneration."
    else
        echo "[FIXTURES] Stale, regenerating. Changed since last generation:"
        stale_fixture_reason | sort -u | sed 's/^/    /'
    fi
    echo "[FIXTURES] Regenerating — roughly 3½ minutes (scenarios run ${WIR_JOBS:-4}-up)."

    fixture_fail=0
    SKIP_BUILD=1 bash "$TEST_PLANS/scripts/run-all-tests.sh" --suite fixtures || fixture_fail=1
    SKIP_BUILD=1 bash "$TEST_PLANS/scripts/generate-cmp-fixture.sh"           || fixture_fail=1

    if [[ $fixture_fail -eq 0 ]] && fixtures_current; then
        record R04 "Report fixtures regenerated" PASS
    else
        # A scenario that produced nothing is a recorded FAIL now, not a silent one: every
        # producer empties its directory first and stamps only a report it wrote, so the
        # fixture is missing rather than last cycle's under a fresh stamp (F3; pinned by
        # selftest_failed_regeneration). fixtures_current still has to agree afterwards,
        # because a regeneration can exit 0 and still not be what the tree would render:
        # lib/ext swapped during the run, or a source edited while it ran.
        record R04 "Report fixtures regenerated" FAIL "see $RESULTS_DIR/test-execution-report.csv"
    fi
fi

# --- 5. Browser tests ---------------------------------------------------------
if fixtures_current; then
    if run_playwright; then
        record R05 "Playwright ($(playwright_test_count) tests)" PASS
    else
        record R05 "Playwright ($(playwright_test_count) tests)" FAIL "see test-results/playwright-report/"
    fi
else
    record R05 "Playwright ($(playwright_test_count) tests)" FAIL "fixtures still stale after regeneration"
fi

# --- 6. Golden artifacts ------------------------------------------------------
# The only stage that makes a SILENTLY MOVED VERDICT loud. Everything above asserts what
# someone thought to assert; this asserts that the same fixed input keeps producing the same
# .json and .xml, so a change that shifts a status, a count, an attainment number or a wire
# name on an unedited file shows up as a diff in the pull request that caused it.
#
# It runs LAST because it is the slowest of the cheap stages (~30s, four `-g -o` runs) and
# because a failure here is most readable once the suites above have said everything else is
# fine.
if "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/run-goldens.sh" > "$RESULTS_DIR/goldens.log" 2>&1; then
    record R06 "Golden artifacts (fixed JTL through -g -o)" PASS
else
    tail -40 "$RESULTS_DIR/goldens.log" >&2
    record R06 "Golden artifacts (fixed JTL through -g -o)" FAIL "see $RESULTS_DIR/goldens.log"
fi
print_summary "REGRESSION SUMMARY"
echo ""
echo "  Not covered here — run manually before a release:"
echo "    ./test-plans/scripts/run-all-tests.sh      # + E01/E03/E04/E13/E15/E22 and E20 external-data"
echo "    ./test-plans/scripts/run-config-tests.sh   # config/property handling"
echo "    ./test-plans/scripts/run-error-tests.sh    # N01-N09"
echo "    ./test-plans/scripts/run-docker-tests.sh   # E16, E17"

exit $FAIL
