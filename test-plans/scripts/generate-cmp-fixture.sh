#!/usr/bin/env bash
# =============================================================================
#  Web Insight Report — CMP comparison fixture
#
#  The Playwright compare-tab specs load test-results/CMP/CMP-report.html, but
#  nothing generated it: it was built by hand and then went stale, so a rebuild
#  of the plugin did not refresh it and the specs asserted against a report from
#  an older build. This script is that missing recipe.
#
#  It runs the same plan twice — the first run becomes the baseline, the second
#  is compared against it — because the Compare tab only renders when a baseline
#  JTL is configured.
#
#  Four comparison fixtures come from it, chosen by CMP_ID:
#    CMP    quick-smoke-test.jmx, 30 s of two thread groups (the default)
#    CMP31  E31's plan (transaction-alone-and-nested-test.jmx): a transaction run on its
#           own and inside another one, and transactions the test end cuts off. The
#           comparison in which the rows added up are not the whole run, so the Compare
#           tab's Σ Total row and the Notes tab's "What Changed" are held to each run's
#           own whole run on it (plan v2 items 3-4).
#    CMPK   K2 (the committed k6 run in test-plans/run-data/K2) against K2's own run
#           data: a pair of one tool that is not JMeter, judged as a JMeter pair is, in
#           k6's words (spec 004, User Story 1). No JMeter run: build-report twice.
#    CMPX   K2 against the results file of E02's plan at E02's load (full-load-test.jmx):
#           a pair of two tools, JMeter against k6, compared without judging (spec 004,
#           User Story 2). JMeter writes the baseline, build-report the k6 run.
#  CMP_JMX and CMP_LOAD_ARGS override the plan and its -J load arguments of a JMeter side.
# =============================================================================
set -uo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../../scripts/lib/common.sh"

CMP_ID="${CMP_ID:-CMP}"
# Which tool recorded each side: jmeter (a JMeter run of the plan below) or k6 (K2's committed run)
BASELINE_TOOL=jmeter
CURRENT_TOOL=jmeter
case "$CMP_ID" in
    CMP)   DEFAULT_JMX=quick-smoke-test.jmx
           DEFAULT_LOAD="-Jthreads_browse=5 -Jthreads_purchase=5 -Jduration=30 -Jrampup=5" ;;
    CMP31) DEFAULT_JMX=transaction-alone-and-nested-test.jmx
           DEFAULT_LOAD="" ;;
    CMPK)  DEFAULT_JMX=""
           DEFAULT_LOAD=""
           BASELINE_TOOL=k6
           CURRENT_TOOL=k6 ;;
    CMPX)  DEFAULT_JMX=full-load-test.jmx
           # E02's load, as run-all-tests.sh runs E02
           DEFAULT_LOAD="-Jthreads_browse=20 -Jthreads_shop=15 -Jthreads_api=10 -Jthreads_admin=5 -Jduration=120 -Jrampup=20"
           CURRENT_TOOL=k6 ;;
    *)     echo "[CMP] FAILED: no comparison fixture called $CMP_ID (CMP, CMP31, CMPK or CMPX)" >&2; exit 1 ;;
esac
CMP_DIR="$RESULTS_DIR/$CMP_ID"
JMX="${CMP_JMX:-$DEFAULT_JMX}"
# shellcheck disable=SC2206 # word splitting is the point: one -J argument per word
LOAD_ARGS=(${CMP_LOAD_ARGS-$DEFAULT_LOAD})
REPORT="$CMP_ID-report.html"
# The k6 side's committed run (the K2 fixture's inputs)
K6_RUN="$TEST_PLANS/run-data/K2"

print_banner "$CMP_ID Comparison Fixture — Web Insight Report"

require_jmeter
# SKIP_BUILD=1 when an orchestrator has already built and deployed this same JAR.
if [[ "${SKIP_BUILD:-0}" == "1" ]]; then
    echo "[CMP] SKIP_BUILD=1 — using the already-deployed plugin ${PLUGIN_VERSION}"
    require_deployed_plugin
else
    build_plugin || exit 1
    deploy_plugin || exit 1
fi

# The whole directory, emptied before anything runs, and checked afterwards.
#
# It used to remove the two JTLs alone and stamp unconditionally, so a run whose JMeter
# rendered nothing left the previous cycle's CMP-report.html in place: the marker check
# below passed on it, and the stamp written at the end named the JAR now in lib/ext (F3).
# rm can fail on Windows without a word, so a report still there afterwards is refused
# rather than run over: it could not be told apart from one this run wrote.
#
# The JTLs are part of it, and were first for a reason that still holds: JMeter APPENDS
# to an existing -l file. This script once cleared neither, so every regeneration
# concatenated another 30-second run onto both; the fixture on this machine had grown
# to five runs in the baseline and nine in the current file, spanning 119 days — a
# "30-second comparison" whose bucket granularity and deltas bore no relation to what a
# fresh checkout produces. It was invisible in CI, where every runner starts empty, and
# the provenance stamps hash the report SOURCES, not the sample data, so they declared
# both current.
rm -rf "$CMP_DIR"
mkdir -p "$CMP_DIR"
if [[ -e "$CMP_DIR/$REPORT" ]]; then
    echo "[CMP] FAILED: could not remove the previous $CMP_DIR/$REPORT; not running over it" >&2
    exit 1
fi

# build_k6 <html name> <log> [baseline file]: K2's committed k6 run through build-report --from k6,
# keeping its run data beside the report (<name>-run-data.jsonl.gz), with the baseline when given.
# Its title is K2's, which the run's identity hashes. bash 3.2: no empty-array expansion under
# set -u, so the baseline option is its own branch.
build_k6() {
    local html="$1" log="$2" baseline="${3:-}" jar
    jar="$(ls "$JMETER_EXT"/jmeter-web-insight-report-*.jar 2>/dev/null | head -1)"
    if [[ -z "$jar" ]]; then
        echo "[CMP] FAILED: no plugin JAR in $JMETER_EXT" > "$log"
        return 1
    fi
    if [[ -n "$baseline" ]]; then
        java -jar "$jar" build-report --from k6 --k6-results "$K6_RUN/results.csv.gz" \
            --k6-summary "$K6_RUN/summary.json" --out "$CMP_DIR" --write-run-data --baseline "$baseline" \
            --property "webinsight.report.title=K2" --property "webinsight.report.filename=$html" > "$log" 2>&1
    else
        java -jar "$jar" build-report --from k6 --k6-results "$K6_RUN/results.csv.gz" \
            --k6-summary "$K6_RUN/summary.json" --out "$CMP_DIR" --write-run-data \
            --property "webinsight.report.title=K2" --property "webinsight.report.filename=$html" > "$log" 2>&1
    fi
}

# --- Run 1: the baseline ------------------------------------------------------
if [[ "$BASELINE_TOOL" == "k6" ]]; then
    # K2's own report, and its run data: the baseline the k6 run below is compared with
    echo "[CMP] Baseline: K2's k6 run, build-report --from k6 ..."
    build_k6 baseline.html "$CMP_DIR/build-report-baseline.log"
    BASELINE_FILE="$CMP_DIR/baseline-run-data.jsonl.gz"
    if ! file_exists "$BASELINE_FILE"; then
        echo "[CMP] FAILED: the baseline build wrote no run data — see $CMP_DIR/build-report-baseline.log" >&2
        exit 1
    fi
else
    echo "[CMP] Baseline run ..."
    # Each run also fills its in-test-plan file (the plan's Simple Data Writer, sub-results off): what a
    # listener in the test plan received, which R07 holds that run's whole-run counts to.
    "$JMETER" -n -t "$TEST_PLANS/$JMX" \
        -l "$CMP_DIR/baseline.jtl" \
        -Jin_test_plan_results_file="$CMP_DIR/baseline-in-test-plan.jtl" \
        -Jwebinsight.report.output="$CMP_DIR" \
        -Jwebinsight.report.filename="baseline.html" \
        -Jwebinsight.report.title="Comparison Baseline" \
        ${LOAD_ARGS[@]+"${LOAD_ARGS[@]}"} > "$CMP_DIR/jmeter-baseline.log" 2>&1
    BASELINE_FILE="$CMP_DIR/baseline.jtl"

    if ! file_exists "$BASELINE_FILE"; then
        echo "[CMP] FAILED: baseline run produced no JTL — see $CMP_DIR/jmeter-baseline.log" >&2
        exit 1
    fi
fi

# --- Run 2: the current run, compared against the baseline --------------------
# A separate JTL, and the baseline passed explicitly: auto-detection only looks
# for a file literally named baseline.jtl, which is exactly the path this fixture
# should not depend on.
if [[ "$CURRENT_TOOL" == "k6" ]]; then
    echo "[CMP] Current: K2's k6 run, compared against $(basename "$BASELINE_FILE") ..."
    build_k6 "$REPORT" "$CMP_DIR/build-report-current.log" "$BASELINE_FILE"
else
    echo "[CMP] Current run (comparing against the baseline) ..."
    "$JMETER" -n -t "$TEST_PLANS/$JMX" \
        -l "$CMP_DIR/results.jtl" \
        -Jin_test_plan_results_file="$CMP_DIR/in-test-plan.jtl" \
        -Jwebinsight.report.output="$CMP_DIR" \
        -Jwebinsight.report.filename="$REPORT" \
        -Jwebinsight.report.title="Comparison Run" \
        -Jwebinsight.report.baseline="$BASELINE_FILE" \
        ${LOAD_ARGS[@]+"${LOAD_ARGS[@]}"} > "$CMP_DIR/jmeter-current.log" 2>&1
fi

# --- Validate -----------------------------------------------------------------
# "The file exists" is not enough: the whole point of this fixture is the Compare
# tab, and a report generated without a usable baseline still writes an HTML file.
#
# The marker has to come from the serialized payload, not from the page text. Every
# report inlines report.js, so "comparisonDiffs", "hasComparison" and the tab-compare
# markup all appear in a report that has no baseline at all — grepping for those
# passes on E02, which is precisely the false green this check exists to prevent.
# The escaped \"hasComparison\":true only occurs in the embedded JSON. A pair of two
# tools must read as one (spec 004), and a pair of one tool as one.
COMPARISON_MARKER='\"hasComparison\":true'
if [[ "$BASELINE_TOOL" == "$CURRENT_TOOL" ]]; then
    PAIR_MARKER='\"sameTool\":true'
else
    PAIR_MARKER='\"sameTool\":false'
fi
if ! file_exists "$CMP_DIR/$REPORT"; then
    record "$CMP_ID" "Comparison fixture" FAIL "report not generated"
elif ! assert_contains "$CMP_DIR/$REPORT" "$COMPARISON_MARKER"; then
    record "$CMP_ID" "Comparison fixture" FAIL "report has no comparison data — baseline was not applied"
elif ! assert_contains "$CMP_DIR/$REPORT" "$PAIR_MARKER"; then
    record "$CMP_ID" "Comparison fixture" FAIL "the comparison does not read as a pair of $BASELINE_TOOL and $CURRENT_TOOL"
elif [[ "$CURRENT_TOOL" == "k6" ]] && log_has_stacktrace "$CMP_DIR/build-report-current.log"; then
    record "$CMP_ID" "Comparison fixture" FAIL "stack trace in $CMP_DIR/build-report-current.log"
else
    record "$CMP_ID" "Comparison fixture" PASS "$(stat_size "$CMP_DIR/$REPORT") bytes"
fi

# Record which report sources produced this fixture, so the Playwright suite can refuse
# to run against it once they change. run_test_jmeter does this for the E-tests; this
# script calls JMeter directly, so it has to stamp its own output.
#
# Only a report this run wrote, which the empty directory above makes the only kind there
# can be: the stamp is a claim about where the HTML came from. A report without the
# comparison marker is still stamped, because it is still this build's; that case is the
# FAIL recorded above and the non-zero exit below.
if file_exists "$CMP_DIR/$REPORT"; then
    write_fixture_stamp "$CMP_DIR"
fi

print_summary "$CMP_ID FIXTURE SUMMARY"
echo ""
echo "  Fixture: $CMP_DIR/$REPORT"

exit $FAIL
