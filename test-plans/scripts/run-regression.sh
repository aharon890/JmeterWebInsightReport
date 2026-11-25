#!/usr/bin/env bash
# =============================================================================
#  Web Insight Report — Regression suite (about 13 min when the fixtures regenerate, measured 2026-11-25:
#  phase 1 3m42s, phase 2 1m53s, the browser tests 6m57s; the owner's limit is 15)
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
#  SKIP_BUILD=1, as every runner reads it: the caller has already built and
#  deployed this tree's JAR (run-release.sh, which runs the other runners beside
#  this one on that JAR, so nothing may rebuild it under them). R01 then checks
#  the deployed JAR instead of building.
#
#  --no-browser-tests / --browser-tests-only: the regression in two halves, for
#  run-release.sh. The browser tests (R05) use most of this machine's CPU on their
#  own (83-90% of 24 threads, measured 2026-11-25), and beside the other runners 5 to
#  25 of them timed out. So the release runs every other step beside the other
#  runners, then R05 alone over the fixtures R04 left current.
#
#  Exit code is the number of failed stages.
# =============================================================================
set -uo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../../scripts/lib/common.sh"

FORCE_FIXTURES=0
BROWSER_TESTS=all   # all | none | only
while [[ $# -gt 0 ]]; do
    case "$1" in
        --force-fixtures) FORCE_FIXTURES=1; shift ;;
        --no-browser-tests) BROWSER_TESTS=none; shift ;;
        --browser-tests-only) BROWSER_TESTS=only; shift ;;
        -h|--help) sed -n '2,36p' "$0"; exit 0 ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done

# R05, in one place: in its turn after the fixtures, or alone with --browser-tests-only.
# browser_step <what to say when the fixtures are stale>
browser_step() {
    if fixtures_current; then
        if run_playwright; then
            record R05 "Playwright ($(playwright_test_count) tests)" PASS
        else
            record R05 "Playwright ($(playwright_test_count) tests)" FAIL "see test-results/playwright-report/"
        fi
    else
        record R05 "Playwright ($(playwright_test_count) tests)" FAIL "$1"
    fi
}

print_banner "Regression Suite — Web Insight Report"

require_jmeter

# --- 1. Build and deploy ------------------------------------------------------
# Deploy, not just build. The fixture stage runs real JMeter against JMX files
# that name the listener class, so the shaded JAR has to be in $JMETER_HOME/lib/ext
# or every scenario dies with CannotResolveClassException before a single sample.
# Building alone was enough on a developer machine that already had the plugin
# installed from earlier work, and failed on every fresh checkout.
if [[ "${SKIP_BUILD:-0}" == "1" ]]; then
    # require_deployed_plugin exits on a missing or doubled JAR, so it runs in a subshell.
    if (require_deployed_plugin); then
        record R01 "Build, deploy + shaded JAR relocation guard" PASS "built and deployed by the caller"
    else
        record R01 "Build, deploy + shaded JAR relocation guard" FAIL "SKIP_BUILD=1, and this tree's JAR is not deployed alone"
        print_summary "REGRESSION SUMMARY"
        exit $FAIL
    fi
elif build_plugin && deploy_plugin; then
    record R01 "Build, deploy + shaded JAR relocation guard" PASS
else
    record R01 "Build, deploy + shaded JAR relocation guard" FAIL "see output above"
    print_summary "REGRESSION SUMMARY"
    exit $FAIL
fi

if [[ "$BROWSER_TESTS" == "only" ]]; then
    browser_step "fixtures stale — run the steps before R05 first (without --browser-tests-only); R04 names them"
    print_summary "REGRESSION SUMMARY (browser tests only)"
    exit $FAIL
fi


# --- 2-8. The steps, side by side where they share nothing ------------------------
# One after another this took 19 minutes on the owner's PC (2026-11-25), and it must stay under 15.
# The fixture regeneration (R04) is JMeter runs on timers: wall-clock bound, the CPU mostly idle.
# So the steps that need no fixture run beside it, and the steps that do run beside each other:
#
#   phase 1 (after R01):  R04 fixtures  |  R02 unit tests  |  R03 static checks  |  R06 goldens
#                         (inside R04: the fixture suite | CMP | CMP31 | the live smoke)
#   phase 2 (after R04):  R07 counts against JMeter  |  R08 run data conformance
#   phase 3:              R05 browser tests, alone
#
# R05 runs alone, last: it uses most of this machine's CPU on its own (83-90% of 24 threads), and
# beside other runners 5 to 25 of its tests timed out (run-release.sh, 2026-11-25). Each step writes
# its own log under $RESULTS_DIR/regression-logs, and its verdict is recorded here, in step order,
# once it has finished. Every step that shares a folder with another has its own: the smoke suite's
# CSV and markers (run-all-tests.sh), the goldens self-test's work folder (GOLDENS_WORK_DIR), and
# R07's and R08's referee classes.
STEP_LOGS="$RESULTS_DIR/regression-logs"
rm -rf "$STEP_LOGS"
mkdir -p "$STEP_LOGS"
REGRESSION_START=$SECONDS

# elapsed <since>: minutes and seconds since $SECONDS was <since>
elapsed() {
    local s=$((SECONDS - $1))
    printf '%dm%02ds' $((s / 60)) $((s % 60))
}

# A failed step's log, so a failure reads as it did when every step printed to the console.
show_log_tail() {
    echo "  ---- last lines of $1 ----" >&2
    tail -40 "$1" >&2
}

step_unit_tests() {
    local fail=0
    run_java_tests || fail=1
    run_js_unit_tests || fail=1
    return $fail
}

# These were CI steps until the GitHub workflows were removed. Since this suite is what the
# run-tests skill runs before a change is pushed — and therefore before it can be merged — they
# belong here as well as in sanity, or they would only ever run on whichever tier a given change
# happened to get.
#
# All four fail silently in the wild: a syntax error means the next runner cannot start, a drifted
# fixture list means something is generated and never asserted on (or demanded and never
# generated), a hook committed 100644 is skipped without a word on Linux and macOS — which would
# disable this very gate — and a CRLF blob hides behind git's stat cache until something touches
# the file, then turns the next change to it into a whole-file diff nobody can review.
step_static_checks() {
    local fail=0 f
    for f in "$PROJECT_DIR"/scripts/*.sh "$PROJECT_DIR"/scripts/lib/*.sh \
             "$PROJECT_DIR"/test-plans/scripts/*.sh; do
        [[ -e "$f" ]] || continue
        bash -n "$f" 2>/dev/null || { echo "  SYNTAX FAIL: $f" >&2; fail=1; }
    done
    verify_fixture_lists || fail=1
    verify_script_modes  || fail=1
    verify_line_endings  || fail=1
    verify_no_nul_bytes  || fail=1
    verify_report_js_rules || fail=1
    verify_release_tier   || fail=1
    verify_jq_idioms      || fail=1
    selftest_report_js_rules || fail=1
    selftest_no_nul_bytes    || fail=1
    selftest_goldens_accept  || fail=1
    selftest_jq_idioms       || fail=1
    selftest_purge_old_plugin_jars || fail=1
    selftest_fixture_stamps  || fail=1
    selftest_failed_regeneration || fail=1
    selftest_report_gate_verdict || fail=1
    return $fail
}

# The only stage that makes a SILENTLY MOVED VERDICT loud. Everything else asserts what someone
# thought to assert; this asserts that the same fixed input keeps producing the same .json and
# .xml, so a change that shifts a status, a count, an attainment number or a wire name on an
# unedited file shows up as a diff in the pull request that caused it. It needs no fixture.
step_goldens() {
    "$TEST_PLANS/scripts/run-goldens.sh" > "$RESULTS_DIR/goldens.log" 2>&1
}

# R04, the expensive stage, and the only one worth avoiding. Skipping it when the stamps match is
# not a shortcut: identical source produces an identical report, which is the same reasoning
# global-setup.ts uses to decide the fixtures are still valid to test against. Its note goes to
# $STEP_LOGS/R04.note and its causes of failure to $STEP_LOGS/R04.cause.
step_fixtures() {
    if [[ $FORCE_FIXTURES -eq 0 ]] && fixtures_current; then
        echo "[FIXTURES] Current — regeneration skipped (--force-fixtures to override)."
        echo "fixtures already current" > "$STEP_LOGS/R04.note"
        # Skipping regeneration also skips every JMeter execution in this suite, and that was
        # measured, not theorised: a full regression run took 106 seconds and left the E24
        # report's mtime untouched. So the tier named "regression" could pass without ever
        # loading the plugin into a JMeter — while sanity, the cheaper tier, does exactly that in
        # S04. SKILL.md has always claimed regression "does everything sanity does"; this is what
        # makes that true. Twenty seconds.
        if ! run_live_smoke; then
            echo "the live smoke failed (E24 or E30) — see $RESULTS_DIR/E24/jmeter.log, $RESULTS_DIR/E30/" \
                > "$STEP_LOGS/R04.cause"
            return 1
        fi
        return 0
    fi
    if [[ $FORCE_FIXTURES -eq 1 ]]; then
        echo "[FIXTURES] Forced regeneration."
    else
        echo "[FIXTURES] Stale, regenerating. Changed since last generation:"
        stale_fixture_reason | sort -u | sed 's/^/    /'
    fi
    echo "regenerated" > "$STEP_LOGS/R04.note"

    # The four producers side by side, each in its own folders: the fixture suite (its scenarios
    # ${WIR_JOBS:-4}-up), the two comparison fixtures, and the smoke suite (E24, and E30's live
    # server-data flow). The fixture suite holds neither smoke scenario, so a regeneration alone
    # would never run the live flow on exactly the change that needed it.
    local p_fixtures p_cmp p_cmp31 p_smoke fixture_fail=0 smoke_fail=0 causes=""
    SKIP_BUILD=1 bash "$TEST_PLANS/scripts/run-all-tests.sh" --suite fixtures \
        > "$STEP_LOGS/R04-fixture-suite.log" 2>&1 &
    p_fixtures=$!
    SKIP_BUILD=1 bash "$TEST_PLANS/scripts/generate-cmp-fixture.sh" > "$STEP_LOGS/R04-cmp.log" 2>&1 &
    p_cmp=$!
    CMP_ID=CMP31 SKIP_BUILD=1 bash "$TEST_PLANS/scripts/generate-cmp-fixture.sh" > "$STEP_LOGS/R04-cmp31.log" 2>&1 &
    p_cmp31=$!
    run_live_smoke > "$STEP_LOGS/R04-smoke.log" 2>&1 &
    p_smoke=$!
    wait "$p_fixtures" || fixture_fail=1
    wait "$p_cmp"      || fixture_fail=1
    wait "$p_cmp31"    || fixture_fail=1
    wait "$p_smoke"    || smoke_fail=1

    # Both are judged, and every cause present is named: a failed smoke used to be the whole R04
    # line, and a fixture that failed to regenerate beside it went unnamed.
    #
    # A scenario that produced nothing is a recorded FAIL now, not a silent one: every producer
    # empties its directory first and stamps only a report it wrote, so the fixture is missing
    # rather than last cycle's under a fresh stamp (F3; pinned by selftest_failed_regeneration).
    # fixtures_current still has to agree afterwards, because a regeneration can exit 0 and still
    # not be what the tree would render: lib/ext swapped during the run, or a source edited while
    # it ran.
    [[ $smoke_fail -eq 0 ]] \
        || causes="the live smoke failed (E24 or E30) — see $RESULTS_DIR/E24/jmeter.log, $RESULTS_DIR/E30/"
    if [[ $fixture_fail -ne 0 ]] || ! fixtures_current; then
        local stale_ids
        stale_ids="$(stale_fixture_reason | sort -u | tr '\n' ',' | sed 's/,$//; s/,/, /g')"
        causes="${causes:+$causes; }fixtures not regenerated (${stale_ids:-a producer failed})"
        causes="$causes — see $RESULTS_DIR/test-execution-report.csv and $STEP_LOGS/R04-*.log"
    fi
    if [[ -n "$causes" ]]; then
        echo "$causes" > "$STEP_LOGS/R04.cause"
        return 1
    fi
    return 0
}

# Every fixture's whole-run and per-sampler counts against JMeter's Aggregate Report, computed by
# JMeter's own classes from the run's own files (run_counts_against_jmeter in common.sh). The other
# suites compare the report with itself, and every whole-run surface reads one shared count, so they
# agreed with each other on a total that JMeter disagreed with. Only over current fixtures: a stale
# one holds the previous build's numbers.
step_counts() {
    run_counts_against_jmeter > "$RESULTS_DIR/counts-against-jmeter.log" 2>&1
}

# Every producer of run data held to JMeter's Aggregate Report: the k6 fixtures through build-report
# --from k6, and the JMeter plugin itself, E02 and E31 written as run data by -g and built again
# (run-run-data.sh, spec 001).
step_run_data() {
    "$TEST_PLANS/scripts/run-run-data.sh" > "$RESULTS_DIR/run-data.log" 2>&1
}

# A drifted fixture list (R03 names it) makes R04 generate a different set than the browser stage
# demands, so the fixture steps do not start at all.
LISTS_AGREE=1
verify_fixture_lists >/dev/null 2>&1 || LISTS_AGREE=0

# --- Phase 1 ----------------------------------------------------------------------------------
PHASE_START=$SECONDS
echo "[REGRESSION] Phase 1: R04 fixtures, R02 unit tests, R03 static checks and R06 goldens, side by side."
step_unit_tests    > "$STEP_LOGS/R02.log" 2>&1 & P_UNIT=$!
step_static_checks > "$STEP_LOGS/R03.log" 2>&1 & P_STATIC=$!
step_goldens       > "$STEP_LOGS/R06.log" 2>&1 & P_GOLDENS=$!
if [[ $LISTS_AGREE -eq 1 ]]; then
    step_fixtures > "$STEP_LOGS/R04.log" 2>&1 & P_FIXTURES=$!
fi

R02_RC=0; wait "$P_UNIT"    || R02_RC=1
R03_RC=0; wait "$P_STATIC"  || R03_RC=1
R06_RC=0; wait "$P_GOLDENS" || R06_RC=1
R04_RC=0
if [[ $LISTS_AGREE -eq 1 ]]; then
    wait "$P_FIXTURES" || R04_RC=1
fi

if [[ $R02_RC -eq 0 ]]; then
    record R02 "Unit tests (Java + report.js)" PASS
else
    show_log_tail "$STEP_LOGS/R02.log"
    record R02 "Unit tests (Java + report.js)" FAIL "see $STEP_LOGS/R02.log"
fi
if [[ $R03_RC -eq 0 ]]; then
    record R03 "Static checks (syntax, fixture lists, exec bits, line endings, NUL bytes, report.js rules, self-tests)" PASS
else
    show_log_tail "$STEP_LOGS/R03.log"
    record R03 "Static checks (syntax, fixture lists, exec bits, line endings, NUL bytes, report.js rules, self-tests)" FAIL "see $STEP_LOGS/R03.log"
fi
if [[ $LISTS_AGREE -eq 0 ]]; then
    record R04 "Report fixtures" FAIL "FIXTURE_IDS and REQUIRED_FIXTURES disagree — see R03"
    record R05 "Playwright ($(playwright_test_count) tests)" FAIL "skipped: fixture lists disagree"
elif [[ $R04_RC -eq 0 ]]; then
    record R04 "Report fixtures + live JMeter smoke" PASS "$(cat "$STEP_LOGS/R04.note" 2>/dev/null)"
else
    show_log_tail "$STEP_LOGS/R04.log"
    record R04 "Report fixtures + live JMeter smoke" FAIL \
        "$(cat "$STEP_LOGS/R04.cause" 2>/dev/null || echo "see $STEP_LOGS/R04.log")"
fi
if [[ $R06_RC -eq 0 ]]; then
    record R06 "Golden artifacts (fixed JTL through -g -o)" PASS
else
    tail -40 "$RESULTS_DIR/goldens.log" >&2
    record R06 "Golden artifacts (fixed JTL through -g -o)" FAIL "see $RESULTS_DIR/goldens.log"
fi
echo "[REGRESSION] Phase 1 took $(elapsed $PHASE_START)."
if [[ $LISTS_AGREE -eq 0 ]]; then
    print_summary "REGRESSION SUMMARY"
    exit $FAIL
fi

# --- Phase 2 ----------------------------------------------------------------------------------
PHASE_START=$SECONDS
if fixtures_current; then
    echo "[REGRESSION] Phase 2: R07 counts against JMeter and R08 run data conformance, side by side."
    step_counts   > "$STEP_LOGS/R07.log" 2>&1 & P_COUNTS=$!
    step_run_data > "$STEP_LOGS/R08.log" 2>&1 & P_RUN_DATA=$!
    R07_RC=0; wait "$P_COUNTS"   || R07_RC=1
    R08_RC=0; wait "$P_RUN_DATA" || R08_RC=1
    if [[ $R07_RC -eq 0 ]]; then
        record R07 "Counts against JMeter's Aggregate Report" PASS
    else
        grep -E 'FAIL|ERROR' "$RESULTS_DIR/counts-against-jmeter.log" | head -20 >&2
        record R07 "Counts against JMeter's Aggregate Report" FAIL "see $RESULTS_DIR/counts-against-jmeter.log"
    fi
    if [[ $R08_RC -eq 0 ]]; then
        record R08 "Run data conformance (k6 fixtures, JMeter as a producer)" PASS
    else
        grep -E 'FAIL' "$RESULTS_DIR/run-data.log" | head -20 >&2
        record R08 "Run data conformance (k6 fixtures, JMeter as a producer)" FAIL "see $RESULTS_DIR/run-data.log"
    fi
else
    record R07 "Counts against JMeter's Aggregate Report" FAIL "fixtures stale — R04 names them"
    record R08 "Run data conformance (k6 fixtures, JMeter as a producer)" FAIL "fixtures stale — R04 names them"
fi
echo "[REGRESSION] Phase 2 took $(elapsed $PHASE_START)."

# --- Phase 3: the browser tests, alone --------------------------------------------------------
if [[ "$BROWSER_TESTS" == "all" ]]; then
    PHASE_START=$SECONDS
    browser_step "fixtures still stale after regeneration — R04 names them"
    echo "[REGRESSION] Phase 3 (browser tests) took $(elapsed $PHASE_START)."
fi
echo "[REGRESSION] Steps 2-8 took $(elapsed $REGRESSION_START) in all."

print_summary "REGRESSION SUMMARY"
if [[ "$BROWSER_TESTS" == "none" ]]; then
    echo "  R05 (the browser tests) was not run: --no-browser-tests. Run --browser-tests-only next."
fi
echo ""
echo "  Not covered here — run manually before a release:"
echo "    ./test-plans/scripts/run-all-tests.sh      # + E01/E03/E04/E13/E15/E22 and E20 external-data"
echo "    ./test-plans/scripts/run-config-tests.sh   # config/property handling"
echo "    ./test-plans/scripts/run-error-tests.sh    # N01-N09"
echo "    ./test-plans/scripts/run-docker-tests.sh   # E16, E17"

exit $FAIL
