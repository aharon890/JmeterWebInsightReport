#!/usr/bin/env bash
# =============================================================================
#  Configuration & Runtime Scenario Tests (C01-C41)
#  Defaults, paths, JUnit XML, Apdex, ramp-up, timestamp filenames, output logging
#
#  C01-C16 are the original configuration cases. C17-C31 cover output paths: every
#  one corresponds to a finding that was reproduced against a real JMeter run and was
#  invisible to this suite. C30 asserts the logging that makes the rest assertable.
#
#  Assumes the plugin is already deployed (run ./scripts/build-and-deploy.sh).
# =============================================================================
set -uo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../../scripts/lib/common.sh"

require_jmeter

RESULTS="$RESULTS_DIR/config-tests"
SINGLE="$TEST_PLANS/single-sampler-test.jmx"

# C14 waits a full minute for the ${timestamp} placeholder to roll over.
# Override to shorten a local run: TIMESTAMP_WAIT=5 ./run-config-tests.sh
TIMESTAMP_WAIT="${TIMESTAMP_WAIT:-61}"

print_banner "Configuration & Runtime Scenario Tests"

mkdir -p "$RESULTS"

# -----------------------------------------------------------------------------
# C01: Default output dir (no -Jwebinsight.report.output)
#      Report must land in the working directory JMeter was launched from.
# -----------------------------------------------------------------------------
echo "[C01] Default output directory..."
C01_DIR="$RESULTS/C01"
mkdir -p "$C01_DIR"
# Subshell cd instead of pushd/popd — C01 and C07 exist to prove cwd-relative
# resolution works, so the working directory genuinely matters here.
(
    cd "$C01_DIR" || exit 1
    run_jmeter -n -t "$SINGLE" -l results.jtl \
        -Jthreads=1 -Jduration=5 -Jrampup=1 > jmeter.log 2>&1
)
if file_exists "$C01_DIR/web-insight-report.html"; then
    record C01 "Default output directory" PASS "default file in working dir"
else
    record C01 "Default output directory" FAIL "web-insight-report.html not found in working dir"
fi

# -----------------------------------------------------------------------------
# C02: Default filename — asserts over C01's output
# -----------------------------------------------------------------------------
echo "[C02] Default filename..."
if file_exists "$C01_DIR/web-insight-report.html"; then
    record C02 "Default filename" PASS "default filename used"
else
    record C02 "Default filename" FAIL "no report"
fi

# -----------------------------------------------------------------------------
# C03: Default title present in HTML — asserts over C01's output
# -----------------------------------------------------------------------------
echo "[C03] Default title in report..."
if ! file_exists "$C01_DIR/web-insight-report.html"; then
    record C03 "Default title" FAIL "no report"
elif assert_contains "$C01_DIR/web-insight-report.html" "JMeter Web Insight Report"; then
    record C03 "Default title" PASS "default title found"
else
    record C03 "Default title" FAIL "default title not found"
fi

# -----------------------------------------------------------------------------
# C04: Default JSON alongside HTML — asserts over C01's output
# -----------------------------------------------------------------------------
echo "[C04] Default JSON file generated..."
if file_exists "$C01_DIR/web-insight-report.json"; then
    record C04 "Default JSON generated" PASS ""
else
    record C04 "Default JSON generated" FAIL "web-insight-report.json missing"
fi

# -----------------------------------------------------------------------------
# C05: Custom filename with ${timestamp} placeholder
# -----------------------------------------------------------------------------
echo "[C05] Filename with timestamp placeholder..."
C05_DIR="$RESULTS/C05"
mkdir -p "$C05_DIR"
run_jmeter -n -t "$SINGLE" -l "$C05_DIR/results.jtl" \
    -Jthreads=1 -Jduration=5 -Jrampup=1 \
    -Jwebinsight.report.output="$C05_DIR" \
    -Jwebinsight.report.filename='ts-report_${timestamp}.html' \
    > "$C05_DIR/jmeter.log" 2>&1
if [[ "$(count_glob "$C05_DIR"/ts-report_*.html)" -ge 1 ]]; then
    record C05 "Timestamp filename" PASS "timestamp filename created"
else
    record C05 "Timestamp filename" FAIL "no timestamped file"
fi

# -----------------------------------------------------------------------------
# C06: JSON filename derived from HTML filename — asserts over C05's output
# -----------------------------------------------------------------------------
echo "[C06] JSON filename matches HTML..."
if [[ "$(count_glob "$C05_DIR"/ts-report_*.json)" -ge 1 ]]; then
    record C06 "JSON filename matches HTML" PASS "matching JSON file"
else
    record C06 "JSON filename matches HTML" FAIL "no matching JSON"
fi

# -----------------------------------------------------------------------------
# C07: Relative output path resolves against cwd
# -----------------------------------------------------------------------------
echo "[C07] Relative output path..."
C07_DIR="$RESULTS/C07"
mkdir -p "$C07_DIR"
(
    cd "$C07_DIR" || exit 1
    run_jmeter -n -t "$SINGLE" -l results.jtl \
        -Jthreads=1 -Jduration=5 -Jrampup=1 \
        -Jwebinsight.report.output=subdir \
        -Jwebinsight.report.filename=C07-report.html > jmeter.log 2>&1
)
if file_exists "$C07_DIR/subdir/C07-report.html"; then
    record C07 "Relative output path" PASS "relative path resolved"
else
    record C07 "Relative output path" FAIL "report not at subdir/C07-report.html"
fi

# -----------------------------------------------------------------------------
# C08: Output dir auto-created when missing
# -----------------------------------------------------------------------------
echo "[C08] Auto-create output directory..."
C08_BASE="$RESULTS/C08"
C08_DIR="$C08_BASE/new-dir-that-doesnt-exist"
mkdir -p "$C08_BASE"
rm -rf "$C08_DIR"
run_jmeter -n -t "$SINGLE" -l "$C08_BASE/results.jtl" \
    -Jthreads=1 -Jduration=5 -Jrampup=1 \
    -Jwebinsight.report.output="$C08_DIR" \
    -Jwebinsight.report.filename=C08-report.html \
    > "$C08_BASE/jmeter.log" 2>&1
if file_exists "$C08_DIR/C08-report.html"; then
    record C08 "Auto-create output dir" PASS "dir auto-created"
else
    record C08 "Auto-create output dir" FAIL "report not generated"
fi

# -----------------------------------------------------------------------------
# C09: Title with special characters
# -----------------------------------------------------------------------------
echo "[C09] Title with special characters..."
C09_DIR="$RESULTS/C09"
mkdir -p "$C09_DIR"
run_jmeter -n -t "$SINGLE" -l "$C09_DIR/results.jtl" \
    -Jthreads=1 -Jduration=5 -Jrampup=1 \
    -Jwebinsight.report.output="$C09_DIR" \
    -Jwebinsight.report.filename=C09-report.html \
    -Jwebinsight.report.title='Test & Report <v2>' \
    > "$C09_DIR/jmeter.log" 2>&1
if file_exists "$C09_DIR/C09-report.html"; then
    record C09 "Special chars in title" PASS "report generated"
else
    record C09 "Special chars in title" FAIL "report not generated"
fi

# -----------------------------------------------------------------------------
# C10: JUnit XML enabled
# -----------------------------------------------------------------------------
echo "[C10] JUnit XML generation enabled..."
C10_DIR="$RESULTS/C10"
mkdir -p "$C10_DIR"
cp -f "$ANNOTATIONS_DIR/asserts-strict.json" "$C10_DIR/report-annotations.json"
run_jmeter -n -t "$TEST_PLANS/asserts-scenario-test.jmx" -l "$C10_DIR/results.jtl" \
    -Jthreads=5 -Jduration=20 -Jrampup=3 \
    -Jwebinsight.report.output="$C10_DIR" \
    -Jwebinsight.report.filename=C10-report.html \
    -Jwebinsight.report.junit=true \
    > "$C10_DIR/jmeter.log" 2>&1
if file_exists "$C10_DIR/C10-report.xml"; then
    record C10 "JUnit XML enabled" PASS "XML generated"
else
    record C10 "JUnit XML enabled" FAIL "no XML"
fi

# -----------------------------------------------------------------------------
# C11: JUnit XML NOT generated by default — asserts over C01's output
# -----------------------------------------------------------------------------
echo "[C11] JUnit XML NOT generated by default..."
if ! file_exists "$C01_DIR/web-insight-report.xml"; then
    record C11 "No JUnit XML by default" PASS "no XML by default"
else
    record C11 "No JUnit XML by default" FAIL "XML generated without flag"
fi

# -----------------------------------------------------------------------------
# C12: Custom Apdex threshold
# -----------------------------------------------------------------------------
echo "[C12] Custom Apdex threshold (200ms)..."
C12_DIR="$RESULTS/C12"
mkdir -p "$C12_DIR"
run_jmeter -n -t "$SINGLE" -l "$C12_DIR/results.jtl" \
    -Jthreads=5 -Jduration=15 -Jrampup=3 \
    -Jwebinsight.report.output="$C12_DIR" \
    -Jwebinsight.report.filename=C12-report.html \
    -Jwebinsight.apdex.threshold=200 \
    > "$C12_DIR/jmeter.log" 2>&1
if file_exists "$C12_DIR/C12-report.html"; then
    record C12 "Custom Apdex threshold" PASS "report generated"
else
    record C12 "Custom Apdex threshold" FAIL "report not generated"
fi

# -----------------------------------------------------------------------------
# C13: Ramp-up marking
# -----------------------------------------------------------------------------
echo "[C13] Ramp-up marking (5000ms)..."
C13_DIR="$RESULTS/C13"
mkdir -p "$C13_DIR"
run_jmeter -n -t "$SINGLE" -l "$C13_DIR/results.jtl" \
    -Jthreads=5 -Jduration=15 -Jrampup=3 \
    -Jwebinsight.report.output="$C13_DIR" \
    -Jwebinsight.report.filename=C13-report.html \
    -Jwebinsight.report.rampup=5000 \
    > "$C13_DIR/jmeter.log" 2>&1
if ! file_exists "$C13_DIR/C13-report.html"; then
    record C13 "Ramp-up marking" FAIL "report not generated"
elif assert_contains "$C13_DIR/C13-report.html" "rampUp"; then
    record C13 "Ramp-up marking" PASS "ramp-up data in report"
else
    record C13 "Ramp-up marking" PASS "report generated (ramp-up marking visual only)"
fi

# -----------------------------------------------------------------------------
# C14: Two consecutive timestamped runs produce unique filenames
# -----------------------------------------------------------------------------
echo "[C14] Two consecutive runs with timestamp filenames..."
C14_DIR="$RESULTS/C14"
mkdir -p "$C14_DIR"
run_jmeter -n -t "$SINGLE" -l "$C14_DIR/r1.jtl" \
    -Jthreads=1 -Jduration=5 -Jrampup=1 \
    -Jwebinsight.report.output="$C14_DIR" \
    -Jwebinsight.report.filename='run_${timestamp}.html' \
    > "$C14_DIR/jmeter1.log" 2>&1
echo "       waiting ${TIMESTAMP_WAIT}s for the timestamp to roll over..."
sleep "$TIMESTAMP_WAIT"
run_jmeter -n -t "$SINGLE" -l "$C14_DIR/r2.jtl" \
    -Jthreads=1 -Jduration=5 -Jrampup=1 \
    -Jwebinsight.report.output="$C14_DIR" \
    -Jwebinsight.report.filename='run_${timestamp}.html' \
    > "$C14_DIR/jmeter2.log" 2>&1
HTML_COUNT="$(count_glob "$C14_DIR"/run_*.html)"
if [[ "$HTML_COUNT" -ge 2 ]]; then
    record C14 "Unique timestamped filenames" PASS "$HTML_COUNT unique files"
else
    record C14 "Unique timestamped filenames" FAIL "only $HTML_COUNT file(s)"
fi

# -----------------------------------------------------------------------------
# C15: Zero/minimal samples edge case
# -----------------------------------------------------------------------------
echo "[C15] Zero/minimal samples edge case..."
C15_DIR="$RESULTS/C15"
mkdir -p "$C15_DIR"
run_jmeter -n -t "$SINGLE" -l "$C15_DIR/results.jtl" \
    -Jthreads=1 -Jduration=2 -Jrampup=1 \
    -Jwebinsight.report.output="$C15_DIR" \
    -Jwebinsight.report.filename=C15-report.html \
    > "$C15_DIR/jmeter.log" 2>&1
if file_exists "$C15_DIR/C15-report.html"; then
    record C15 "Minimal data" PASS "report generated even with minimal data"
else
    record C15 "Minimal data" FAIL "report not generated"
fi

# -----------------------------------------------------------------------------
# C16: Listener honours -Jwebinsight.report.{rampup,annotations,baseline}
#      These were previously read only by the -g -o exporter, so in listener mode
#      comparison worked only when a file literally named baseline.jtl sat in the
#      output directory, and the ramp-up overlay was impossible (E32 / LIST-4).
#      The files are deliberately placed OUTSIDE the output directory so
#      auto-detection cannot supply them — only the properties can.
# -----------------------------------------------------------------------------
echo "[C16] Listener honours baseline/annotations/rampup properties..."
C16_DIR="$RESULTS/C16"
C16_EXT="$RESULTS/C16-external"
mkdir -p "$C16_DIR" "$C16_EXT"
cp -f "$ANNOTATIONS_DIR/full-annotations.json" "$C16_EXT/annotations.json"

# A first run supplies the baseline JTL, from outside the second run's output dir.
run_jmeter -n -t "$SINGLE" -l "$C16_EXT/baseline-run.jtl" \
    -Jthreads=2 -Jduration=5 -Jrampup=1 \
    -Jwebinsight.report.output="$C16_EXT" \
    -Jwebinsight.report.filename=C16-baseline.html \
    > "$C16_EXT/jmeter.log" 2>&1

run_jmeter -n -t "$SINGLE" -l "$C16_DIR/results.jtl" \
    -Jthreads=2 -Jduration=8 -Jrampup=1 \
    -Jwebinsight.report.output="$C16_DIR" \
    -Jwebinsight.report.filename=C16-report.html \
    -Jwebinsight.report.rampup=3000 \
    -Jwebinsight.report.annotations="$C16_EXT/annotations.json" \
    -Jwebinsight.report.baseline="$C16_EXT/baseline-run.jtl" \
    > "$C16_DIR/jmeter.log" 2>&1

C16_REPORT="$C16_DIR/C16-report.html"
if ! file_exists "$C16_REPORT"; then
    record C16 "Listener CLI properties" FAIL "report not generated"
else
    # Match the SERIALIZED chartData, not the bundled report.js. The bare words
    # rampUpEnd/timelineMarkers/baseline appear in the embedded JavaScript of every
    # report, so grepping for those would pass even when the properties are ignored.
    # Inside the inline chartData payload the JSON keys are quote-escaped.
    C16_NOTES=""
    assert_contains "$C16_REPORT" '\"rampUpEnd\":'      || C16_NOTES="$(append_note "$C16_NOTES" "rampup ignored")"
    assert_contains "$C16_REPORT" '\"assertions\":'     || C16_NOTES="$(append_note "$C16_NOTES" "annotations ignored")"
    assert_contains "$C16_REPORT" '\"comparisonDiffs\":' || C16_NOTES="$(append_note "$C16_NOTES" "baseline ignored")"
    if [[ -z "$C16_NOTES" ]]; then
        record C16 "Listener CLI properties" PASS "rampup, annotations and baseline all applied"
    else
        record C16 "Listener CLI properties" FAIL "$C16_NOTES"
    fi
fi

# =============================================================================
#  C17-C29, C31 — output paths.
#
#  Every scenario here corresponds to a finding that was reproduced against a real
#  JMeter run and was invisible to this suite. They are cheap: one 5-second run each.
# =============================================================================

# -----------------------------------------------------------------------------
# C17: Two external-data reports in ONE directory keep their own payloads.
#
#      The payload filename was a constant while the report name was configurable,
#      so the second run's payload overwrote the first's and report 1 rendered
#      report 2's charts under report 1's header cards and sampler table — including
#      sampler filter checkboxes for samplers absent from its own table. Nothing
#      detected it: the template tested window.__WIR_DATA__ for PRESENCE, never
#      identity.
#
#      That last sentence is history and has been for a while. The template now stamps
#      a runId into the page and compares it against the payload's own, and since that
#      check turned fatal a mismatch is not a banner over the charts — it withholds
#      every chart and explains why instead. Which is exactly why this row still earns
#      its five seconds: the runtime check is a last line of defence, and a report that
#      has to invoke it shows its reader no charts at all. What C17 asserts is that the
#      pair is right in the first place, so the check never has anything to catch.
# -----------------------------------------------------------------------------
echo "[C17] Two external reports in one directory..."
C17_DIR="$RESULTS/C17"
rm -rf "$C17_DIR"; mkdir -p "$C17_DIR"
WIR_RUNTIME_LOG="$C17_DIR/alpha-runtime.log" \
run_jmeter -n -t "$SINGLE" -l "$C17_DIR/r1.jtl" \
    -Jthreads=1 -Jduration=5 -Jrampup=1 \
    -Jwebinsight.report.output="$C17_DIR" \
    -Jwebinsight.report.filename=alpha.html \
    -Jwebinsight.report.title=RUN-ALPHA \
    -Jwebinsight.report.externaldata=true > "$C17_DIR/alpha.log" 2>&1
WIR_RUNTIME_LOG="$C17_DIR/beta-runtime.log" \
run_jmeter -n -t "$TEST_PLANS/many-samplers-test.jmx" -l "$C17_DIR/r2.jtl" \
    -Jthreads=1 -Jduration=5 -Jrampup=1 \
    -Jwebinsight.report.output="$C17_DIR" \
    -Jwebinsight.report.filename=beta.html \
    -Jwebinsight.report.title=RUN-BETA \
    -Jwebinsight.report.externaldata=true > "$C17_DIR/beta.log" 2>&1

C17_NOTES=""
if ! file_exists "$C17_DIR/alpha.html" || ! file_exists "$C17_DIR/beta.html"; then
    record C17 "Two external reports, one dir" FAIL "one or both reports missing"
else
    # Two distinct payloads, not one shared file.
    file_exists "$C17_DIR/alpha-data.js" \
        || C17_NOTES="$(append_note "$C17_NOTES" "alpha-data.js missing")"
    file_exists "$C17_DIR/beta-data.js" \
        || C17_NOTES="$(append_note "$C17_NOTES" "beta-data.js missing")"
    file_absent "$C17_DIR/web-insight-data.js" \
        || C17_NOTES="$(append_note "$C17_NOTES" "a shared web-insight-data.js still appears")"
    # Each HTML must point at its OWN payload.
    assert_contains "$C17_DIR/alpha.html" 'src="alpha-data.js"' \
        || C17_NOTES="$(append_note "$C17_NOTES" "alpha.html does not reference alpha-data.js")"
    assert_contains "$C17_DIR/beta.html" 'src="beta-data.js"' \
        || C17_NOTES="$(append_note "$C17_NOTES" "beta.html does not reference beta-data.js")"
    assert_contains "$C17_DIR/alpha.html" 'src="beta-data.js"' \
        && C17_NOTES="$(append_note "$C17_NOTES" "alpha.html references BETA's payload")"
    # Each payload declares which run it belongs to, so a mismatch is detectable and not
    # merely unlikely.
    assert_contains "$C17_DIR/alpha-data.js" '"runTestName":"RUN-ALPHA"' \
        || C17_NOTES="$(append_note "$C17_NOTES" "alpha payload lacks its run identity")"
    assert_contains "$C17_DIR/beta-data.js" '"runTestName":"RUN-BETA"' \
        || C17_NOTES="$(append_note "$C17_NOTES" "beta payload lacks its run identity")"
    # The manifest in each .json names that report's own payload (CODE-8).
    assert_contains "$C17_DIR/alpha.json" 'alpha-data.js' \
        || C17_NOTES="$(append_note "$C17_NOTES" "alpha.json manifest does not name its payload")"
    if [[ -z "$C17_NOTES" ]]; then
        record C17 "Two external reports, one dir" PASS "separate payloads, each with its own run identity"
    else
        record C17 "Two external reports, one dir" FAIL "$C17_NOTES"
    fi
fi

# -----------------------------------------------------------------------------
# C17b: A run that FAILS must not corrupt the previous run's payload.
#
#       The payload was written before the HTML. So when the HTML write failed, the
#       payload had already landed on top of the previous run's — leaving that run's
#       intact, still-valid report rendering the new run's data, from a run that
#       exited 0. Here the second run is made to fail by pointing it at an output
#       directory that is an existing FILE.
# -----------------------------------------------------------------------------
echo "[C17b] A failed run leaves the previous payload untouched..."
C17B_DIR="$RESULTS/C17b"
rm -rf "$C17B_DIR"; mkdir -p "$C17B_DIR"
WIR_RUNTIME_LOG="$C17B_DIR/good-runtime.log" \
run_jmeter -n -t "$SINGLE" -l "$C17B_DIR/r1.jtl" \
    -Jthreads=1 -Jduration=5 -Jrampup=1 \
    -Jwebinsight.report.output="$C17B_DIR" \
    -Jwebinsight.report.filename=keep.html \
    -Jwebinsight.report.externaldata=true > "$C17B_DIR/good.log" 2>&1
C17B_MD5_BEFORE="$(file_md5 "$C17B_DIR/keep-data.js")"

# A file where a directory is expected: every write into it must fail.
C17B_BLOCKED="$C17B_DIR/blocked"
: > "$C17B_BLOCKED"
WIR_RUNTIME_LOG="$C17B_DIR/bad-runtime.log" \
run_jmeter -n -t "$SINGLE" -l "$C17B_DIR/r2.jtl" \
    -Jthreads=1 -Jduration=5 -Jrampup=1 \
    -Jwebinsight.report.output="$C17B_BLOCKED" \
    -Jwebinsight.report.filename=keep.html \
    -Jwebinsight.report.externaldata=true > "$C17B_DIR/bad.log" 2>&1
C17B_MD5_AFTER="$(file_md5 "$C17B_DIR/keep-data.js")"

C17B_NOTES=""
if [[ "$C17B_MD5_BEFORE" == "none" ]]; then
    record C17b "Failed run keeps old payload" FAIL "the first run produced no payload to protect"
else
    [[ "$C17B_MD5_BEFORE" == "$C17B_MD5_AFTER" ]] \
        || C17B_NOTES="$(append_note "$C17B_NOTES" "payload md5 changed: $C17B_MD5_BEFORE -> $C17B_MD5_AFTER")"
    # No temp-file debris. A stray .tmp in a -o directory would make JMeter refuse the NEXT run.
    [[ "$(count_glob "$C17B_DIR"/.*.tmp)" -eq 0 ]] \
        || C17B_NOTES="$(append_note "$C17B_NOTES" "a .tmp file was left behind")"
    if [[ -z "$C17B_NOTES" ]]; then
        record C17b "Failed run keeps old payload" PASS "previous payload byte-identical, no debris"
    else
        record C17b "Failed run keeps old payload" FAIL "$C17B_NOTES"
    fi
fi

# -----------------------------------------------------------------------------
# C18: A filename set on the listener ELEMENT is visible to the -e -o exporter.
#
#      The exporter read only the -J property, but the listener accepts the name from
#      either source. So a GUI-set filename was invisible: the check derived the
#      DEFAULT name, did not find it, and generated a SECOND report of the same run —
#      with a different filename AND a different title.
# -----------------------------------------------------------------------------
echo "[C18] GUI-set filename is visible to the exporter..."
C18_BASE="$RESULTS/C18"
rm -rf "$C18_BASE"; mkdir -p "$C18_BASE"
C18_DASH="$C18_BASE/dash"
WIR_RUNTIME_LOG="$C18_BASE/jmeter-runtime.log" \
run_jmeter -n -t "$TEST_PLANS/gui-filename-test.jmx" -l "$C18_BASE/results.jtl" \
    -Jthreads=1 -Jduration=5 -Jrampup=1 \
    -Jwebinsight.report.output="$C18_DASH" \
    -e -o "$C18_DASH" > "$C18_BASE/jmeter.log" 2>&1

C18_COUNT="$(count_glob "$C18_DASH"/gui-name.html "$C18_DASH"/web-insight-report.html)"
if [[ "$C18_COUNT" -eq 1 ]] && file_exists "$C18_DASH/gui-name.html"; then
    record C18 "GUI filename seen by exporter" PASS "exactly one report, under the GUI-configured name"
else
    record C18 "GUI filename seen by exporter" FAIL \
        "$C18_COUNT Web Insight HTML file(s) — expected exactly gui-name.html"
fi

# -----------------------------------------------------------------------------
# C19: An unwritable output directory fails loudly, names the DIRECTORY, and now
#      FAILS THE RUN.
#
#      Was: exit 0, no report, and a stderr line containing only a path — the path of
#      the REPORT, while the thing that was wrong was the directory. A pipeline shaped
#      `jmeter -n -t … && publish` stayed green with nothing to publish.
#
#      This row then pinned that exit 0 as *intended*, on the reasoning that a listener
#      must not abort someone's test run over a report, and treated a non-zero status as
#      something you opted into with failonerror=true. That reasoning lost: the flag had
#      to be discovered before it could protect anyone, and every failure mode in the
#      matrix exited 0. webinsight.report.failonerror now DEFAULTS TO TRUE, so the
#      default here is a non-zero status and the OPT-OUT is what has to be asked for.
#      The second run is therefore failonerror=false rather than =true — the pair still
#      proves the flag does something, approached from the other side.
# -----------------------------------------------------------------------------
echo "[C19] Output directory is an existing file..."
C19_DIR="$RESULTS/C19"
rm -rf "$C19_DIR"; mkdir -p "$C19_DIR"
C19_BLOCKED="$C19_DIR/blocked"
: > "$C19_BLOCKED"
WIR_RUNTIME_LOG="$C19_DIR/jmeter-runtime.log" \
run_jmeter -n -t "$SINGLE" -l "$C19_DIR/results.jtl" \
    -Jthreads=1 -Jduration=5 -Jrampup=1 \
    -Jwebinsight.report.output="$C19_BLOCKED" > "$C19_DIR/jmeter.log" 2>&1
C19_RC=$?

C19_NOTES=""
assert_contains "$C19_DIR/jmeter.log" "WEB-INSIGHT-REPORT: FAILED" \
    || C19_NOTES="$(append_note "$C19_NOTES" "no FAILED marker on the console")"
# The message must name the directory, not the report inside it.
assert_contains "$C19_DIR/jmeter.log" "not a directory" \
    || C19_NOTES="$(append_note "$C19_NOTES" "the message does not say the output dir is not a directory")"
[[ "$C19_RC" -ne 0 ]] \
    || C19_NOTES="$(append_note "$C19_NOTES" "the default must now fail the run, and it exited 0")"

# ...and failonerror=false is the way back to the old status, for a pipeline that cannot
# be changed today. It buys back the exit code and nothing else: the FAILED marker still
# has to be there, or the run is silent again in the one configuration that asked to be.
WIR_RUNTIME_LOG="$C19_DIR/optout-runtime.log" \
run_jmeter -n -t "$SINGLE" -l "$C19_DIR/r2.jtl" \
    -Jthreads=1 -Jduration=5 -Jrampup=1 \
    -Jwebinsight.report.output="$C19_BLOCKED" \
    -Jwebinsight.report.failonerror=false > "$C19_DIR/optout.log" 2>&1
C19_OPTOUT_RC=$?
assert_exit_code "$C19_OPTOUT_RC" 0 \
    || C19_NOTES="$(append_note "$C19_NOTES" "failonerror=false should restore exit 0, got $C19_OPTOUT_RC")"
assert_contains "$C19_DIR/optout.log" "WEB-INSIGHT-REPORT: FAILED" \
    || C19_NOTES="$(append_note "$C19_NOTES" "failonerror=false silenced the FAILED marker as well as the exit code")"

if [[ -z "$C19_NOTES" ]]; then
    record C19 "Unwritable output dir" PASS \
        "FAILED marker names the directory; the default exits $C19_RC, failonerror=false exits $C19_OPTOUT_RC and still says FAILED"
else
    record C19 "Unwritable output dir" FAIL "$C19_NOTES"
fi

# -----------------------------------------------------------------------------
# C20: Two ${timestamp} runs back to back, with NO sleep, produce two files.
#
#      The pattern was minute-granular and JMeter takes seconds just to start, so two
#      short runs landed in the same minute and the second silently overwrote the
#      first. C14 above still sleeps for its own reasons; this one must not need to.
# -----------------------------------------------------------------------------
echo "[C20] Two timestamped runs with no sleep..."
C20_DIR="$RESULTS/C20"
rm -rf "$C20_DIR"; mkdir -p "$C20_DIR"
for i in 1 2; do
    WIR_RUNTIME_LOG="$C20_DIR/r$i-runtime.log" \
    run_jmeter -n -t "$SINGLE" -l "$C20_DIR/r$i.jtl" \
        -Jthreads=1 -Jduration=5 -Jrampup=1 \
        -Jwebinsight.report.output="$C20_DIR" \
        -Jwebinsight.report.filename='c20_${timestamp}.html' > "$C20_DIR/r$i.log" 2>&1
done
C20_COUNT="$(count_glob "$C20_DIR"/c20_*.html)"
if [[ "$C20_COUNT" -ge 2 ]]; then
    record C20 "Timestamp granularity" PASS "$C20_COUNT distinct files with no sleep"
else
    record C20 "Timestamp granularity" FAIL "only $C20_COUNT file(s) — seconds granularity missing?"
fi

# -----------------------------------------------------------------------------
# C21: A filename containing ':' is rejected, and a report is STILL produced.
#
#      Was: accepted by the validator, then thrown from outputFile.toPath() as
#      InvalidPathException — an IllegalArgumentException, so it escaped the declared
#      `throws IOException` — after the output directory had been created and the
#      payload written. Exit 0, no report. And on Linux the same name was legal, so
#      one configuration behaved differently per platform.
#
#      The exit 0 asserted below is NOT the old failonerror contract and does not move
#      with it: a rejected name falls back to the default and the full artifact set is
#      produced, so nothing failed for failonerror to fire on. A non-zero status here
#      would mean the fallback stopped working.
# -----------------------------------------------------------------------------
echo "[C21] Filename containing a colon..."
C21_DIR="$RESULTS/C21"
rm -rf "$C21_DIR"; mkdir -p "$C21_DIR"
WIR_RUNTIME_LOG="$C21_DIR/jmeter-runtime.log" \
run_jmeter -n -t "$SINGLE" -l "$C21_DIR/results.jtl" \
    -Jthreads=1 -Jduration=5 -Jrampup=1 \
    -Jwebinsight.report.output="$C21_DIR" \
    -Jwebinsight.report.filename='rep:ort.html' > "$C21_DIR/jmeter.log" 2>&1
C21_RC=$?

C21_NOTES=""
assert_log_contains "$C21_DIR/jmeter-runtime.log" "Ignoring report filename" \
    || C21_NOTES="$(append_note "$C21_NOTES" "no rejection WARN in the runtime log")"
file_exists "$C21_DIR/web-insight-report.html" \
    || C21_NOTES="$(append_note "$C21_NOTES" "no report produced under the fallback name")"
assert_exit_code "$C21_RC" 0 \
    || C21_NOTES="$(append_note "$C21_NOTES" "expected exit 0, got $C21_RC")"
if [[ -z "$C21_NOTES" ]]; then
    record C21 "Colon in filename" PASS "rejected with a WARN, default name used, report produced"
else
    record C21 "Colon in filename" FAIL "$C21_NOTES"
fi

# -----------------------------------------------------------------------------
# C22: A configured-but-missing annotations file is reported, not silent.
#
#      Was DEBUG, and the message did not even name the path it looked for. The
#      consequence is not cosmetic: no annotations means no assert rules, which
#      means the CI/CD JSON reports UNKNOWN and the JUnit XML reports every sampler as
#      passing. The quality gate stops gating, silently.
# -----------------------------------------------------------------------------
echo "[C22] Configured annotations file does not exist..."
C22_DIR="$RESULTS/C22"
rm -rf "$C22_DIR"; mkdir -p "$C22_DIR"
WIR_RUNTIME_LOG="$C22_DIR/jmeter-runtime.log" \
run_jmeter -n -t "$SINGLE" -l "$C22_DIR/results.jtl" \
    -Jthreads=1 -Jduration=5 -Jrampup=1 \
    -Jwebinsight.report.output="$C22_DIR" \
    -Jwebinsight.report.filename=C22-report.html \
    -Jwebinsight.report.annotations="$C22_DIR/definitely-not-here.json" > "$C22_DIR/jmeter.log" 2>&1
C22_RC=$?

C22_NOTES=""
# And the run fails, because a gate that was configured and could not be built is the plugin
# failing to do what it was told -- the same category as an unwritable output directory. C33
# proves the flag in both directions; this stage proves the missing-file path reaches it.
[[ "$C22_RC" -ne 0 ]]     || C22_NOTES="$(append_note "$C22_NOTES" "a configured-but-missing annotations file left the exit code at 0")"
# ERROR now, not WARN, and the sentence changed with it: the loader used to log and swallow,
# so the only trace was a line in a file nobody reads. It carries the problem out as a
# finding now, which is why this also checks the JSON below.
assert_log_contains "$C22_DIR/jmeter-runtime.log" "the annotations file does not exist" \
    || C22_NOTES="$(append_note "$C22_NOTES" "no log line for the missing annotations file")"
assert_log_contains "$C22_DIR/jmeter-runtime.log" "definitely-not-here.json" \
    || C22_NOTES="$(append_note "$C22_NOTES" "the log line does not name the path")"
# The finding reaches the artifact a pipeline reads, which is the point of A4: a typo'd
# path used to produce a report indistinguishable from one that never wanted a gate.
assert_contains "$C22_DIR/C22-report.json" '"findings"' \
    || C22_NOTES="$(append_note "$C22_NOTES" "the missing file is not reported in the CI/CD JSON")"
# A pipeline must be able to tell "no thresholds configured" from "evaluated, no verdict".
assert_contains "$C22_DIR/C22-report.json" '"assertsEvaluated" : false' \
    || C22_NOTES="$(append_note "$C22_NOTES" "assertsEvaluated:false missing from the JSON")"
if [[ -z "$C22_NOTES" ]]; then
    record C22 "Missing annotations file" PASS "exits non-zero, logged with the path, findings[] in the JSON, assertsEvaluated:false"
else
    record C22 "Missing annotations file" FAIL "$C22_NOTES"
fi

# -----------------------------------------------------------------------------
# C23: '${timestamp}' as the WHOLE filename still produces a report under -g -o.
#
#      It degenerated to glob "*.html", hence json glob "*.json", hence a regex that
#      matched JMeter's own statistics.json — written by JsonExporter BEFORE any
#      exporter runs. The collision check concluded a listener report existed and
#      skipped, so the run produced no Web Insight report at all and exited 0.
# -----------------------------------------------------------------------------
echo "[C23] Whole-name timestamp under -g -o..."
C23_DIR="$RESULTS/C23"
rm -rf "$C23_DIR"; mkdir -p "$C23_DIR"
WIR_RUNTIME_LOG="$C23_DIR/src-runtime.log" \
run_jmeter -n -t "$TEST_PLANS/no-listener-test.jmx" -l "$C23_DIR/src.jtl" \
    -Jthreads=1 -Jduration=5 -Jrampup=1 > "$C23_DIR/src.log" 2>&1
WIR_RUNTIME_LOG="$C23_DIR/gen-runtime.log" \
run_jmeter -g "$C23_DIR/src.jtl" -o "$C23_DIR/dash" \
    -Jwebinsight.report.filename='${timestamp}' > "$C23_DIR/gen.log" 2>&1

if [[ "$(count_glob "$C23_DIR"/dash/*.html)" -ge 2 ]]; then
    # JMeter's index.html plus at least one of ours.
    record C23 "Whole-name timestamp glob" PASS "a Web Insight report was produced"
else
    record C23 "Whole-name timestamp glob" FAIL \
        "only $(count_glob "$C23_DIR"/dash/*.html) html file(s) — the glob suppressed the report"
fi

# -----------------------------------------------------------------------------
# C24: Extension handling is case-insensitive and does not stack.
#
#      Was purely additive and case-sensitive: MyReport.HTML -> MyReport.HTML.html,
#      report.htm -> report.htm.html, report.json -> report.json.html. The worst was a
#      configured ".html", which produced three DOTFILES (.html/.json/.xml), invisible
#      to ls on Unix.
# -----------------------------------------------------------------------------
echo "[C24] Extension handling..."
C24_DIR="$RESULTS/C24"
rm -rf "$C24_DIR"; mkdir -p "$C24_DIR"
C24_NOTES=""
c24_case() {   # <configured> <expected-html>
    local dir="$C24_DIR/$2"
    mkdir -p "$dir"
    WIR_RUNTIME_LOG="$dir/jmeter-runtime.log" \
    run_jmeter -n -t "$SINGLE" -l "$dir/results.jtl" \
        -Jthreads=1 -Jduration=5 -Jrampup=1 \
        -Jwebinsight.report.output="$dir" \
        -Jwebinsight.report.filename="$1" > "$dir/jmeter.log" 2>&1
    file_exists "$dir/$2" \
        || C24_NOTES="$(append_note "$C24_NOTES" "'$1' did not produce $2")"
    file_absent "$dir/$1.html" \
        || C24_NOTES="$(append_note "$C24_NOTES" "'$1' also produced the stacked $1.html")"
}
c24_case "MyReport.HTML" "MyReport.html"
c24_case "report.htm"    "report.html"
c24_case "report.json"   "report.html"
# A bare extension has no base name to build siblings from, so it falls back.
c24_dot="$C24_DIR/dot"; mkdir -p "$c24_dot"
WIR_RUNTIME_LOG="$c24_dot/jmeter-runtime.log" \
run_jmeter -n -t "$SINGLE" -l "$c24_dot/results.jtl" \
    -Jthreads=1 -Jduration=5 -Jrampup=1 \
    -Jwebinsight.report.output="$c24_dot" \
    -Jwebinsight.report.filename='.html' > "$c24_dot/jmeter.log" 2>&1
file_exists "$c24_dot/web-insight-report.html" \
    || C24_NOTES="$(append_note "$C24_NOTES" "'.html' did not fall back to the default")"
if [[ -z "$C24_NOTES" ]]; then
    record C24 "Extension handling" PASS "case-insensitive, non-stacking, no dotfiles"
else
    record C24 "Extension handling" FAIL "$C24_NOTES"
fi

# -----------------------------------------------------------------------------
# C25: The external-data threshold is configurable, and a bad value is refused.
#
#      It was documented as configurable and was not: setExternalDataThreshold had
#      zero call sites in either module, so the 10 MB switch point could not be tuned.
# -----------------------------------------------------------------------------
echo "[C25] External data threshold..."
C25_DIR="$RESULTS/C25"
rm -rf "$C25_DIR"; mkdir -p "$C25_DIR"
C25_LOW="$C25_DIR/low"; mkdir -p "$C25_LOW"
WIR_RUNTIME_LOG="$C25_LOW/jmeter-runtime.log" \
run_jmeter -n -t "$SINGLE" -l "$C25_LOW/results.jtl" \
    -Jthreads=1 -Jduration=5 -Jrampup=1 \
    -Jwebinsight.report.output="$C25_LOW" \
    -Jwebinsight.report.filename=C25-report.html \
    -Jwebinsight.report.externaldata.threshold=1000 > "$C25_LOW/jmeter.log" 2>&1
C25_BAD="$C25_DIR/bad"; mkdir -p "$C25_BAD"
WIR_RUNTIME_LOG="$C25_BAD/jmeter-runtime.log" \
run_jmeter -n -t "$SINGLE" -l "$C25_BAD/results.jtl" \
    -Jthreads=1 -Jduration=5 -Jrampup=1 \
    -Jwebinsight.report.output="$C25_BAD" \
    -Jwebinsight.report.filename=C25-report.html \
    -Jwebinsight.report.externaldata.threshold=-5 > "$C25_BAD/jmeter.log" 2>&1

C25_NOTES=""
file_exists "$C25_LOW/C25-report-data.js" \
    || C25_NOTES="$(append_note "$C25_NOTES" "threshold=1000 did not trip external mode")"
assert_log_contains "$C25_BAD/jmeter-runtime.log" "must be greater than 0" \
    || C25_NOTES="$(append_note "$C25_NOTES" "negative threshold was not rejected with a WARN")"
file_absent "$C25_BAD/C25-report-data.js" \
    || C25_NOTES="$(append_note "$C25_NOTES" "negative threshold still forced external mode")"
if [[ -z "$C25_NOTES" ]]; then
    record C25 "External data threshold" PASS "1000 trips external mode; -5 rejected, default kept"
else
    record C25 "External data threshold" FAIL "$C25_NOTES"
fi

# -----------------------------------------------------------------------------
# C26: Two listeners in one plan are noticed.
#
#      Each carries its own stats engine and its own testEnded, so the same path was
#      rendered (~1.3 MB) and written twice and which one survived depended on
#      listener iteration order. The plan has existed in this repo all along and NO
#      runner referenced it, so the behaviour was never asserted on.
# -----------------------------------------------------------------------------
echo "[C26] Two listeners in one plan..."
C26_DIR="$RESULTS/C26"
rm -rf "$C26_DIR"; mkdir -p "$C26_DIR"
WIR_RUNTIME_LOG="$C26_DIR/jmeter-runtime.log" \
run_jmeter -n -t "$TEST_PLANS/multi-listener-test.jmx" -l "$C26_DIR/results.jtl" \
    -Jthreads=1 -Jduration=5 -Jrampup=1 \
    -Jwebinsight.report.output="$C26_DIR" \
    -Jwebinsight.report.filename=C26-report.html > "$C26_DIR/jmeter.log" 2>&1

if assert_log_contains "$C26_DIR/jmeter-runtime.log" "has already been written in this JVM"; then
    record C26 "Duplicate listener write" PASS "the second write to the same path is reported"
elif ! file_exists "$C26_DIR/C26-report.html"; then
    record C26 "Duplicate listener write" FAIL "no report generated at all"
else
    record C26 "Duplicate listener write" FAIL "two listeners wrote the same path with no warning"
fi

# -----------------------------------------------------------------------------
# C27: A .json that cannot be written is an ERROR and is shown on the terminal.
#
#      Both the JSON and XML writers swallow their exception by design — a report is
#      still worth having without them — but the swallow was total: WARN into
#      jmeter.log while the console printed unqualified success. A pipeline gating on
#      the JSON learned nothing from any channel it was watching.
#
#      The swallow is still by design and the HTML is still kept. What changed is that
#      the run no longer ends well: failonerror defaults to true and now covers a
#      PARTIAL failure, so an incomplete set exits non-zero. The exit code is asserted
#      here beside the INCOMPLETE marker because it is the only one of the two channels
#      a pipeline gets for free.
# -----------------------------------------------------------------------------
echo "[C27] The CI/CD JSON cannot be written..."
C27_DIR="$RESULTS/C27"
rm -rf "$C27_DIR"; mkdir -p "$C27_DIR"
mkdir -p "$C27_DIR/C27-report.json"   # a directory where the file must go
WIR_RUNTIME_LOG="$C27_DIR/jmeter-runtime.log" \
run_jmeter -n -t "$SINGLE" -l "$C27_DIR/results.jtl" \
    -Jthreads=1 -Jduration=5 -Jrampup=1 \
    -Jwebinsight.report.output="$C27_DIR" \
    -Jwebinsight.report.filename=C27-report.html > "$C27_DIR/jmeter.log" 2>&1
C27_RC=$?

C27_NOTES=""
file_exists "$C27_DIR/C27-report.html" \
    || C27_NOTES="$(append_note "$C27_NOTES" "the HTML should still be written")"
assert_log_contains "$C27_DIR/jmeter-runtime.log" "Failed to write the CI/CD JSON" \
    || C27_NOTES="$(append_note "$C27_NOTES" "no ERROR logged for the JSON failure")"
assert_contains "$C27_DIR/jmeter.log" "JSON : FAILED" \
    || C27_NOTES="$(append_note "$C27_NOTES" "the console summary does not show JSON : FAILED")"
assert_contains "$C27_DIR/jmeter.log" "WEB-INSIGHT-REPORT: INCOMPLETE" \
    || C27_NOTES="$(append_note "$C27_NOTES" "no INCOMPLETE marker on stderr")"
[[ "$C27_RC" -ne 0 ]] \
    || C27_NOTES="$(append_note "$C27_NOTES" "an incomplete set must now exit non-zero, and it exited 0")"
if [[ -z "$C27_NOTES" ]]; then
    record C27 "JSON write failure is visible" PASS \
        "HTML written, JSON failure on the console and at ERROR, run exited $C27_RC"
else
    record C27 "JSON write failure is visible" FAIL "$C27_NOTES"
fi

# -----------------------------------------------------------------------------
# C28: A disabled listener produces no report — documents matrix row A.
# -----------------------------------------------------------------------------
echo "[C28] Listener disabled..."
C28_DIR="$RESULTS/C28"
rm -rf "$C28_DIR"; mkdir -p "$C28_DIR"
WIR_RUNTIME_LOG="$C28_DIR/jmeter-runtime.log" \
run_jmeter -n -t "$TEST_PLANS/listener-disabled-test.jmx" -l "$C28_DIR/results.jtl" \
    -Jthreads=1 -Jduration=5 -Jrampup=1 \
    -Jwebinsight.report.output="$C28_DIR" > "$C28_DIR/jmeter.log" 2>&1

if file_absent "$C28_DIR/web-insight-report.html"; then
    # And nothing claimed otherwise: no intent line, because the plugin never ran.
    if assert_log_lacks "$C28_DIR/jmeter-runtime.log" "Web Insight Report: outputDir="; then
        record C28 "Listener disabled" PASS "no report and no intent line — the plugin did not run"
    else
        record C28 "Listener disabled" FAIL "no report, but something logged an intent line"
    fi
else
    record C28 "Listener disabled" FAIL "a disabled listener still produced a report"
fi

# -----------------------------------------------------------------------------
# C29: A plan with NO listener element still gets a report under -e -o.
#
#      Not a defect — it is what makes `jmeter -g old.jtl -o out` work at all — but it
#      means installing the plugin changes the output of other people's test plans on
#      that machine, so it is worth pinning.
# -----------------------------------------------------------------------------
echo "[C29] No listener element, -e -o..."
C29_DIR="$RESULTS/C29"
rm -rf "$C29_DIR"; mkdir -p "$C29_DIR"
WIR_RUNTIME_LOG="$C29_DIR/jmeter-runtime.log" \
run_jmeter -n -t "$TEST_PLANS/no-listener-test.jmx" -l "$C29_DIR/results.jtl" \
    -Jthreads=1 -Jduration=5 -Jrampup=1 \
    -e -o "$C29_DIR/dash" > "$C29_DIR/jmeter.log" 2>&1

if file_exists "$C29_DIR/dash/web-insight-report.html"; then
    record C29 "No listener element" PASS "the -g -o exporter generated anyway (globally registered)"
else
    record C29 "No listener element" FAIL "no report from the exporter"
fi

# -----------------------------------------------------------------------------
# C31: A report cannot overwrite JMeter's OWN dashboard files.
#
#      Verified live before the fix: -Jwebinsight.report.filename=index.html replaced
#      JMeter's 9,672-byte dashboard entry point with our 1.3 MB report, exit 0, no
#      warning. And =statistics produced NO report at all, because the broken
#      collision check found JMeter's statistics.json and skipped — so fixing that
#      check without this guard would have turned a missing report into a corrupted
#      statistics.json, which is strictly worse.
#
#      Asserts BOTH halves: JMeter's files are untouched AND a report still exists.
# -----------------------------------------------------------------------------
echo "[C31] Reserved dashboard filenames..."
C31_DIR="$RESULTS/C31"
rm -rf "$C31_DIR"; mkdir -p "$C31_DIR"
WIR_RUNTIME_LOG="$C31_DIR/src-runtime.log" \
run_jmeter -n -t "$TEST_PLANS/no-listener-test.jmx" -l "$C31_DIR/src.jtl" \
    -Jthreads=1 -Jduration=5 -Jrampup=1 > "$C31_DIR/src.log" 2>&1

# Control, for the byte comparison.
WIR_RUNTIME_LOG="$C31_DIR/ctl-runtime.log" \
run_jmeter -g "$C31_DIR/src.jtl" -o "$C31_DIR/ctl" > "$C31_DIR/ctl.log" 2>&1
C31_INDEX_MD5="$(file_md5 "$C31_DIR/ctl/index.html")"
C31_STATS_MD5="$(file_md5 "$C31_DIR/ctl/statistics.json")"

C31_NOTES=""
c31_case() {   # <configured name> <subdir>
    local dir="$C31_DIR/$2"
    WIR_RUNTIME_LOG="$C31_DIR/$2-runtime.log" \
    run_jmeter -g "$C31_DIR/src.jtl" -o "$dir" \
        -Jwebinsight.report.filename="$1" > "$C31_DIR/$2.log" 2>&1
    [[ "$(file_md5 "$dir/index.html")" == "$C31_INDEX_MD5" ]] \
        || C31_NOTES="$(append_note "$C31_NOTES" "'$1' modified JMeter's index.html")"
    [[ "$(file_md5 "$dir/statistics.json")" == "$C31_STATS_MD5" ]] \
        || C31_NOTES="$(append_note "$C31_NOTES" "'$1' modified JMeter's statistics.json")"
    # The other half: a report must still exist.
    file_exists "$dir/web-insight-report.html" \
        || C31_NOTES="$(append_note "$C31_NOTES" "'$1' produced no Web Insight report at all")"
    assert_log_contains "$C31_DIR/$2-runtime.log" "JMeter's own HTML dashboard" \
        || C31_NOTES="$(append_note "$C31_NOTES" "'$1' was not reported as a reserved name")"
}
if [[ "$C31_INDEX_MD5" == "none" ]]; then
    record C31 "Reserved dashboard filenames" FAIL "the control run produced no dashboard to compare against"
else
    c31_case "index.html" "asindex"
    c31_case "statistics" "asstats"
    if [[ -z "$C31_NOTES" ]]; then
        record C31 "Reserved dashboard filenames" PASS "dashboard untouched and a report still produced"
    else
        record C31 "Reserved dashboard filenames" FAIL "$C31_NOTES"
    fi
fi

# -----------------------------------------------------------------------------
# C30: The plugin says where it is writing, on a channel someone reads.
#
#      Three separate defects made this scenario necessary, and all three were
#      invisible to the suite before it existed:
#
#      1. Nothing stated intent. No line, at any level, said "I am about to write X
#         into Y", so every silent-no-output case — a rejected filename, an
#         unwritable directory, the -g -o exporter skipping — left no record of the
#         resolved directory or filename, and often no evidence the plugin ran.
#      2. Three of the four artifacts were announced only via log.info. JMeter's
#         default log4j2.xml has NO Console appender, so the JSON a pipeline gates
#         on was named nowhere a pipeline could see.
#      3. No runner captured JMeter's own log at all (see WIR_RUNTIME_LOG in
#         common.sh), so even the lines that did exist were unassertable.
#
#      Asserts the intent line is present AND absolute — a relative
#      -Jwebinsight.report.output resolves against the process working directory,
#      so the configured string tells an operator much less than the resolved one.
# -----------------------------------------------------------------------------
echo "[C30] Output paths are announced on stdout and in the log..."
C30_DIR="$RESULTS/C30"
mkdir -p "$C30_DIR"
C30_LOG="$C30_DIR/jmeter-runtime.log"
C30_CONSOLE="$C30_DIR/jmeter.log"
WIR_RUNTIME_LOG="$C30_LOG" \
run_jmeter -n -t "$SINGLE" -l "$C30_DIR/results.jtl" \
    -Jthreads=1 -Jduration=5 -Jrampup=1 \
    -Jwebinsight.report.output="$C30_DIR" \
    -Jwebinsight.report.filename=C30-report.html \
    -Jwebinsight.report.junit=true \
    > "$C30_CONSOLE" 2>&1

C30_NOTES=""
if ! file_exists "$C30_DIR/C30-report.html"; then
    record C30 "Output paths announced" FAIL "report not generated"
else
    # LOG-1: intent, in JMeter's own log, naming every planned artifact.
    assert_log_contains "$C30_LOG" "Web Insight Report: outputDir=" \
        || C30_NOTES="$(append_note "$C30_NOTES" "no intent line in the runtime log")"
    # Absolute, whichever platform: a leading / or a drive letter. Comparing against
    # $C30_DIR directly cannot work — Git Bash says /d/... where Java says D:\...
    grep -qE 'outputDir=(/|[A-Za-z]:\\)' "$C30_LOG" 2>/dev/null \
        || C30_NOTES="$(append_note "$C30_NOTES" "intent line outputDir is not absolute")"
    assert_log_contains "$C30_LOG" "html=C30-report.html" \
        || C30_NOTES="$(append_note "$C30_NOTES" "intent line does not name the html")"
    assert_log_contains "$C30_LOG" "json=C30-report.json" \
        || C30_NOTES="$(append_note "$C30_NOTES" "intent line does not name the json")"
    assert_log_contains "$C30_LOG" "xml=C30-report.xml" \
        || C30_NOTES="$(append_note "$C30_NOTES" "intent line does not name the xml")"

    # LOG-2: the terminal names every artifact, not just the HTML.
    assert_contains "$C30_CONSOLE" "Web Insight Report generated:" \
        || C30_NOTES="$(append_note "$C30_NOTES" "no summary block on the console")"
    assert_contains "$C30_CONSOLE" "C30-report.json" \
        || C30_NOTES="$(append_note "$C30_NOTES" "console never names the JSON")"
    assert_contains "$C30_CONSOLE" "C30-report.xml" \
        || C30_NOTES="$(append_note "$C30_NOTES" "console never names the XML")"
    assert_contains "$C30_CONSOLE" "DATA : inline" \
        || C30_NOTES="$(append_note "$C30_NOTES" "console does not report the payload mode")"

    # LOG-3: the one line CI greps.
    assert_contains "$C30_CONSOLE" "WEB-INSIGHT-REPORT: OK " \
        || C30_NOTES="$(append_note "$C30_NOTES" "no OK marker on stdout")"

    if [[ -z "$C30_NOTES" ]]; then
        record C30 "Output paths announced" PASS "intent line, artifact block and OK marker all present"
    else
        record C30 "Output paths announced" FAIL "$C30_NOTES"
    fi
fi

# -----------------------------------------------------------------------------
# C32: -Jwebinsight.report.failonassert turns a FAIL verdict into a non-zero exit,
#      and DOES NOT unless asked.
#
#      The default matters more than the flag. Every other way this plugin can read
#      red -- a rule the file got wrong, a target that matched nothing, an annotations
#      file that would not load -- is reported and counted and never touches the exit
#      code. Only a declared assert measured against a real number can fail a build,
#      and even that is opt-in, because deciding what a FAIL means is the tester's job.
#
#      Both directions, and the DEFAULT run is the one that would rot silently: a flag
#      that quietly became on-by-default would turn every existing red gate into a
#      broken pipeline, and nothing else in the suite would notice.
# -----------------------------------------------------------------------------
echo "[C32] failonassert turns a FAIL verdict into an exit code, only when asked..."
C32_DIR="$RESULTS/C32"
rm -rf "$C32_DIR"; mkdir -p "$C32_DIR"
cp -f "$ANNOTATIONS_DIR/asserts-strict.json" "$C32_DIR/report-annotations.json"

# 1. Default. A gate that says FAIL, and an exit code that does not.
run_jmeter -n -t "$TEST_PLANS/asserts-scenario-test.jmx" -l "$C32_DIR/default.jtl" \
    -Jthreads=5 -Jduration=15 -Jrampup=3 \
    -Jwebinsight.report.output="$C32_DIR" \
    -Jwebinsight.report.filename=C32-default.html \
    -Jwebinsight.report.annotations="$C32_DIR/report-annotations.json" \
    > "$C32_DIR/default.log" 2>&1
C32_DEFAULT_RC=$?

C32_NOTES=""
assert_contains "$C32_DIR/C32-default.json" '"status" : "FAIL"' \
    || C32_NOTES="$(append_note "$C32_NOTES" "the strict asserts did not produce a FAIL gate, so this proves nothing")"
assert_exit_code "$C32_DEFAULT_RC" 0 \
    || C32_NOTES="$(append_note "$C32_NOTES" "a FAIL verdict exited $C32_DEFAULT_RC by DEFAULT -- failonassert must be opt-in")"

# 2. Asked for. Same run, same verdict, non-zero status.
run_jmeter -n -t "$TEST_PLANS/asserts-scenario-test.jmx" -l "$C32_DIR/optin.jtl" \
    -Jthreads=5 -Jduration=15 -Jrampup=3 \
    -Jwebinsight.report.output="$C32_DIR" \
    -Jwebinsight.report.filename=C32-optin.html \
    -Jwebinsight.report.annotations="$C32_DIR/report-annotations.json" \
    -Jwebinsight.report.failonassert=true \
    > "$C32_DIR/optin.log" 2>&1
C32_OPTIN_RC=$?

[[ "$C32_OPTIN_RC" -ne 0 ]] \
    || C32_NOTES="$(append_note "$C32_NOTES" "failonassert=true left the run at exit 0 on a FAIL gate")"
# The report is still written. Failing the build must not cost the artifact that explains why.
file_exists "$C32_DIR/C32-optin.html" \
    || C32_NOTES="$(append_note "$C32_NOTES" "failonassert suppressed the report itself")"
assert_contains "$C32_DIR/C32-optin.json" '"status" : "FAIL"' \
    || C32_NOTES="$(append_note "$C32_NOTES" "the opt-in run did not reach the same verdict")"

if [[ -z "$C32_NOTES" ]]; then
    record C32 "failonassert is opt-in" PASS "FAIL exits 0 by default, 1 with the flag, report written either way"
else
    record C32 "failonassert is opt-in" FAIL "$C32_NOTES"
fi

# -----------------------------------------------------------------------------
# C33: a gate that could not be BUILT fails the run; a gate that FAILED does not.
#
#      Those two sentences are the whole change, and run 4 is the one that keeps
#      them apart. A rule whose metric does not exist means the gate is not
#      running while its author believes it is -- the same category as an
#      unwritable output directory, which is what failonerror has always covered.
#      A breached threshold is a verdict, and a verdict is the tester's to act on.
#
#      Run 1 is the half that would rot silently. If this ever went back to
#      exiting 0, every pipeline with a typo'd metric would go quietly green and
#      nothing else in the suite would notice.
# -----------------------------------------------------------------------------
echo "[C33] A gate that could not be built fails; a gate that failed does not..."
C33_DIR="$RESULTS/C33"
rm -rf "$C33_DIR"; mkdir -p "$C33_DIR"
C33_NOTES=""

# A file with one unrunnable rule beside one healthy one. The healthy rule matters:
# it proves the gate still ran and still judged, so run 1 is not passing for the
# trivial reason that nothing happened at all.
cat > "$C33_DIR/broken.json" <<'JSON'
{
  "assertions": [
    { "id": "healthy", "target": { "match": "*" }, "metric": "p95", "fail": { "gt": 999999 } },
    { "id": "typo",    "target": { "match": "*" }, "metric": "p95x", "fail": { "gt": 1 } }
  ]
}
JSON

# 1. Default: the file is wrong, so the run is too.
run_jmeter -n -t "$TEST_PLANS/asserts-scenario-test.jmx" -l "$C33_DIR/broken.jtl" \
    -Jthreads=5 -Jduration=15 -Jrampup=3 \
    -Jwebinsight.report.output="$C33_DIR" \
    -Jwebinsight.report.filename=C33-broken.html \
    -Jwebinsight.report.annotations="$C33_DIR/broken.json" \
    > "$C33_DIR/broken.log" 2>&1
C33_BROKEN_RC=$?

[[ "$C33_BROKEN_RC" -ne 0 ]] \
    || C33_NOTES="$(append_note "$C33_NOTES" "a rule that could not run left the exit code at 0")"
assert_contains "$C33_DIR/broken.log" "WEB-INSIGHT-REPORT: CONFIG ERROR" \
    || C33_NOTES="$(append_note "$C33_NOTES" "no CONFIG ERROR marker on the console")"
assert_contains "$C33_DIR/C33-broken.json" '"invalidRule" : 1' \
    || C33_NOTES="$(append_note "$C33_NOTES" "gate.notJudged.invalidRule is not 1")"
# Failing the build must not cost the artifact that explains why.
file_exists "$C33_DIR/C33-broken.html" \
    || C33_NOTES="$(append_note "$C33_NOTES" "the HTML was suppressed by the failure")"
file_exists "$C33_DIR/C33-broken.json" \
    || C33_NOTES="$(append_note "$C33_NOTES" "the CI/CD JSON was suppressed by the failure")"
# And the gate really did run: the healthy rule was judged.
assert_contains "$C33_DIR/C33-broken.json" '"passed" : 1' \
    || C33_NOTES="$(append_note "$C33_NOTES" "the healthy rule was not judged, so run 1 proves nothing")"

# 2. The opt-out buys back the exit code and nothing else.
run_jmeter -n -t "$TEST_PLANS/asserts-scenario-test.jmx" -l "$C33_DIR/optout.jtl" \
    -Jthreads=5 -Jduration=15 -Jrampup=3 \
    -Jwebinsight.report.output="$C33_DIR" \
    -Jwebinsight.report.filename=C33-optout.html \
    -Jwebinsight.report.annotations="$C33_DIR/broken.json" \
    -Jwebinsight.report.failonerror=false \
    > "$C33_DIR/optout.log" 2>&1
C33_OPTOUT_RC=$?

assert_exit_code "$C33_OPTOUT_RC" 0 \
    || C33_NOTES="$(append_note "$C33_NOTES" "failonerror=false should restore exit 0, got $C33_OPTOUT_RC")"
assert_contains "$C33_DIR/optout.log" "WEB-INSIGHT-REPORT: CONFIG ERROR" \
    || C33_NOTES="$(append_note "$C33_NOTES" "failonerror=false silenced the marker as well as the exit code")"

# 3. An annotations file that cannot be read at all is the same kind of wrong.
run_jmeter -n -t "$TEST_PLANS/asserts-scenario-test.jmx" -l "$C33_DIR/nofile.jtl" \
    -Jthreads=5 -Jduration=15 -Jrampup=3 \
    -Jwebinsight.report.output="$C33_DIR" \
    -Jwebinsight.report.filename=C33-nofile.html \
    -Jwebinsight.report.annotations="$C33_DIR/definitely-not-here.json" \
    > "$C33_DIR/nofile.log" 2>&1
C33_NOFILE_RC=$?

[[ "$C33_NOFILE_RC" -ne 0 ]] \
    || C33_NOTES="$(append_note "$C33_NOTES" "a missing annotations file left the exit code at 0")"
assert_contains "$C33_DIR/C33-nofile.json" '"findings"' \
    || C33_NOTES="$(append_note "$C33_NOTES" "the missing file is not in findings[]")"

# 4. THE ONE THAT KEEPS THE TWO SENTENCES APART. A healthy file whose gate FAILS
#    must still exit 0 -- a verdict is the tester's to act on, and failonassert is
#    how you ask for it (C32). If this ever goes non-zero, this change has become
#    "anything red fails the build", which is not what was agreed.
cp -f "$ANNOTATIONS_DIR/asserts-strict.json" "$C33_DIR/strict.json"
run_jmeter -n -t "$TEST_PLANS/asserts-scenario-test.jmx" -l "$C33_DIR/verdict.jtl" \
    -Jthreads=5 -Jduration=15 -Jrampup=3 \
    -Jwebinsight.report.output="$C33_DIR" \
    -Jwebinsight.report.filename=C33-verdict.html \
    -Jwebinsight.report.annotations="$C33_DIR/strict.json" \
    > "$C33_DIR/verdict.log" 2>&1
C33_VERDICT_RC=$?

assert_contains "$C33_DIR/C33-verdict.json" '"status" : "FAIL"' \
    || C33_NOTES="$(append_note "$C33_NOTES" "the strict file did not produce a FAIL gate, so run 4 proves nothing")"
assert_exit_code "$C33_VERDICT_RC" 0 \
    || C33_NOTES="$(append_note "$C33_NOTES" "a FAIL VERDICT exited $C33_VERDICT_RC -- only a config error may do that")"

if [[ -z "$C33_NOTES" ]]; then
    record C33 "Config errors fail, verdicts do not" PASS "broken rule and missing file exit 1; failonerror=false restores 0; a FAIL verdict still exits 0"
else
    record C33 "Config errors fail, verdicts do not" FAIL "$C33_NOTES"
fi

# -----------------------------------------------------------------------------
# C34: the -Jwebinsight.compare.* thresholds actually reach the verdict.
#
#      Five properties documented in README.md, WIKI/FEATURES.md and
#      WIKI/Tutorial/06-Compare-Tab.md, and until this case nothing executed a single
#      one of them: the layering sat in a private method reading JMeterUtils, so it
#      could be deleted outright with every suite green.
#
#      ComparisonThresholdResolverTest pins the layering in milliseconds. It cannot
#      catch what this catches: the property NAME, spelled exactly as documented,
#      surviving a real JMeter process and landing in the serialized payload.
#
#      Hand-written JTLs, not load runs. The first attempt compared two real runs of
#      the same plan and failed: p95 came out 2ms LOWER in the current run, so there
#      was no regression to flag at any threshold. Between two arbitrary runs the sign
#      of the change is random, which makes a verdict-flip assertion a coin toss.
#      Here the current run is exactly 2x the baseline, so p95 and mean both change
#      by exactly +100%, every time, on every machine.
#
#      BOTH halves are decisive -- neither can pass unless a property was really read:
#        * relaxed: p95=500 must SUPPRESS the regression that the DEFAULT 10% flags.
#        * mean:    mean is DISABLED by default (-1), so a regression with p95 also
#                   relaxed can only come from the mean property being applied.
# -----------------------------------------------------------------------------
echo "[C34] Comparison threshold properties..."
C34_DIR="$RESULTS/C34"
rm -rf "$C34_DIR"; mkdir -p "$C34_DIR"

# All standard JMeter columns -- the -g pipeline requires responseMessage, threadName,
# dataType and the rest even though only elapsed differs between the two files.
cat > "$C34_DIR/baseline.jtl" << 'JTLEOF'
timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success,failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect
1700000000000,100,GET /api/thing,200,OK,Thread-1,text,true,,512,64,1,1,null,50,0,10
1700000001000,100,GET /api/thing,200,OK,Thread-1,text,true,,512,64,1,1,null,50,0,10
1700000002000,100,GET /api/thing,200,OK,Thread-1,text,true,,512,64,1,1,null,50,0,10
1700000003000,100,GET /api/thing,200,OK,Thread-1,text,true,,512,64,1,1,null,50,0,10
1700000004000,100,GET /api/thing,200,OK,Thread-1,text,true,,512,64,1,1,null,50,0,10
JTLEOF
sed 's/,100,GET/,200,GET/' "$C34_DIR/baseline.jtl" > "$C34_DIR/current.jtl"

c34_generate() {   # <subdir> <extra -J args...>
    local sub="$1"; shift
    WIR_RUNTIME_LOG="$C34_DIR/$sub-runtime.log" \
    run_jmeter -g "$C34_DIR/current.jtl" -o "$C34_DIR/$sub" \
        -Jwebinsight.report.baseline="$C34_DIR/baseline.jtl" \
        "$@" > "$C34_DIR/$sub.log" 2>&1
}

c34_generate relaxed -Jwebinsight.compare.p95.threshold=500
c34_generate mean -Jwebinsight.compare.p95.threshold=500 -Jwebinsight.compare.mean.threshold=5

C34_RELAXED="$C34_DIR/relaxed/web-insight-report.html"
C34_MEAN="$C34_DIR/mean/web-insight-report.html"
C34_NOTES=""

if ! file_exists "$C34_RELAXED" || ! file_exists "$C34_MEAN"; then
    record C34 "Comparison threshold properties" FAIL "a report was not generated"
else
    # Both reports must actually HAVE a comparison. Without this, a baseline that
    # silently failed to load makes the "no regression" assertion pass for entirely
    # the wrong reason -- the false green generate-cmp-fixture.sh documents.
    assert_contains "$C34_RELAXED" '\"hasComparison\":true' \
        || C34_NOTES="$(append_note "$C34_NOTES" "relaxed report has no comparison at all")"
    assert_contains "$C34_MEAN" '\"hasComparison\":true' \
        || C34_NOTES="$(append_note "$C34_NOTES" "mean report has no comparison at all")"

    # Escaped, so these match the serialized chartData and not the inlined report.js,
    # where the bare word "regression" appears in every report ever made.
    if assert_contains "$C34_RELAXED" '\"regression\":true'; then
        C34_NOTES="$(append_note "$C34_NOTES" "p95=500 still flagged a +100% change -- the p95 property was ignored")"
    fi
    assert_contains "$C34_MEAN" '\"regression\":true' \
        || C34_NOTES="$(append_note "$C34_NOTES" "mean=5 flagged nothing on a +100% mean -- the mean property was ignored")"

    if [[ -z "$C34_NOTES" ]]; then
        record C34 "Comparison threshold properties" PASS \
            "p95 suppressed the default verdict and mean enabled a disabled metric"
    else
        record C34 "Comparison threshold properties" FAIL "$C34_NOTES"
    fi
fi

# -----------------------------------------------------------------------------
# C35: -Jwebinsight.report.servermetrics reaches the report through a real JMeter process,
#      and the three-step choreography holds end to end with a recorded source (--recording).
#
#      ServerMetricsGenerationTest pins the generator in-process. It cannot catch what this
#      catches: the property NAME surviving a real -g -o run, wir-collect.sh building a
#      classpath JMeter's lib/ can satisfy, and the payload's runId matching the report's
#      when the collector and the exporter both name the run by the default title.
#
#      The collector's console must say the file source read its recording (OK, not EMPTY):
#      since D27 an EMPTY series exits 0, so the exit code no longer proves it.
#
#      Two runs, each decisive:
#        * this run's payload: the HTML carries the collection id the collector printed.
#        * another run's payload (runId rewritten): refused, CONFIG ERROR, non-zero exit
#          under the default failonerror, an ERROR finding in the CI/CD JSON — and the report
#          is still written, and the page says the payload was refused.
#      (A third run, the same refusal under an observe-only gate, went with failsBuild — C6.)
# -----------------------------------------------------------------------------
echo "[C35] Server-metrics payload property..."
C35_DIR="$RESULTS/C35"
rm -rf "$C35_DIR"; mkdir -p "$C35_DIR/rec"

# Its own five-sample JTL, like every other stage: the same shape C34 writes, owned here so the
# stage cannot be broken by C34 moving or being skipped.
cat > "$C35_DIR/current.jtl" << 'JTLEOF'
timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success,failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect
1700000000000,200,GET /api/thing,200,OK,Thread-1,text,true,,512,64,1,1,null,50,0,10
1700000001000,200,GET /api/thing,200,OK,Thread-1,text,true,,512,64,1,1,null,50,0,10
1700000002000,200,GET /api/thing,200,OK,Thread-1,text,true,,512,64,1,1,null,50,0,10
1700000003000,200,GET /api/thing,200,OK,Thread-1,text,true,,512,64,1,1,null,50,0,10
1700000004000,200,GET /api/thing,200,OK,Thread-1,text,true,,512,64,1,1,null,50,0,10
JTLEOF

# The recording for the one query, under the name a file source looks for: the first 16 hex of
# SHA-256 over the resolved query — RunIdentity.digest, computed here independently through
# common.sh's sha256_hex (sha256sum on GNU, shasum -a 256 on macOS).
C35_QUERY='up'
C35_DIGEST="$(printf '%s' "$C35_QUERY" | sha256_hex | cut -c1-16)"
C35_NOTES=""
[[ ${#C35_DIGEST} == 16 ]] || C35_NOTES="$(append_note "$C35_NOTES" "could not fingerprint the query (no sha256 tool?)")"
cat > "$C35_DIR/rec/$C35_DIGEST.json" << 'PROMEOF'
{"status":"success","data":{"resultType":"matrix","result":[{"metric":{"pod":"a"},"values":[[1700000000,"1"],[1700000001,"2"],[1700000002,"3"],[1700000003,"4"],[1700000004,"5"]]}]}}
PROMEOF
# No connection in the file (C8): the recording is the collector's --recording.
cat > "$C35_DIR/annotations.json" << ANNEOF
{"serverMetrics":{"components":{"app":{"type":"custom"}},
 "asserts":[{"id":"up","component":"app","query":"$C35_QUERY","reduce":{"kind":"max"},"fail":{"gt":10}}]}}
ANNEOF

"$PROJECT_DIR/scripts/wir-collect.sh" --jtl "$C35_DIR/current.jtl" \
        --annotations "$C35_DIR/annotations.json" --recording "$C35_DIR/rec" --step 1s \
        --out "$C35_DIR/server-metrics.json" --no-wait \
        > "$C35_DIR/collect.log" 2>&1
C35_COLLECT_RC=$?   # captured before any `if`: inside `if ! cmd; then`, $? is the negation's 0
if (( C35_COLLECT_RC != 0 )); then
    C35_NOTES="$(append_note "$C35_NOTES" "wir-collect exited $C35_COLLECT_RC -- see $C35_DIR/collect.log")"
fi
# ...and the file source READ the recording: OK, not EMPTY. The exit code cannot say so any more:
# until D27 an unread recording exited 2 and failed the line above, and now an EMPTY series exits 0
# (the payload records it) and a NO_MATCH is not fatal (D3), so without this C35 passed on a file
# source that read nothing (review:W).
# A custom series is named component/query on the console (SF25), as a catalogue one is
# component/metric: two components may ask one query, and the bare query printed both alike.
assert_contains "$C35_DIR/collect.log" "wir-collect: OK app/up coverage=" \
    || C35_NOTES="$(append_note "$C35_NOTES" "the file source did not read its recording: no 'wir-collect: OK app/up' -- see $C35_DIR/collect.log")"
C35_COLLECTION="$(grep -o '"collectionId" *: *"[0-9a-f]*"' "$C35_DIR/server-metrics.json" 2>/dev/null | grep -o '[0-9a-f]\{16\}')"
[[ -n "$C35_COLLECTION" ]] || C35_NOTES="$(append_note "$C35_NOTES" "no collectionId in the payload")"

c35_generate() {   # <subdir> <payload> [annotations]
    local sub="$1" payload="$2" ann="${3:-$C35_DIR/annotations.json}"
    WIR_RUNTIME_LOG="$C35_DIR/$sub-runtime.log" \
    run_jmeter -g "$C35_DIR/current.jtl" -o "$C35_DIR/$sub" \
        -Jwebinsight.report.annotations="$ann" \
        -Jwebinsight.report.servermetrics="$payload" > "$C35_DIR/$sub.log" 2>&1
    echo $? > "$C35_DIR/$sub.exit"
}

c35_generate mine "$C35_DIR/server-metrics.json"
# Another run's payload: the same file with its runId rewritten.
sed 's/"runId" *: *"[0-9a-f]*"/"runId" : "0123456789abcdef"/' "$C35_DIR/server-metrics.json" \
    > "$C35_DIR/foreign.json"
c35_generate foreign "$C35_DIR/foreign.json"

C35_MINE="$C35_DIR/mine/web-insight-report.html"
C35_FOREIGN="$C35_DIR/foreign/web-insight-report.html"
if ! file_exists "$C35_MINE"; then
    C35_NOTES="$(append_note "$C35_NOTES" "no report for this run's payload")"
else
    assert_contains "$C35_MINE" "\\\"collectionId\\\":\\\"$C35_COLLECTION\\\"" \
        || C35_NOTES="$(append_note "$C35_NOTES" "the HTML does not carry the collected payload ($C35_COLLECTION)")"
    [[ "$(cat "$C35_DIR/mine.exit")" == "0" ]] \
        || C35_NOTES="$(append_note "$C35_NOTES" "this run's payload exited $(cat "$C35_DIR/mine.exit"), expected 0")"
fi
if ! file_exists "$C35_FOREIGN"; then
    C35_NOTES="$(append_note "$C35_NOTES" "a foreign payload must still produce a report")"
else
    if assert_contains "$C35_FOREIGN" "\\\"collectionId\\\":\\\"$C35_COLLECTION\\\""; then
        C35_NOTES="$(append_note "$C35_NOTES" "another run's payload was drawn under this run's report")"
    fi
    grep -q "CONFIG ERROR" "$C35_DIR/foreign.log" "$C35_DIR/foreign-runtime.log" 2>/dev/null \
        || C35_NOTES="$(append_note "$C35_NOTES" "no CONFIG ERROR marker for a foreign payload")"
    [[ "$(cat "$C35_DIR/foreign.exit")" != "0" ]] \
        || C35_NOTES="$(append_note "$C35_NOTES" "a foreign payload exited 0 under the default failonerror")"
    # ...and the CI/CD JSON says so: a refused payload leaves no serverStatus or serverGates to
    # read, so the ERROR finding is the only trace a pipeline on failonerror=false can see (the
    # STRICT line of test-plans/goldens/jq-gate.txt reads it). It used to leave none.
    grep -B1 '"ruleId" : "serverMetrics.payload"' "$C35_DIR/foreign/web-insight-report.json" 2>/dev/null \
            | grep -q '"severity" : "error"' \
        || C35_NOTES="$(append_note "$C35_NOTES" "the fatal refusal is not an ERROR finding in the CI/CD JSON")"
fi

if file_exists "$C35_FOREIGN"; then
    assert_contains "$C35_FOREIGN" '\"serverPayloadRefused\":{\"reason\":\"FOREIGN_RUN\"' \
        || C35_NOTES="$(append_note "$C35_NOTES" "the page no longer says the payload was refused")"
fi

if [[ -z "$C35_NOTES" ]]; then
    record C35 "Server-metrics payload property" PASS \
        "collected $C35_COLLECTION, embedded for this run, refused (and fatal) for another"
else
    record C35 "Server-metrics payload property" FAIL "$C35_NOTES"
fi

# -----------------------------------------------------------------------------
# Shared by C36/C37: a five-sample JTL and a `file` recording for the query `up` (values 1..5,
# so max = 5), exactly as C35 builds them, plus the collector run that binds the payload to the
# JTL's runId. Each stage owns its own copy.
# -----------------------------------------------------------------------------
write_server_gate_inputs() {   # <dir>
    local d="$1"
    mkdir -p "$d/rec"
    cat > "$d/current.jtl" << 'JTLEOF'
timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success,failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect
1700000000000,200,GET /api/thing,200,OK,Thread-1,text,true,,512,64,1,1,null,50,0,10
1700000001000,200,GET /api/thing,200,OK,Thread-1,text,true,,512,64,1,1,null,50,0,10
1700000002000,200,GET /api/thing,200,OK,Thread-1,text,true,,512,64,1,1,null,50,0,10
1700000003000,200,GET /api/thing,200,OK,Thread-1,text,true,,512,64,1,1,null,50,0,10
1700000004000,200,GET /api/thing,200,OK,Thread-1,text,true,,512,64,1,1,null,50,0,10
JTLEOF
    local digest
    digest="$(printf '%s' up | sha256_hex | cut -c1-16)"
    # C35's guard, kept here so both stages get the honest message: with no sha256 tool on PATH
    # the digest is empty, the recording is written as a hidden ".json", the file source finds
    # nothing, and both stages fail complaining about gates and exit codes instead.
    if [[ ${#digest} -ne 16 ]]; then
        echo "  [SKIP-SETUP] no usable sha256 tool: the query digest came out as '$digest'" >&2
        return 1
    fi
    cat > "$d/rec/$digest.json" << 'PROMEOF'
{"status":"success","data":{"resultType":"matrix","result":[{"metric":{"pod":"a"},"values":[[1700000000,"1"],[1700000001,"2"],[1700000002,"3"],[1700000003,"4"],[1700000004,"5"]]}]}}
PROMEOF
}

# -----------------------------------------------------------------------------
# C36: a server component's FAIL reaches the exit code through failonassert. The fold, through a
#      real process:
#        1. a component FAILs, default flags -> exit 0 (a verdict is the tester's to act on), and
#           the FAIL is a JUnit <failure>
#        2. the same with failonassert=true -> exit 1, ASSERT FAIL marker naming the component
#      (A third run, an observe-only gate's FAIL left at exit 0, went with failsBuild — C6.)
# -----------------------------------------------------------------------------
echo "[C36] Server gates in the exit fold..."
C36_DIR="$RESULTS/C36"
rm -rf "$C36_DIR"
write_server_gate_inputs "$C36_DIR"
cat > "$C36_DIR/building.json" << 'ANNEOF'
{"serverMetrics":{"components":{"kafka":{"type":"custom","description":"Kafka"}},
 "asserts":[{"id":"up-max","component":"kafka","query":"up","reduce":{"kind":"max"},"fail":{"gt":1}}]}}
ANNEOF
# The CSV backend (--csv), through a real process: C35 has the Prometheus replay, so the two
# stages between them cover both. The same values as the recording (up = 1..5 over the run).
cat > "$C36_DIR/metrics.csv" << 'CSVEOF'
time,component,metric,instance,value
2023-11-14T22:13:20Z,kafka,up,a,1
2023-11-14T22:13:21Z,kafka,up,a,2
2023-11-14T22:13:22Z,kafka,up,a,3
2023-11-14T22:13:23Z,kafka,up,a,4
2023-11-14T22:13:24Z,kafka,up,a,5
CSVEOF
C36_NOTES=""
"$PROJECT_DIR/scripts/wir-collect.sh" --jtl "$C36_DIR/current.jtl" --annotations "$C36_DIR/building.json" \
        --csv "$C36_DIR/metrics.csv" --step 1s \
        --out "$C36_DIR/server-metrics.json" --no-wait > "$C36_DIR/collect.log" 2>&1
C36_COLLECT_RC=$?
(( C36_COLLECT_RC == 0 )) || C36_NOTES="$(append_note "$C36_NOTES" "wir-collect exited $C36_COLLECT_RC -- see $C36_DIR/collect.log")"
assert_contains "$C36_DIR/collect.log" "wir-collect: OK kafka/up coverage=" \
    || C36_NOTES="$(append_note "$C36_NOTES" "the CSV backend did not read its rows: no 'wir-collect: OK kafka/up' -- see $C36_DIR/collect.log")"
assert_contains "$C36_DIR/server-metrics.json" '"type" : "csv"' \
    || C36_NOTES="$(append_note "$C36_NOTES" "the payload does not name the csv source")"

c36_generate() {   # <subdir> <annotations> [extra -J args...]
    local sub="$1" ann="$2"; shift 2
    WIR_RUNTIME_LOG="$C36_DIR/$sub-runtime.log" \
    run_jmeter -g "$C36_DIR/current.jtl" -o "$C36_DIR/$sub" \
        -Jwebinsight.report.annotations="$ann" \
        -Jwebinsight.report.servermetrics="$C36_DIR/server-metrics.json" \
        -Jwebinsight.report.junit=true "$@" > "$C36_DIR/$sub.log" 2>&1
    echo $? > "$C36_DIR/$sub.exit"
}

c36_generate verdict "$C36_DIR/building.json"
assert_exit_code "$(cat "$C36_DIR/verdict.exit")" 0 \
    || C36_NOTES="$(append_note "$C36_NOTES" "a server FAIL verdict exited $(cat "$C36_DIR/verdict.exit") by default -- only failonassert may do that")"
assert_contains "$C36_DIR/verdict/web-insight-report.json" '"serverComponents"' \
    || C36_NOTES="$(append_note "$C36_NOTES" "no serverComponents block in the CI/CD JSON")"
assert_contains "$C36_DIR/verdict/web-insight-report.json" '"id" : "kafka"' \
    || C36_NOTES="$(append_note "$C36_NOTES" "the kafka component is not in serverComponents")"
assert_contains "$C36_DIR/verdict.log" "Server kafka: FAIL" \
    || C36_NOTES="$(append_note "$C36_NOTES" "the console verdict line does not name the server gate")"
assert_contains "$C36_DIR/verdict/web-insight-report.xml" '- kafka"' \
    || C36_NOTES="$(append_note "$C36_NOTES" "no JUnit suite for the kafka gate")"
assert_contains "$C36_DIR/verdict/web-insight-report.xml" '<failure message="Server assert &apos;up-max&apos;' \
    || C36_NOTES="$(append_note "$C36_NOTES" "the building gate's FAIL is not a <failure> in the JUnit XML")"

c36_generate fatal "$C36_DIR/building.json" -Jwebinsight.report.failonassert=true
[[ "$(cat "$C36_DIR/fatal.exit")" != "0" ]] \
    || C36_NOTES="$(append_note "$C36_NOTES" "failonassert=true left a building server gate's FAIL at exit 0")"
assert_contains "$C36_DIR/fatal.log" "WEB-INSIGHT-REPORT: ASSERT FAIL" \
    || C36_NOTES="$(append_note "$C36_NOTES" "no ASSERT FAIL marker for the server gate")"
file_exists "$C36_DIR/fatal/web-insight-report.html" \
    || C36_NOTES="$(append_note "$C36_NOTES" "the HTML was suppressed by the failure")"

if [[ -z "$C36_NOTES" ]]; then
    record C36 "Server gates in the exit fold" PASS \
        "a component's FAIL exits 0 by default and 1 under failonassert, and is a JUnit <failure>"
else
    record C36 "Server gates in the exit fold" FAIL "$C36_NOTES"
fi

# -----------------------------------------------------------------------------
# C37: a source that never answered is a config error -- fatal under the default failonerror,
#      marked, and rendered as a JUnit <failure>. The source is --prometheus-url on a port
#      nothing listens on, so the collector records FAILED and the report says SOURCE_FAILED.
#      (Its observe-only half, silent on the exit code, went with failsBuild — C6.)
#
#      The collector itself exits 0 for it (D27): it wrote the payload, the payload says FAILED,
#      and the report decides what that means. And the last run here is the reason: the three
#      documented steps, as a CI job runs them -- one script under `set -e`, `jmeter` on PATH,
#      JMETER_HOME unset, and the collector a lone COPY of scripts/wir-collect.sh with nothing
#      beside it, which is all a release user has (M8-02). With a dead source that script must
#      reach step 3's report; step 3 runs failonerror=false, which is how a pipeline that wants
#      the report's verdict rather than its exit code asks for it. Until M7d it stopped after
#      step 2 (the collector exited 2), before the only report that gates; and the copy could not
#      have run at all (it sourced lib/common.sh from beside itself). The same copy with no java
#      to run refuses with exit 1 and a sentence, not exec's bare 127.
# -----------------------------------------------------------------------------
echo "[C37] A source that never answered..."
C37_DIR="$RESULTS/C37"
rm -rf "$C37_DIR"
write_server_gate_inputs "$C37_DIR"
cat > "$C37_DIR/building.json" << 'ANNEOF'
{"serverMetrics":{"components":{"kafka":{"type":"custom","description":"Kafka"}},
 "asserts":[{"id":"dead-max","component":"kafka","query":"up","reduce":{"kind":"max"},"fail":{"gt":1}}]}}
ANNEOF
C37_DEAD_URL="http://127.0.0.1:9/api/v1"
C37_NOTES=""
"$PROJECT_DIR/scripts/wir-collect.sh" --jtl "$C37_DIR/current.jtl" --annotations "$C37_DIR/building.json" \
        --prometheus-url "$C37_DEAD_URL" --step 1s \
        --out "$C37_DIR/server-metrics.json" --no-wait > "$C37_DIR/collect.log" 2>&1
C37_COLLECT_RC=$?
# 0, not 2 (D27): the payload was written, and FAILED is recorded in it. What a dead source means
# is the report's call -- the collector writes what happened, and step 3 decides.
assert_exit_code "$C37_COLLECT_RC" 0 \
    || C37_NOTES="$(append_note "$C37_NOTES" "wir-collect exited $C37_COLLECT_RC for a dead source, expected 0: it wrote the payload -- see $C37_DIR/collect.log")"
assert_contains "$C37_DIR/server-metrics.json" '"status" : "FAILED"' \
    || C37_NOTES="$(append_note "$C37_NOTES" "the payload does not record the source as FAILED")"
assert_contains "$C37_DIR/collect.log" "wir-collect: FAILED kafka/up" \
    || C37_NOTES="$(append_note "$C37_NOTES" "the collector's console does not name the failed series")"

c37_generate() {   # <subdir> <annotations>
    local sub="$1" ann="$2"
    WIR_RUNTIME_LOG="$C37_DIR/$sub-runtime.log" \
    run_jmeter -g "$C37_DIR/current.jtl" -o "$C37_DIR/$sub" \
        -Jwebinsight.report.annotations="$ann" \
        -Jwebinsight.report.servermetrics="$C37_DIR/server-metrics.json" \
        -Jwebinsight.report.junit=true > "$C37_DIR/$sub.log" 2>&1
    echo $? > "$C37_DIR/$sub.exit"
}

c37_generate building "$C37_DIR/building.json"
[[ "$(cat "$C37_DIR/building.exit")" != "0" ]] \
    || C37_NOTES="$(append_note "$C37_NOTES" "a source failure on a building gate left the exit code at 0")"
grep -q "WEB-INSIGHT-REPORT: CONFIG ERROR" "$C37_DIR/building.log" "$C37_DIR/building-runtime.log" 2>/dev/null \
    || C37_NOTES="$(append_note "$C37_NOTES" "no CONFIG ERROR marker for the failed source")"
grep -q "kafka: 1 source failed" "$C37_DIR/building.log" "$C37_DIR/building-runtime.log" 2>/dev/null \
    || C37_NOTES="$(append_note "$C37_NOTES" "the CONFIG ERROR line does not name the gate and the reason")"
assert_contains "$C37_DIR/building/web-insight-report.json" '"sourceFailed" : 1' \
    || C37_NOTES="$(append_note "$C37_NOTES" "serverComponents does not count the source failure")"
file_exists "$C37_DIR/building/web-insight-report.html" \
    || C37_NOTES="$(append_note "$C37_NOTES" "the HTML was suppressed by the failure")"

# By the reason as well as the tag: a bare '<failure' matches the client suite's own failures and
# would pass on a report where no server suite was written at all.
# "could not be collected: <why>" (SF23) - the series' fate and the collector's reason, never "because
# the source never answered", which was false beside a PARTIAL source.
assert_contains "$C37_DIR/building/web-insight-report.xml" '<failure message="Server assert could not be collected: ' \
    || C37_NOTES="$(append_note "$C37_NOTES" "the building gate's source failure is not a <failure> in the JUnit XML")"

# The pipeline (D27, M8-02). The steps are the ones WirCollect --help prints, with absolute paths
# so that nothing here depends on how JMeter resolves a relative one, and --no-wait because a
# source nothing listens on answers no better after the 30 s settle floor. Each step echoes a
# marker after it, so a stop says which step stopped it.
C37_PIPE="$C37_DIR/pipeline"
mkdir -p "$C37_PIPE/bin"
cp "$PROJECT_DIR/scripts/wir-collect.sh" "$C37_PIPE/bin/"
cp "$C37_DIR/building.json" "$C37_PIPE/report-annotations.json"
cat > "$C37_PIPE/pipeline.sh" << 'PIPEEOF'
set -e
A="$PWD/report-annotations.json"
# 1. the run, and its interim report (client gate only)
jmeter -n -t "$PLAN" -l "$PWD/results.jtl" -j "$PWD/step1-runtime.log" \
    -Jthreads=1 -Jduration=5 -Jrampup=1 \
    -Jwebinsight.report.output="$PWD/interim" -Jwebinsight.report.annotations="$A"
echo "[pipeline] step 1 exited 0"
# 2. the server metrics, beside the JTL
./bin/wir-collect.sh --jtl "$PWD/results.jtl" --annotations "$A" \
    --prometheus-url "$DEAD_URL" --step 1s --out "$PWD/server-metrics.json" --no-wait
echo "[pipeline] step 2 exited 0"
# 3. the report that gates -- its verdict read from the JSON, not from the exit code
jmeter -g "$PWD/results.jtl" -o "$PWD/report" -j "$PWD/step3-runtime.log" \
    -Jwebinsight.report.annotations="$A" \
    -Jwebinsight.report.servermetrics="$PWD/server-metrics.json" -Jwebinsight.report.junit=true \
    -Jwebinsight.report.failonerror=false
echo "[pipeline] step 3 exited 0"
PIPEEOF
C37_JMETER_BIN="$JMETER_HOME/bin"
(
    cd "$C37_PIPE" || exit 1
    unset JMETER_HOME
    PATH="$C37_JMETER_BIN:$PATH" PLAN="$SINGLE" DEAD_URL="$C37_DEAD_URL" bash pipeline.sh
) > "$C37_PIPE/pipeline.log" 2>&1
C37_PIPE_RC=$?
# -a throughout: JMeter's console lines carry a platform-charset em dash, which is not UTF-8, and
# without it GNU grep calls the log binary and -o prints "Binary file matches" for the step.
C37_PIPE_LAST="$(grep -a -o '\[pipeline\] step [0-9] exited 0' "$C37_PIPE/pipeline.log" | tail -1)"
if ! assert_exit_code "$C37_PIPE_RC" 0; then
    C37_NOTES="$(append_note "$C37_NOTES" "the documented pipeline stopped under set -e with exit $C37_PIPE_RC after '${C37_PIPE_LAST:-no step}' with a dead source -- see $C37_PIPE/pipeline.log")"
fi
grep -a -q "wir-collect: FAILED kafka/up" "$C37_PIPE/pipeline.log" \
    || C37_NOTES="$(append_note "$C37_NOTES" "the standalone copy of wir-collect.sh did not collect the dead source")"
# The same copy with no java to run: a refusal like every other, exit 1 and a sentence, never exec's
# bare 127 (review:W). Asked through a JAVA_HOME with no bin/java, because that is portable: a PATH
# without java is not, since macOS has a /usr/bin/java whether or not a JDK is installed.
mkdir -p "$C37_PIPE/no-jdk"
JMETER_HOME="$JMETER_HOME" JAVA_HOME="$C37_PIPE/no-jdk" "$C37_PIPE/bin/wir-collect.sh" --help \
    > "$C37_PIPE/no-java.log" 2>&1
C37_NOJAVA_RC=$?
assert_exit_code "$C37_NOJAVA_RC" 1 \
    || C37_NOTES="$(append_note "$C37_NOTES" "the standalone wir-collect.sh exited $C37_NOJAVA_RC for a JAVA_HOME with no bin/java, expected 1 -- see $C37_PIPE/no-java.log")"
assert_contains "$C37_PIPE/no-java.log" "wir-collect: JAVA_HOME is set to " \
    || C37_NOTES="$(append_note "$C37_NOTES" "the standalone wir-collect.sh did not say JAVA_HOME has no java -- see $C37_PIPE/no-java.log")"
file_exists "$C37_PIPE/interim/web-insight-report.html" \
    || C37_NOTES="$(append_note "$C37_NOTES" "step 1 wrote no interim report")"
if ! file_exists "$C37_PIPE/report/web-insight-report.html"; then
    C37_NOTES="$(append_note "$C37_NOTES" "the pipeline produced no step-3 report")"
else
    assert_contains "$C37_PIPE/report/web-insight-report.json" '"sourceFailed" : 1' \
        || C37_NOTES="$(append_note "$C37_NOTES" "the pipeline's step-3 report does not count the dead source")"
    assert_contains "$C37_PIPE/report/web-insight-report.xml" '<failure message="Server assert could not be collected: ' \
        || C37_NOTES="$(append_note "$C37_NOTES" "the pipeline's step-3 JUnit does not fail the dead source")"
fi

if [[ -z "$C37_NOTES" ]]; then
    record C37 "A source that never answered" PASS \
        "SOURCE_FAILED exits 1 under the default failonerror, is marked and is a JUnit failure; the collector exits 0, and the three steps under set -e reach step 3; a missing java is exit 1, not 127"
else
    record C37 "A source that never answered" FAIL "$C37_NOTES"
fi

# -----------------------------------------------------------------------------
# C38: the document the page saves gates the next jmeter -g exactly as the page showed it (the M6
#      gate: "download -> jmeter -g with the same payload -> same verdicts").
#
#      Every other tier compares the two evaluators on a file neither of them wrote. This feeds a
#      file the PAGE wrote back through Java, so what a Save adds or loses -- a band rounded on a
#      drag, a field the editor fills, a KPI parked or added, a client rule retuned under a server
#      assert that requires it -- reaches the judge that gates the build.
#
#        1. E26's inputs, -g -o, exactly as run-all-tests.sh builds E26: the page under test.
#        2. save-from-page.js loads the shipped report.js over that page's own chartData (both
#           authoring modules and the document lifecycle, as initCharts runs them) and writes three
#           documents with the verdicts shown beside each: an untouched page's Download, one after
#           a scripted session -- a client drag, a server drag whose rounding crosses the measured
#           value, an editor retune, a park and an add, each through the handler the UI calls --
#           and one where Generate from sources seeded an assert on a freed series.
#        3. Each document, -g -o over the same JTL and the same committed payload.
#        4. Every server assert (status, reason, sentence, actual, coverage, instances, band), every
#           server gate (status and every count) and the client gate and rules, field by field.
#        5. The same for an untouched Download of G3's committed input (gate-broken.json), six of
#           whose seven rules Java refuses (healthy-control is the PASS control), each refused rule's
#           sentence included: a no-op Save once dropped half-readable-band's unreadable fail band,
#           and the next run judged it PASS (C-1, the client half).
#      And a control: the untouched page's verdicts against the EDITED run must disagree, so a
#      comparison that had stopped comparing anything cannot pass.
#
#      failonerror=false for the reason E26 sets it: typo-reducer is an INVALID_RULE on purpose, a
#      config error that exits 1 by default. E26's rule applies too -- no report title, which is
#      hashed into runId and would withhold the payload.
# -----------------------------------------------------------------------------
echo "[C38] A saved document gates the next run as the page showed..."
C38_DIR="$RESULTS/C38"
C38_INPUTS="$TEST_PLANS/server-metrics"
C38_SCRIPT="$TEST_PLANS/scripts/save-from-page.js"
rm -rf "$C38_DIR"; mkdir -p "$C38_DIR"
C38_NOTES=""

c38_generate() {   # <subdir> <annotations>
    local sub="$1" ann="$2"
    WIR_RUNTIME_LOG="$C38_DIR/$sub-runtime.log" \
    run_jmeter -g "$TEST_PLANS/goldens/plain.jtl" -o "$C38_DIR/$sub" \
        -Jwebinsight.report.annotations="$ann" \
        -Jwebinsight.report.servermetrics="$C38_INPUTS/server-metrics.json" \
        -Jwebinsight.report.failonerror=false > "$C38_DIR/$sub.log" 2>&1
    echo $? > "$C38_DIR/$sub.exit"
}

if ! command -v node >/dev/null 2>&1; then
    C38_NOTES="node is not on PATH -- the page's half of this stage runs report.js in node"
else
    c38_generate page "$C38_INPUTS/e26-server.json"
    if ! file_exists "$C38_DIR/page/web-insight-report.html"; then
        C38_NOTES="$(append_note "$C38_NOTES" "no page was generated from E26's inputs -- see $C38_DIR/page.log")"
    elif ! node "$C38_SCRIPT" save "$C38_DIR/page/web-insight-report.html" "$C38_DIR/saved" \
            > "$C38_DIR/save.log" 2>&1; then
        C38_NOTES="$(append_note "$C38_NOTES" "the page could not make its edits or save -- $(grep -m1 '\[C38\]' "$C38_DIR/save.log")")"
    else
        for c38_case in noop edited seeded; do
            c38_generate "$c38_case" "$C38_DIR/saved/$c38_case/report-annotations.json"
            c38_json="$C38_DIR/$c38_case/web-insight-report.json"
            if ! file_exists "$c38_json"; then
                C38_NOTES="$(append_note "$C38_NOTES" "$c38_case: jmeter -g wrote no CI/CD JSON -- see $C38_DIR/$c38_case.log")"
            elif ! node "$C38_SCRIPT" compare "$C38_DIR/saved/$c38_case/page-verdicts.json" "$c38_json" \
                    > "$C38_DIR/$c38_case-compare.log" 2>&1; then
                C38_NOTES="$(append_note "$C38_NOTES" "$c38_case: $(grep -c '^  ' "$C38_DIR/$c38_case-compare.log") field(s) differ, first: $(grep -m1 '^  ' "$C38_DIR/$c38_case-compare.log" | sed 's/^ *//') -- see $C38_DIR/$c38_case-compare.log")"
            fi
        done
        # The control: an untouched page's verdicts cannot match the edited run's.
        if file_exists "$C38_DIR/edited/web-insight-report.json" \
                && node "$C38_SCRIPT" compare "$C38_DIR/saved/noop/page-verdicts.json" \
                    "$C38_DIR/edited/web-insight-report.json" > "$C38_DIR/control-compare.log" 2>&1; then
            C38_NOTES="$(append_note "$C38_NOTES" "the untouched page matched the edited run -- the comparison compares nothing")"
        fi
    fi

    # G3's input, saved untouched: its six refused rules must still be refused by the next run, each
    # in the same words, and healthy-control must still PASS.
    c38_generate broken-page "$TEST_PLANS/goldens/gate-broken.json"
    if ! file_exists "$C38_DIR/broken-page/web-insight-report.html"; then
        C38_NOTES="$(append_note "$C38_NOTES" "no page was generated from G3's input -- see $C38_DIR/broken-page.log")"
    elif ! node "$C38_SCRIPT" noop "$C38_DIR/broken-page/web-insight-report.html" "$C38_DIR/saved/broken" \
            > "$C38_DIR/save-broken.log" 2>&1; then
        C38_NOTES="$(append_note "$C38_NOTES" "G3's page could not save -- $(grep -m1 '\[C38\]' "$C38_DIR/save-broken.log")")"
    else
        c38_generate broken "$C38_DIR/saved/broken/report-annotations.json"
        c38_json="$C38_DIR/broken/web-insight-report.json"
        if ! file_exists "$c38_json"; then
            C38_NOTES="$(append_note "$C38_NOTES" "broken: jmeter -g wrote no CI/CD JSON -- see $C38_DIR/broken.log")"
        elif ! node "$C38_SCRIPT" compare "$C38_DIR/saved/broken/page-verdicts.json" "$c38_json" \
                > "$C38_DIR/broken-compare.log" 2>&1; then
            C38_NOTES="$(append_note "$C38_NOTES" "broken: $(grep -c '^  ' "$C38_DIR/broken-compare.log") field(s) differ, first: $(grep -m1 '^  ' "$C38_DIR/broken-compare.log" | sed 's/^ *//') -- see $C38_DIR/broken-compare.log")"
        fi
    fi
fi

if [[ -z "$C38_NOTES" ]]; then
    record C38 "A saved document gates the next run as the page showed" PASS \
        "an untouched Download, a five-edit session, a Generate-from-sources seed and an untouched Download of G3's input (six refused rules and a PASS control) each re-judged by jmeter -g to the page's verdicts, field by field"
else
    record C38 "A saved document gates the next run as the page showed" FAIL "$C38_NOTES"
fi

# -----------------------------------------------------------------------------
# C39: a Grafana export collected as it is downloaded -- wir-collect --csv FILE --layout wide.
#
#      Inspect > Data > Download CSV with "Series joined by time" and Formatted data off is wide:
#      a quoted "Time" column of epoch milliseconds, then one column per series, \r\n line ends,
#      an empty cell where a series has no point at another's timestamp. The committed
#      e27-grafana.csv holds E27's own values over plain.jtl's window (22:13:20 to 22:14:08 UTC on
#      2023-11-14, one row a second) in that shape, and this stage feeds a CRLF copy of it, as
#      Grafana writes it:
#        * two columns named by the header convention, component/metric[/instance] -- the two
#          asserts of e27-server.json, one with an instance and one without;
#        * one named by the --columns map, e27-grafana-columns.csv, whose other entry names a
#          column the export does not have (listed, maps nothing);
#        * one column neither names, a raw exporter metric: ignored and listed, never guessed at;
#        * one empty cell in the lag column: no point, so 48 of its 49.
#      Then the payload, field by field, and the console. And the same file at --step 250ms: rows
#      1 s apart counted in 250 ms slots read LOW_COVERAGE for a reason nobody sees, so the
#      collector says so and names the --step that matches.
#
#      The collector exits 0 (D27) whatever it found. Every check here reads what it wrote.
# -----------------------------------------------------------------------------
echo "[C39] A Grafana wide export..."
C39_DIR="$RESULTS/C39"
C39_INPUTS="$TEST_PLANS/server-catalogue"
rm -rf "$C39_DIR"; mkdir -p "$C39_DIR"
C39_NOTES=""
# The committed file is LF, as every text blob is (verify_line_endings); Grafana writes CRLF.
awk '{ printf "%s\r\n", $0 }' "$C39_INPUTS/e27-grafana.csv" > "$C39_DIR/e27-grafana.csv"

c39_collect() {   # <name> <step>
    local name="$1" step="$2"
    "$PROJECT_DIR/scripts/wir-collect.sh" --jtl "$TEST_PLANS/goldens/plain.jtl" \
            --annotations "$C39_INPUTS/e27-server.json" \
            --csv "$C39_DIR/e27-grafana.csv" --layout wide --columns "$C39_INPUTS/e27-grafana-columns.csv" \
            --step "$step" --out "$C39_DIR/$name.json" --no-wait > "$C39_DIR/$name.log" 2>&1
    echo $? > "$C39_DIR/$name.exit"
}

c39_collect matched 1s
assert_exit_code "$(cat "$C39_DIR/matched.exit")" 0 \
    || C39_NOTES="$(append_note "$C39_NOTES" "wir-collect exited $(cat "$C39_DIR/matched.exit") -- see $C39_DIR/matched.log")"
assert_contains "$C39_DIR/matched.log" 'wir-collect: 1 column not mapped to a component and metric: "orders-db:5432 pg_stat_activity_count"' \
    || C39_NOTES="$(append_note "$C39_NOTES" "the unmapped column is not listed on the console -- see $C39_DIR/matched.log")"
assert_contains "$C39_DIR/matched.log" 'wir-collect: e27-grafana-columns.csv names 1 column that e27-grafana.csv does not have, so it maps nothing: "Replication lag"' \
    || C39_NOTES="$(append_note "$C39_NOTES" "the --columns entry for a column the export lacks is not listed")"
assert_contains "$C39_DIR/matched.log" "wir-collect: OK orders-db/connections.used.pct coverage=100%" \
    || C39_NOTES="$(append_note "$C39_NOTES" "the header-convention column was not read: no 'OK orders-db/connections.used.pct coverage=100%'")"
if grep -q "points are" "$C39_DIR/matched.log"; then
    C39_NOTES="$(append_note "$C39_NOTES" "a spacing warning at --step 1s, the export's own spacing")"
fi

if ! command -v node >/dev/null 2>&1; then
    C39_NOTES="$(append_note "$C39_NOTES" "node is not on PATH -- the payload is checked field by field in node")"
elif ! file_exists "$C39_DIR/matched.json"; then
    C39_NOTES="$(append_note "$C39_NOTES" "no payload was written")"
else
    cat > "$C39_DIR/check.js" << 'JSEOF'
// The payload C39 collected, field by field: the layout, three series and their points.
const fs = require('fs');
const p = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
const problems = [];
const src = (p.sources && p.sources[0]) || {};
if (src.layout !== 'wide') problems.push('the source records layout ' + JSON.stringify(src.layout) + ', not "wide"');
if ('timeZone' in src) problems.push('a timeZone was recorded without --time-zone');
const names = p.series.map(s => s.component + '/' + s.metric);
if (p.series.length !== 3) problems.push(p.series.length + ' series, expected 3: ' + names.join(', '));
function expect(component, metric, count, labels, ts, value) {
    const s = p.series.find(x => x.component === component && x.metric === metric);
    const what = component + '/' + metric;
    if (!s) { problems.push(what + ' was not collected (' + names.join(', ') + ')'); return; }
    const inst = s.instances && s.instances[0];
    if (!inst) { problems.push(what + ' has no instance'); return; }
    if (JSON.stringify(inst.labels || {}) !== JSON.stringify(labels)) {
        problems.push(what + ' is labelled ' + JSON.stringify(inst.labels) + ', expected ' + JSON.stringify(labels));
    }
    if (inst.points.length !== count) problems.push(what + ' has ' + inst.points.length + ' points, expected ' + count);
    const pt = inst.points.find(q => q[0] === ts);
    if (value === null ? pt : (!pt || pt[1] !== value)) {
        problems.push(what + ' at ' + ts + ' is ' + JSON.stringify(pt) + ', expected ' + (value === null ? 'no point' : value));
    }
}
// By the header convention, with an instance and without one.
expect('orders-db', 'connections.used.pct', 49, { instance: 'pg-1' }, 1700000000000, 40);
expect('events', 'consumer.lag.max', 48, {}, 1700000048000, 676);
// The empty cell at 22:13:30: no point, never a zero.
expect('events', 'consumer.lag.max', 48, {}, 1700000010000, null);
// By the --columns map: E27's one deadlock, at 22:13:50.
expect('orders-db', 'deadlocks', 49, { instance: 'pg-1' }, 1700000030000, 1);
if (problems.length) { console.log(problems.join('; ')); process.exit(1); }
JSEOF
    if ! node "$C39_DIR/check.js" "$C39_DIR/matched.json" > "$C39_DIR/check.log" 2>&1; then
        C39_NOTES="$(append_note "$C39_NOTES" "the payload: $(head -c 400 "$C39_DIR/check.log")")"
    fi
fi

c39_collect coarse 250ms
assert_exit_code "$(cat "$C39_DIR/coarse.exit")" 0 \
    || C39_NOTES="$(append_note "$C39_NOTES" "wir-collect at --step 250ms exited $(cat "$C39_DIR/coarse.exit") -- see $C39_DIR/coarse.log")"
assert_contains "$C39_DIR/coarse.log" "wir-collect: orders-db/connections.used.pct points are 1s apart but --step is 250ms - coverage counts 250ms slots; pass --step 1s" \
    || C39_NOTES="$(append_note "$C39_NOTES" "no spacing warning for 1 s rows at --step 250ms -- see $C39_DIR/coarse.log")"

if [[ -z "$C39_NOTES" ]]; then
    record C39 "A Grafana wide export" PASS \
        "a CRLF wide export read by its headers and a --columns map, the unmapped column and the map's absent one listed, a gap kept a gap, and a step finer than the rows said"
else
    record C39 "A Grafana wide export" FAIL "$C39_NOTES"
fi

# -----------------------------------------------------------------------------
# C40: an InfluxDB 2 export collected as it comes -- wir-collect --csv FILE --csv-columns SPEC
#      --component ID.
#
#      influx query --raw (or the API's CSV) writes InfluxDB's annotated CSV: long, one row per
#      point, but its columns are _time, _value, _field and the tags, every header and data line
#      starts with an empty annotation column, #group/#datatype/#default lines stand before each
#      header, and a table whose columns differ starts a new block, with a header of its own, after
#      a blank line, and every line ends \r\n. It names no component: InfluxDB knows nothing of
#      them. The committed e27-influx2.csv holds two of E27's orders-db series over plain.jtl's
#      window (22:13:20 to 22:14:08 UTC on 2023-11-14, one row a second) in that shape, and this
#      stage feeds a CRLF copy of it, as InfluxDB writes it:
#        * block 1, connections.used.pct on pg-1, with InfluxDB's _start/_stop range columns;
#        * block 2, deadlocks on pg-1, without them and with an extra region tag, so every column
#          it reads is at another position than block 1's;
#      read with --csv-columns time=_time,value=_value,metric=_field,instance=host and --component
#      orders-db against E27's annotations. Then the payload, field by field: both series and their
#      points, and the source recording the columns and the component it was read with.
#
#      The collector exits 0 (D27) whatever it found. Every check here reads what it wrote.
# -----------------------------------------------------------------------------
echo "[C40] An InfluxDB 2 annotated export..."
C40_DIR="$RESULTS/C40"
C40_INPUTS="$TEST_PLANS/server-catalogue"
rm -rf "$C40_DIR"; mkdir -p "$C40_DIR"
C40_NOTES=""
# The committed file is LF, as every text blob is (verify_line_endings); InfluxDB writes CRLF.
awk '{ printf "%s\r\n", $0 }' "$C40_INPUTS/e27-influx2.csv" > "$C40_DIR/e27-influx2.csv"

"$PROJECT_DIR/scripts/wir-collect.sh" --jtl "$TEST_PLANS/goldens/plain.jtl" \
        --annotations "$C40_INPUTS/e27-server.json" \
        --csv "$C40_DIR/e27-influx2.csv" --csv-columns "time=_time,value=_value,metric=_field,instance=host" \
        --component orders-db --step 1s --out "$C40_DIR/influx.json" --no-wait > "$C40_DIR/influx.log" 2>&1
echo $? > "$C40_DIR/influx.exit"
assert_exit_code "$(cat "$C40_DIR/influx.exit")" 0 \
    || C40_NOTES="$(append_note "$C40_NOTES" "wir-collect exited $(cat "$C40_DIR/influx.exit") -- see $C40_DIR/influx.log")"
assert_contains "$C40_DIR/influx.log" "wir-collect: OK orders-db/connections.used.pct coverage=100%" \
    || C40_NOTES="$(append_note "$C40_NOTES" "block 1 was not read: no 'OK orders-db/connections.used.pct coverage=100%' -- see $C40_DIR/influx.log")"
assert_contains "$C40_DIR/influx.log" "wir-collect: OK orders-db/deadlocks coverage=100%" \
    || C40_NOTES="$(append_note "$C40_NOTES" "block 2 was not read: no 'OK orders-db/deadlocks coverage=100%' -- see $C40_DIR/influx.log")"

if ! command -v node >/dev/null 2>&1; then
    C40_NOTES="$(append_note "$C40_NOTES" "node is not on PATH -- the payload is checked field by field in node")"
elif ! file_exists "$C40_DIR/influx.json"; then
    C40_NOTES="$(append_note "$C40_NOTES" "no payload was written")"
else
    cat > "$C40_DIR/check.js" << 'JSEOF'
// The payload C40 collected, field by field: how the source was read, and both blocks' series.
const fs = require('fs');
const p = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
const problems = [];
const src = (p.sources && p.sources[0]) || {};
if (src.columns !== 'time=_time,metric=_field,instance=host,value=_value') {
    problems.push('the source records columns ' + JSON.stringify(src.columns) + ', not the normalised spec');
}
if (src.component !== 'orders-db') problems.push('the source records component ' + JSON.stringify(src.component));
if ('layout' in src || 'timeZone' in src) problems.push('a layout or a timeZone was recorded without the flag');
if (src.status !== 'OK') problems.push('the source is ' + src.status + ': ' + src.error);
const names = p.series.map(s => s.component + '/' + s.metric + ' ' + s.status);
function expect(metric, count, first, at, value, last) {
    const s = p.series.find(x => x.component === 'orders-db' && x.metric === metric);
    const what = 'orders-db/' + metric;
    if (!s) { problems.push(what + ' was not collected (' + names.join(', ') + ')'); return; }
    if ((s.instances || []).length !== 1) { problems.push(what + ' has ' + (s.instances || []).length + ' instances, expected 1'); return; }
    const inst = s.instances[0];
    if (JSON.stringify(inst.labels || {}) !== JSON.stringify({ instance: 'pg-1' })) {
        problems.push(what + ' is labelled ' + JSON.stringify(inst.labels) + ', expected the host tag pg-1');
    }
    if (inst.points.length !== count) problems.push(what + ' has ' + inst.points.length + ' points, expected ' + count);
    const check = (ts, v) => {
        const pt = inst.points.find(q => q[0] === ts);
        if (!pt || pt[1] !== v) problems.push(what + ' at ' + ts + ' is ' + JSON.stringify(pt) + ', expected ' + v);
    };
    check(1700000000000, first);
    check(at, value);
    check(1700000048000, last);
}
// Block 1, under the header with _start and _stop.
expect('connections.used.pct', 49, 40, 1700000010000, 60, 51);
// Block 2, under its own header: no _start/_stop, a region tag, every column at another position. E27's one deadlock, at 22:13:50.
expect('deadlocks', 49, 0, 1700000030000, 1, 0);
// E27's other assert names events, which no row is in: recorded, and EMPTY.
const lag = p.series.find(x => x.component === 'events' && x.metric === 'consumer.lag.max');
if (!lag || lag.status !== 'EMPTY') problems.push('events/consumer.lag.max is ' + (lag ? lag.status : 'missing') + ', expected EMPTY');
if (p.series.length !== 3) problems.push(p.series.length + ' series, expected 3: ' + names.join(', '));
if (problems.length) { console.log(problems.join('; ')); process.exit(1); }
JSEOF
    if ! node "$C40_DIR/check.js" "$C40_DIR/influx.json" > "$C40_DIR/check.log" 2>&1; then
        C40_NOTES="$(append_note "$C40_NOTES" "the payload: $(head -c 400 "$C40_DIR/check.log")")"
    fi
fi

if [[ -z "$C40_NOTES" ]]; then
    record C40 "An InfluxDB 2 annotated export" PASS \
        "two blocks with their own headers read through --csv-columns into --component's series, every point where E27 has it, and the source recording the columns and the component"
else
    record C40 "An InfluxDB 2 annotated export" FAIL "$C40_NOTES"
fi

# -----------------------------------------------------------------------------
# C41: a Datadog export read by Datadog's names -- wir-collect --csv FILE --from datadog.
#
#      An export from Datadog names each metric as Datadog does and hands over Datadog's kind of
#      number; the catalogue's names block says, per KPI, which metric that is, its kind and the
#      scale. The committed e27-datadog.csv holds E27's series over plain.jtl's window (22:13:20 to
#      22:14:08 UTC on 2023-11-14, one row a second) under those names, read against E27's
#      annotations:
#        * postgresql.percent_usage_connections, a 0-1 fraction (gauge, x100), for orders-db's
#          asserted connections.used.pct: 0.4 reads 40;
#        * kafka.consumer_lag (gauge) for events' asserted consumer.lag.max;
#        * postgresql.deadlocks, lock/s (rate, x60), for deadlocks, collected beside the asserts
#          (D1): E27's one deadlock is 0.05/s at 22:13:50, so 3 a minute;
#        * up under the catalogue's own name: Datadog has no single metric for it (its entry is
#          null), so the computed series is read as it is, and records no readAs.
#      Then the payload, field by field: the tool on the source, the name, kind and scale on every
#      series read under Datadog's name, and every converted point E27's own value (within 1e-9:
#      0.57 x 100 is 56.99999999999999 in a double).
#
#      The collector exits 0 (D27) whatever it found. Every check here reads what it wrote.
# -----------------------------------------------------------------------------
echo "[C41] A Datadog export read by Datadog's names..."
C41_DIR="$RESULTS/C41"
C41_INPUTS="$TEST_PLANS/server-catalogue"
rm -rf "$C41_DIR"; mkdir -p "$C41_DIR"
C41_NOTES=""

"$PROJECT_DIR/scripts/wir-collect.sh" --jtl "$TEST_PLANS/goldens/plain.jtl" \
        --annotations "$C41_INPUTS/e27-server.json" \
        --csv "$C41_INPUTS/e27-datadog.csv" --from datadog \
        --step 1s --out "$C41_DIR/datadog.json" --no-wait > "$C41_DIR/datadog.log" 2>&1
echo $? > "$C41_DIR/datadog.exit"
assert_exit_code "$(cat "$C41_DIR/datadog.exit")" 0 \
    || C41_NOTES="$(append_note "$C41_NOTES" "wir-collect exited $(cat "$C41_DIR/datadog.exit") -- see $C41_DIR/datadog.log")"
for c41_series in orders-db/connections.used.pct events/consumer.lag.max orders-db/deadlocks orders-db/up; do
    assert_contains "$C41_DIR/datadog.log" "wir-collect: OK $c41_series coverage=100%" \
        || C41_NOTES="$(append_note "$C41_NOTES" "$c41_series was not read: no 'OK $c41_series coverage=100%' -- see $C41_DIR/datadog.log")"
done

if ! command -v node >/dev/null 2>&1; then
    C41_NOTES="$(append_note "$C41_NOTES" "node is not on PATH -- the payload is checked field by field in node")"
elif ! file_exists "$C41_DIR/datadog.json"; then
    C41_NOTES="$(append_note "$C41_NOTES" "no payload was written")"
else
    cat > "$C41_DIR/check.js" << 'JSEOF'
// The payload C41 collected, field by field: the tool, what each series was read as, and every
// converted point against E27's own value for it.
const fs = require('fs');
const p = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
const e27 = fs.readFileSync(process.argv[3], 'utf8').trim().split('\n').slice(1).map((l) => l.split(','));
const problems = [];
const src = (p.sources && p.sources[0]) || {};
if (src.from !== 'datadog') problems.push('the source records from ' + JSON.stringify(src.from) + ', not "datadog"');
if (src.status !== 'OK') problems.push('the source is ' + src.status + ': ' + src.error);
const names = p.series.map((s) => s.component + '/' + s.metric + ' ' + s.status);
if (p.series.length !== 4) problems.push(p.series.length + ' series, expected 4: ' + names.join(', '));
function expect(component, metric, instance, readAs, valueAt) {
    const what = component + '/' + metric;
    const s = p.series.find((x) => x.component === component && x.metric === metric);
    if (!s) { problems.push(what + ' was not collected (' + names.join(', ') + ')'); return; }
    if (JSON.stringify(s.readAs) !== JSON.stringify(readAs)) {
        problems.push(what + ' was read as ' + JSON.stringify(s.readAs) + ', expected ' + JSON.stringify(readAs));
    }
    const inst = (s.instances || [])[0];
    if (!inst || s.instances.length !== 1) { problems.push(what + ' has ' + (s.instances || []).length + ' instances, expected 1'); return; }
    if (JSON.stringify(inst.labels || {}) !== JSON.stringify({ instance: instance })) {
        problems.push(what + ' is labelled ' + JSON.stringify(inst.labels) + ', expected instance ' + instance);
    }
    if (inst.points.length !== 49) problems.push(what + ' has ' + inst.points.length + ' points, expected 49');
    inst.points.forEach(([ts, v]) => {
        const want = valueAt(ts);
        if (want === undefined || Math.abs(v - want) > 1e-9) problems.push(what + ' at ' + ts + ' is ' + v + ', expected ' + want);
    });
}
// E27's own value for a series at a time: what the converted number must come back to.
const e27At = (component, metric, instance) => (ts) => {
    const row = e27.find((r) => r[1] === component && r[2] === metric && r[3] === instance && Date.parse(r[0]) === ts);
    return row ? Number(row[4]) : undefined;
};
// A 0-1 gauge x100: 0.4 at 22:13:20 reads 40 exactly.
expect('orders-db', 'connections.used.pct', 'pg-1',
    { tool: 'datadog', metric: 'postgresql.percent_usage_connections', kind: 'gauge', scale: 100 },
    e27At('orders-db', 'connections.used.pct', 'pg-1'));
const first = p.series.find((x) => x.metric === 'connections.used.pct');
if (first && first.instances && first.instances[0] && first.instances[0].points[0][1] !== 40) {
    problems.push('0.4 x 100 reads ' + first.instances[0].points[0][1] + ', not 40');
}
expect('events', 'consumer.lag.max', 'orders', { tool: 'datadog', metric: 'kafka.consumer_lag', kind: 'gauge', scale: 1 },
    e27At('events', 'consumer.lag.max', 'orders'));
// A rate per second x60: 0.05/s at 22:13:50 is 3 a minute, 0 everywhere else.
expect('orders-db', 'deadlocks', 'pg-1', { tool: 'datadog', metric: 'postgresql.deadlocks', kind: 'rate', scale: 60 },
    (ts) => (ts === 1700000030000 ? 3 : 0));
// Datadog has none for up: the series under the catalogue's own name, as written - its one 0 at 22:13:44 too.
expect('orders-db', 'up', 'pg-1', undefined, e27At('orders-db', 'up', 'pg-1'));
if (problems.length) { console.log(problems.join('; ')); process.exit(1); }
JSEOF
    if ! node "$C41_DIR/check.js" "$C41_DIR/datadog.json" "$C41_INPUTS/e27-metrics.csv" > "$C41_DIR/check.log" 2>&1; then
        C41_NOTES="$(append_note "$C41_NOTES" "the payload: $(head -c 400 "$C41_DIR/check.log")")"
    fi
fi

if [[ -z "$C41_NOTES" ]]; then
    record C41 "A Datadog export read by Datadog's names" PASS \
        "a 0-1 gauge read x100, a per-second rate read x60 and a gauge read as it is, each recorded as read under Datadog's name with its kind and scale and back to E27's own values, and a KPI Datadog has none for read under the catalogue's name"
else
    record C41 "A Datadog export read by Datadog's names" FAIL "$C41_NOTES"
fi

print_summary "CONFIGURATION TEST SUMMARY"

exit $FAIL
