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
# =============================================================================
set -uo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../../scripts/lib/common.sh"

CMP_DIR="$RESULTS_DIR/CMP"
JMX="${CMP_JMX:-quick-smoke-test.jmx}"
LOAD_ARGS=(-Jthreads_browse=5 -Jthreads_purchase=5 -Jduration=30 -Jrampup=5)

print_banner "CMP Comparison Fixture — Web Insight Report"

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
if [[ -e "$CMP_DIR/CMP-report.html" ]]; then
    echo "[CMP] FAILED: could not remove the previous $CMP_DIR/CMP-report.html; not running over it" >&2
    exit 1
fi

# --- Run 1: the baseline ------------------------------------------------------
echo "[CMP] Baseline run ..."
"$JMETER" -n -t "$TEST_PLANS/$JMX" \
    -l "$CMP_DIR/baseline.jtl" \
    -Jwebinsight.report.output="$CMP_DIR" \
    -Jwebinsight.report.filename="baseline.html" \
    -Jwebinsight.report.title="Comparison Baseline" \
    "${LOAD_ARGS[@]}" > "$CMP_DIR/jmeter-baseline.log" 2>&1

if ! file_exists "$CMP_DIR/baseline.jtl"; then
    echo "[CMP] FAILED: baseline run produced no JTL — see $CMP_DIR/jmeter-baseline.log" >&2
    exit 1
fi

# --- Run 2: the current run, compared against the baseline --------------------
# A separate JTL, and the baseline passed explicitly: auto-detection only looks
# for a file literally named baseline.jtl, which is exactly the path this fixture
# should not depend on.
echo "[CMP] Current run (comparing against the baseline) ..."
"$JMETER" -n -t "$TEST_PLANS/$JMX" \
    -l "$CMP_DIR/results.jtl" \
    -Jwebinsight.report.output="$CMP_DIR" \
    -Jwebinsight.report.filename="CMP-report.html" \
    -Jwebinsight.report.title="Comparison Run" \
    -Jwebinsight.report.baseline="$CMP_DIR/baseline.jtl" \
    "${LOAD_ARGS[@]}" > "$CMP_DIR/jmeter-current.log" 2>&1

# --- Validate -----------------------------------------------------------------
# "The file exists" is not enough: the whole point of this fixture is the Compare
# tab, and a report generated without a usable baseline still writes an HTML file.
#
# The marker has to come from the serialized payload, not from the page text. Every
# report inlines report.js, so "comparisonDiffs", "hasComparison" and the tab-compare
# markup all appear in a report that has no baseline at all — grepping for those
# passes on E02, which is precisely the false green this check exists to prevent.
# The escaped \"hasComparison\":true only occurs in the embedded JSON.
COMPARISON_MARKER='\"hasComparison\":true'
if ! file_exists "$CMP_DIR/CMP-report.html"; then
    record CMP "Comparison fixture" FAIL "report not generated"
elif ! assert_contains "$CMP_DIR/CMP-report.html" "$COMPARISON_MARKER"; then
    record CMP "Comparison fixture" FAIL "report has no comparison data — baseline was not applied"
else
    record CMP "Comparison fixture" PASS "$(stat_size "$CMP_DIR/CMP-report.html") bytes"
fi

# Record which report sources produced this fixture, so the Playwright suite can refuse
# to run against it once they change. run_test_jmeter does this for the E-tests; this
# script calls JMeter directly, so it has to stamp its own output.
#
# Only a report this run wrote, which the empty directory above makes the only kind there
# can be: the stamp is a claim about where the HTML came from. A report without the
# comparison marker is still stamped, because it is still this build's; that case is the
# FAIL recorded above and the non-zero exit below.
if file_exists "$CMP_DIR/CMP-report.html"; then
    write_fixture_stamp "$CMP_DIR"
fi

print_summary "CMP FIXTURE SUMMARY"
echo ""
echo "  Fixture: $CMP_DIR/CMP-report.html"

exit $FAIL
