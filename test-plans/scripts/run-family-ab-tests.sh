#!/usr/bin/env bash
# =============================================================================
#  Output Artifact Scenarios — families A and B (A01-A12, B01-B12)
#
#  Family A drives the listener through the
#  JMX/GUI configuration surface; family B drives the same listener through -J
#  properties. Both families are about what a SHARED output directory looks like
#  after more than one run has written into it.
#
#  WHY THIS RUNNER EXISTS SEPARATELY FROM run-title-junit-tests.sh
#  ---------------------------------------------------------------------------
#  Every T-row is a `clean` row, so that suite's run_case removes and recreates
#  the case directory before each run. More than half the rows here are `carry`
#  or `seed:` — the pre-existing state IS the scenario — and cleaning between
#  the two runs of a carry row destroys the collision it exists to observe. The
#  failure mode is a FALSE PASS, not an error, which is why the preconditions
#  are asserted below rather than assumed:
#
#    clean   -> removed, recreated, and checked empty before the run
#    carry   -> cleaned ONCE, before run 1; run 2 deliberately inherits run 1
#    seed:x  -> pre-populated with x, and the seed is verified to be in place
#
#  THE TITLE IS THE PROVENANCE MARKER
#  ---------------------------------------------------------------------------
#  After run 2 overwrites run 1 the directory is byte-identical to one where
#  run 2 SKIPPED, and mtimes are far too coarse to separate them. So every
#  repeated-run row seeds with -Jwebinsight.report.title=SEED-RUN-1 and runs
#  with RUN-2, then reads the .json "testName" (F16 — the title is the one field
#  reaching HTML, JSON and JUnit XML):
#
#    testName == RUN-2      -> run 2 overwrote  (listener, F2)
#    testName == SEED-RUN-1 -> run 2 skipped    (exporter, F8 — families C/D)
#
#  ABSENCE ASSERTIONS ARE NEVER FREE
#  ---------------------------------------------------------------------------
#  "no report was written" and "no WARN was logged" both pass trivially when the
#  run never happened at all. Every such row first proves the run executed —
#  samples in the JTL, or a report under the fallback name — before asserting
#  what is missing.
#
#  STATUSES: PASS, FAIL (behaved worse than the row claims), DIFF (behaved
#  differently, consistently, and the CSV's Expected is what is wrong) and
#  BLOCKED (the precondition cannot be staged on this platform). Only PASS
#  counts as a pass, so any of the other three leaves a non-zero exit status.
#
#  Assumes the plugin is already deployed (run ./scripts/build-and-deploy.sh).
# =============================================================================
set -uo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../../scripts/lib/common.sh"

require_jmeter
require_python || exit 1

# The helpers below print artifact contents back to the shell. On Windows Python's stdout
# defaults to the console codepage (cp1252), so printing a non-ASCII title raises
# UnicodeEncodeError, the helper dies, and the caller silently compares against an empty
# string — reporting a mangled artifact when the one on disk was perfectly correct.
export PYTHONIOENCODING=utf-8

RESULTS="$RESULTS_DIR/family-ab-tests"

SINGLE="$TEST_PLANS/single-sampler-test.jmx"              # no listener fields set
BLANK_JMX="$TEST_PLANS/title-blank-test.jmx"              # title field empty, JUnit off
FN_JMX="$TEST_PLANS/filename-jmx-test.jmx"                # filename = __P(wir.jmx.filename)
GUI_JMX="$TEST_PLANS/gui-filename-test.jmx"               # filename gui-name.html, title GUI-CONFIGURED
JUNIT_JMX="$TEST_PLANS/junit-jmx-enabled-test.jmx"        # JUnit checkbox true in the plan
MULTI_JMX="$TEST_PLANS/multi-listener-test.jmx"           # two listeners, both default names
MULTI_DIFF_JMX="$TEST_PLANS/multi-listener-distinct-test.jmx"  # two listeners, two names
DISABLED_JMX="$TEST_PLANS/listener-disabled-test.jmx"     # listener element enabled="false"

DEFAULT_HTML="web-insight-report.html"
DEFAULT_JSON="web-insight-report.json"

print_banner "Output Artifact Scenarios — families A and B"

mkdir -p "$RESULTS"

# -----------------------------------------------------------------------------
# Helpers
# -----------------------------------------------------------------------------

# clean_dir <dir> — the `clean` precondition. Removed and recreated rather than merely
# mkdir -p'd, and the emptiness is CHECKED: a stale report from an aborted earlier
# invocation is exactly the debris that makes a broken run look green.
clean_dir() {
    local dir="$1"
    rm -rf "$dir"
    mkdir -p "$dir"
    if [[ -n "$(ls -A "$dir" 2>/dev/null)" ]]; then
        echo "  [PRECONDITION] $dir is not empty at start — results would be unsound" >&2
        return 1
    fi
    return 0
}

# run_out <logdir> <tag> <jmx> <outputdir> [extra jmeter args...]
#
# Short and quiet. Console capture goes to <logdir>/<tag>.log, JMeter's own log — the only
# place the plugin's WARN and ERROR lines appear, since JMeter ships no Console appender —
# to <logdir>/<tag>-runtime.log. Each run gets its own JTL: -l APPENDS, and run_jmeter
# deletes the target first, so a per-run name keeps one run's samples out of another's.
# Returns JMeter's exit status.
run_out() {
    local logdir="$1" tag="$2" jmx="$3" outdir="$4"; shift 4
    mkdir -p "$logdir"
    WIR_RUNTIME_LOG="$logdir/$tag-runtime.log" \
    run_jmeter -n -t "$jmx" -l "$logdir/$tag.jtl" \
        -Jthreads=2 -Jduration=4 -Jrampup=1 \
        -Jwebinsight.report.output="$outdir" \
        "$@" > "$logdir/$tag.log" 2>&1
}

# run_into <dir> <tag> <jmx> [extra args...] — the common case, logs beside the report.
run_into() {
    local dir="$1" tag="$2" jmx="$3"; shift 3
    run_out "$dir" "$tag" "$jmx" "$dir" "$@"
}

json_title() { "$PYTHON" -c "import json,sys;print(json.load(open(sys.argv[1],encoding='utf-8'))['testName'])" "$1" 2>/dev/null; }
xml_title()  { "$PYTHON" -c "import xml.etree.ElementTree as E,sys;print(E.parse(sys.argv[1]).getroot().get('name'))" "$1" 2>/dev/null; }
xml_valid()  { "$PYTHON" -c "import xml.etree.ElementTree as E,sys;E.parse(sys.argv[1])" "$1" >/dev/null 2>&1; }

# produced_samples <jtl> — positive proof a run actually executed, for rows whose real
# assertion is an ABSENCE. A JTL with only its header line means JMeter started and ran
# nothing; no JTL at all means it never started.
produced_samples() {
    [[ -f "$1" ]] || return 1
    [[ "$(wc -l < "$1")" -gt 1 ]]
}

# writes_denied <dir> — true when this process genuinely cannot create a file in <dir>.
# Asked rather than assumed: on Windows the DOS read-only attribute is advisory for
# directories (verified — attrib +R leaves File.canWrite() true AND the write succeeds),
# so only a real ACL denial stages B10's precondition.
writes_denied() {
    if ( : > "$1/.wir-probe" ) 2>/dev/null; then
        rm -f "$1/.wir-probe"
        return 1
    fi
    return 0
}

deny_writes() {
    if command -v icacls.exe >/dev/null 2>&1 && command -v cygpath >/dev/null 2>&1; then
        MSYS_NO_PATHCONV=1 icacls.exe "$(cygpath -w "$1")" /deny "$(whoami):(W,AD,WD)" >/dev/null 2>&1
    else
        chmod a-w "$1" 2>/dev/null
    fi
    writes_denied "$1"
}

# Always paired with deny_writes, immediately after the run — a directory left denied
# cannot be cleaned up by the next cycle, and rm -rf would fail on it.
allow_writes() {
    if command -v icacls.exe >/dev/null 2>&1 && command -v cygpath >/dev/null 2>&1; then
        MSYS_NO_PATHCONV=1 icacls.exe "$(cygpath -w "$1")" /remove:d "$(whoami)" >/dev/null 2>&1
    fi
    chmod u+w "$1" 2>/dev/null
    return 0
}

# =============================================================================
#  Family A — the listener configured from the JMX/GUI surface
# =============================================================================

# -----------------------------------------------------------------------------
# A01 (carry): default config twice into one directory. Overwrite is the intended
#              default (F2) and the ONLY signal is a WARN in a log with no console
#              appender — so assert the WARN's presence on run 2 and its absence on
#              run 1, not just the surviving file.
# -----------------------------------------------------------------------------
echo "[A01] Default config, two runs into one directory..."
A01_DIR="$RESULTS/A01"
clean_dir "$A01_DIR"
run_into "$A01_DIR" r1 "$BLANK_JMX" -Jwebinsight.report.title=SEED-RUN-1
A01_SEEDED=no; file_exists "$A01_DIR/$DEFAULT_HTML" && A01_SEEDED=yes
run_into "$A01_DIR" r2 "$BLANK_JMX" -Jwebinsight.report.title=RUN-2

A01_NOTES=""
[[ "$A01_SEEDED" == "yes" ]] \
    || A01_NOTES="$(append_note "$A01_NOTES" "run 1 produced no report, so run 2 had nothing to carry into")"
[[ "$(count_glob "$A01_DIR"/*.html)" -eq 1 ]] \
    || A01_NOTES="$(append_note "$A01_NOTES" "$(count_glob "$A01_DIR"/*.html) html files, expected exactly 1")"
[[ "$(count_glob "$A01_DIR"/*.json)" -eq 1 ]] \
    || A01_NOTES="$(append_note "$A01_NOTES" "$(count_glob "$A01_DIR"/*.json) json files, expected exactly 1")"
A01_TITLE="$(json_title "$A01_DIR/$DEFAULT_JSON")"
[[ "$A01_TITLE" == "RUN-2" ]] \
    || A01_NOTES="$(append_note "$A01_NOTES" "surviving testName is '$A01_TITLE', expected RUN-2")"
assert_log_contains "$A01_DIR/r2-runtime.log" "Overwriting an existing report" \
    || A01_NOTES="$(append_note "$A01_NOTES" "run 2 did not warn that it was overwriting")"
assert_log_lacks "$A01_DIR/r1-runtime.log" "Overwriting an existing report" \
    || A01_NOTES="$(append_note "$A01_NOTES" "run 1 warned about overwriting an empty directory")"
if [[ -z "$A01_NOTES" ]]; then
    record A01 "Repeated default runs overwrite" PASS "one pair survives, testName RUN-2, WARN on run 2 only"
else
    record A01 "Repeated default runs overwrite" FAIL "$A01_NOTES"
fi

# -----------------------------------------------------------------------------
# A02 (carry): the filename comes from the PLAN, not -J. filename-jmx-test.jmx holds a
#              single __P() reference in WebInsightReport.reportFilename, so the
#              PreCompiler makes it a FunctionProperty and getPropertyAsString evaluates
#              it (F15) — the JMX surface, driven from the shell.
# -----------------------------------------------------------------------------
echo "[A02] JMX filename, two runs into one directory..."
A02_DIR="$RESULTS/A02"
clean_dir "$A02_DIR"
run_into "$A02_DIR" r1 "$FN_JMX" -Jwir.jmx.filename=my-report.html -Jwebinsight.report.title=SEED-RUN-1
A02_SEEDED=no; file_exists "$A02_DIR/my-report.html" && A02_SEEDED=yes
run_into "$A02_DIR" r2 "$FN_JMX" -Jwir.jmx.filename=my-report.html -Jwebinsight.report.title=RUN-2

A02_NOTES=""
[[ "$A02_SEEDED" == "yes" ]] \
    || A02_NOTES="$(append_note "$A02_NOTES" "run 1 produced no my-report.html to carry into")"
file_exists "$A02_DIR/my-report.html"  || A02_NOTES="$(append_note "$A02_NOTES" "my-report.html missing")"
file_exists "$A02_DIR/my-report.json"  || A02_NOTES="$(append_note "$A02_NOTES" "my-report.json missing")"
file_absent "$A02_DIR/$DEFAULT_HTML"   || A02_NOTES="$(append_note "$A02_NOTES" "a default-named report was written too")"
[[ "$(count_glob "$A02_DIR"/*.html)" -eq 1 ]] \
    || A02_NOTES="$(append_note "$A02_NOTES" "$(count_glob "$A02_DIR"/*.html) html files, expected 1")"
A02_TITLE="$(json_title "$A02_DIR/my-report.json")"
[[ "$A02_TITLE" == "RUN-2" ]] \
    || A02_NOTES="$(append_note "$A02_NOTES" "surviving testName is '$A02_TITLE', expected RUN-2")"
if [[ -z "$A02_NOTES" ]]; then
    record A02 "JMX filename, run 2 wins" PASS "one my-report pair, testName RUN-2, no default-named files"
else
    record A02 "JMX filename, run 2 wins" FAIL "$A02_NOTES"
fi

# -----------------------------------------------------------------------------
# A03 (carry): ${timestamp} in the JMX filename, with a gap. Two distinct pairs must
#              survive and NEITHER run may warn — the point of the placeholder is that
#              there is nothing to overwrite. The no-WARN half is only meaningful
#              because the two-file half proves both runs actually wrote something.
# -----------------------------------------------------------------------------
echo "[A03] JMX \${timestamp} filename, two runs with a gap..."
A03_DIR="$RESULTS/A03"
clean_dir "$A03_DIR"
run_into "$A03_DIR" r1 "$FN_JMX" -Jwir.jmx.filename='run-${timestamp}.html' -Jwebinsight.report.title=RUN-1
sleep 2
run_into "$A03_DIR" r2 "$FN_JMX" -Jwir.jmx.filename='run-${timestamp}.html' -Jwebinsight.report.title=RUN-2

A03_NOTES=""
A03_HTML="$(count_glob "$A03_DIR"/run-*.html)"
A03_JSON="$(count_glob "$A03_DIR"/run-*.json)"
[[ "$A03_HTML" -eq 2 ]] || A03_NOTES="$(append_note "$A03_NOTES" "$A03_HTML timestamped reports, expected 2")"
[[ "$A03_JSON" -eq 2 ]] || A03_NOTES="$(append_note "$A03_NOTES" "$A03_JSON timestamped .json files, expected 2")"
assert_log_lacks "$A03_DIR/r1-runtime.log" "Overwriting an existing report" \
    || A03_NOTES="$(append_note "$A03_NOTES" "run 1 warned about overwriting")"
assert_log_lacks "$A03_DIR/r2-runtime.log" "Overwriting an existing report" \
    || A03_NOTES="$(append_note "$A03_NOTES" "run 2 warned about overwriting a distinct name")"
if [[ -z "$A03_NOTES" ]]; then
    record A03 "JMX \${timestamp} keeps both runs" PASS "two distinct pairs retained, no overwrite WARN"
else
    record A03 "JMX \${timestamp} keeps both runs" FAIL "$A03_NOTES"
fi

# -----------------------------------------------------------------------------
# A04 (retired 2026-10-10): a same-second ${timestamp} collision. Its premise was two runs
#              COMPLETING inside one clock second, which needs two JMeter JVMs to start,
#              run and shut down inside 1s - unreachable on any machine, so it recorded
#              BLOCKED on every run and kept this runner's exit non-zero for nothing. What
#              it would have checked is covered: a collision on one name overwrites with a
#              WARN whatever produced the name (A01, B01), and ${timestamp} gives distinct
#              names a second apart (A03, C20). The id is not reused.
# -----------------------------------------------------------------------------

# -----------------------------------------------------------------------------
# A05 (clean): a colon in the JMX filename. The allowlist (F4) rejects it, the default
#              name is used, and the INTENT LINE must show the resolved name — that line
#              is the only record of where a rejected name ended up.
# -----------------------------------------------------------------------------
echo "[A05] JMX filename containing a colon..."
A05_DIR="$RESULTS/A05"
clean_dir "$A05_DIR"
run_into "$A05_DIR" r1 "$FN_JMX" -Jwir.jmx.filename='bad:name.html' -Jwebinsight.report.title=A05

A05_NOTES=""
assert_log_contains "$A05_DIR/r1-runtime.log" "Ignoring report filename" \
    || A05_NOTES="$(append_note "$A05_NOTES" "no rejection WARN in the runtime log")"
file_exists "$A05_DIR/$DEFAULT_HTML" \
    || A05_NOTES="$(append_note "$A05_NOTES" "no report under the fallback name")"
assert_log_contains "$A05_DIR/r1-runtime.log" "html=$DEFAULT_HTML" \
    || A05_NOTES="$(append_note "$A05_NOTES" "the intent line does not show the resolved name")"
[[ "$(count_glob "$A05_DIR"/*name*.html)" -eq 0 ]] \
    || A05_NOTES="$(append_note "$A05_NOTES" "something resembling the rejected name was written")"
if [[ -z "$A05_NOTES" ]]; then
    record A05 "Colon in the JMX filename" PASS "rejected with a WARN, default name used, resolved name in the intent line"
else
    record A05 "Colon in the JMX filename" FAIL "$A05_NOTES"
fi

# -----------------------------------------------------------------------------
# A06 (clean): a path separator in the JMX filename. Same rejection, plus the half that
#              only a filesystem check can prove — no subdirectory was created on the way
#              to finding out.
# -----------------------------------------------------------------------------
echo "[A06] JMX filename containing a path separator..."
A06_DIR="$RESULTS/A06"
clean_dir "$A06_DIR"
run_into "$A06_DIR" r1 "$FN_JMX" -Jwir.jmx.filename='sub/dir/r.html' -Jwebinsight.report.title=A06

A06_NOTES=""
assert_log_contains "$A06_DIR/r1-runtime.log" "Ignoring report filename" \
    || A06_NOTES="$(append_note "$A06_NOTES" "no rejection WARN in the runtime log")"
file_exists "$A06_DIR/$DEFAULT_HTML" \
    || A06_NOTES="$(append_note "$A06_NOTES" "no report under the fallback name")"
[[ ! -d "$A06_DIR/sub" ]] \
    || A06_NOTES="$(append_note "$A06_NOTES" "a sub/ directory was created")"
file_absent "$A06_DIR/r.html" \
    || A06_NOTES="$(append_note "$A06_NOTES" "the basename was written instead")"
if [[ -z "$A06_NOTES" ]]; then
    record A06 "Path separator in the JMX filename" PASS "rejected with a WARN, default name used, no subdirectory created"
else
    record A06 "Path separator in the JMX filename" FAIL "$A06_NOTES"
fi

# -----------------------------------------------------------------------------
# A07 (clean): two listeners in one plan, identical config. WRITTEN_THIS_JVM is static
#              (F6), so this is the one case the plugin can detect by itself — the same
#              path is rendered and written twice and the last writer wins.
# -----------------------------------------------------------------------------
echo "[A07] Two listeners in one plan, identical config..."
A07_DIR="$RESULTS/A07"
clean_dir "$A07_DIR"
run_into "$A07_DIR" r1 "$MULTI_JMX" -Jwebinsight.report.title=A07

A07_NOTES=""
file_exists "$A07_DIR/$DEFAULT_HTML" \
    || A07_NOTES="$(append_note "$A07_NOTES" "no report at all")"
assert_log_contains "$A07_DIR/r1-runtime.log" "has already been written in this JVM" \
    || A07_NOTES="$(append_note "$A07_NOTES" "the second write to the same path was not reported")"
[[ "$(count_glob "$A07_DIR"/*.html)" -eq 1 ]] \
    || A07_NOTES="$(append_note "$A07_NOTES" "$(count_glob "$A07_DIR"/*.html) html files, expected 1")"
if [[ -z "$A07_NOTES" ]]; then
    record A07 "Two listeners, same path" PASS "one file, written twice, WARN names the path"
else
    record A07 "Two listeners, same path" FAIL "$A07_NOTES"
fi

# -----------------------------------------------------------------------------
# A08 (clean): two listeners with DIFFERENT filenames. Independent paths, so both pairs
#              must be complete and nothing may warn. Each listener carries its own title
#              in the plan, which is what proves the two reports are not one report
#              written twice: LISTENER-A and LISTENER-B must both survive.
#
#              No -Jwebinsight.report.filename here — a CLI property wins over both
#              fields (F13) and would collapse this back into A07.
# -----------------------------------------------------------------------------
echo "[A08] Two listeners in one plan, different filenames..."
A08_DIR="$RESULTS/A08"
clean_dir "$A08_DIR"
run_into "$A08_DIR" r1 "$MULTI_DIFF_JMX"

A08_NOTES=""
for f in listener-a.html listener-a.json listener-b.html listener-b.json; do
    file_exists "$A08_DIR/$f" || A08_NOTES="$(append_note "$A08_NOTES" "$f missing")"
done
A08_TA="$(json_title "$A08_DIR/listener-a.json")"
A08_TB="$(json_title "$A08_DIR/listener-b.json")"
[[ "$A08_TA" == "LISTENER-A" ]] || A08_NOTES="$(append_note "$A08_NOTES" "listener-a.json testName is '$A08_TA'")"
[[ "$A08_TB" == "LISTENER-B" ]] || A08_NOTES="$(append_note "$A08_NOTES" "listener-b.json testName is '$A08_TB'")"
assert_log_lacks "$A08_DIR/r1-runtime.log" "has already been written in this JVM" \
    || A08_NOTES="$(append_note "$A08_NOTES" "distinct paths still produced a duplicate-write WARN")"
if [[ -z "$A08_NOTES" ]]; then
    record A08 "Two listeners, two filenames" PASS "two complete pairs, each with its own title, no warning"
else
    record A08 "Two listeners, two filenames" FAIL "$A08_NOTES"
fi

# -----------------------------------------------------------------------------
# A09 (carry): change the filename between runs. Run 1 must be UNTOUCHED — verified by
#              its title marker, not its mtime, because an overwrite with identical
#              content and a fresh mtime is indistinguishable from a skip.
# -----------------------------------------------------------------------------
echo "[A09] Change the JMX filename between two runs into one directory..."
A09_DIR="$RESULTS/A09"
clean_dir "$A09_DIR"
run_into "$A09_DIR" r1 "$FN_JMX" -Jwir.jmx.filename=a09-first.html -Jwebinsight.report.title=SEED-RUN-1
A09_SEEDED=no; file_exists "$A09_DIR/a09-first.html" && A09_SEEDED=yes
run_into "$A09_DIR" r2 "$FN_JMX" -Jwir.jmx.filename=a09-second.html -Jwebinsight.report.title=RUN-2

A09_NOTES=""
[[ "$A09_SEEDED" == "yes" ]] \
    || A09_NOTES="$(append_note "$A09_NOTES" "run 1 produced nothing to carry into")"
file_exists "$A09_DIR/a09-first.html"  || A09_NOTES="$(append_note "$A09_NOTES" "a09-first.html was removed")"
file_exists "$A09_DIR/a09-second.html" || A09_NOTES="$(append_note "$A09_NOTES" "a09-second.html missing")"
A09_T1="$(json_title "$A09_DIR/a09-first.json")"
A09_T2="$(json_title "$A09_DIR/a09-second.json")"
[[ "$A09_T1" == "SEED-RUN-1" ]] \
    || A09_NOTES="$(append_note "$A09_NOTES" "run 1's testName is now '$A09_T1' — it was not left alone")"
[[ "$A09_T2" == "RUN-2" ]] \
    || A09_NOTES="$(append_note "$A09_NOTES" "run 2's testName is '$A09_T2'")"
[[ "$(count_glob "$A09_DIR"/*.html)" -eq 2 ]] \
    || A09_NOTES="$(append_note "$A09_NOTES" "$(count_glob "$A09_DIR"/*.html) html files, expected 2")"
if [[ -z "$A09_NOTES" ]]; then
    record A09 "Renamed second run keeps both" PASS "two pairs retained, run 1 still carries SEED-RUN-1"
else
    record A09 "Renamed second run keeps both" FAIL "$A09_NOTES"
fi

# -----------------------------------------------------------------------------
# A10 (clean): no output directory configured at all. The report lands in the working
#              directory JMeter was launched from — a subshell cd, not a -J, because the
#              working directory genuinely is the variable under test.
# -----------------------------------------------------------------------------
echo "[A10] Output directory left unset..."
A10_DIR="$RESULTS/A10"
clean_dir "$A10_DIR"
(
    cd "$A10_DIR" || exit 1
    WIR_RUNTIME_LOG=r1-runtime.log \
    run_jmeter -n -t "$BLANK_JMX" -l results.jtl \
        -Jthreads=2 -Jduration=4 -Jrampup=1 \
        -Jwebinsight.report.title=A10 > r1.log 2>&1
)

A10_NOTES=""
file_exists "$A10_DIR/$DEFAULT_HTML" \
    || A10_NOTES="$(append_note "$A10_NOTES" "no report in the working directory")"
# The intent line prints the directory ABSOLUTE, so the case name has to appear in it.
# Matched on the last segment rather than the whole path: the log is written by the JVM in
# native form (D:\...) while the shell holds a POSIX one.
assert_log_contains "$A10_DIR/r1-runtime.log" "Web Insight Report: outputDir=" \
    || A10_NOTES="$(append_note "$A10_NOTES" "no intent line logged")"
assert_log_contains "$A10_DIR/r1-runtime.log" "A10 html=$DEFAULT_HTML" \
    || A10_NOTES="$(append_note "$A10_NOTES" "the intent line does not name the working directory as the output dir")"
if [[ -z "$A10_NOTES" ]]; then
    record A10 "Unset output directory" PASS "report written to the working directory and announced in the intent line"
else
    record A10 "Unset output directory" FAIL "$A10_NOTES"
fi

# -----------------------------------------------------------------------------
# A11 (clean, dir absent): the output directory does not exist yet.
# -----------------------------------------------------------------------------
echo "[A11] Output directory does not exist yet..."
A11_BASE="$RESULTS/A11"
clean_dir "$A11_BASE"
A11_OUT="$A11_BASE/not-created-yet"
A11_NOTES=""
[[ ! -e "$A11_OUT" ]] || A11_NOTES="$(append_note "$A11_NOTES" "the precondition failed: $A11_OUT already exists")"
run_out "$A11_BASE" r1 "$BLANK_JMX" "$A11_OUT" -Jwebinsight.report.title=A11

file_exists "$A11_OUT/$DEFAULT_HTML" \
    || A11_NOTES="$(append_note "$A11_NOTES" "directory was not created, or no report inside it")"
file_exists "$A11_OUT/$DEFAULT_JSON" \
    || A11_NOTES="$(append_note "$A11_NOTES" "no .json sibling")"
if [[ -z "$A11_NOTES" ]]; then
    record A11 "Missing output directory is created" PASS "directory auto-created, report written"
else
    record A11 "Missing output directory is created" FAIL "$A11_NOTES"
fi

# -----------------------------------------------------------------------------
# A12 (carry): JUnit XML on, from the PLAN's checkbox, twice into one directory. Three
#              artifacts per run and all three must be replaced — the .xml is the one
#              most likely to be left behind, because only the HTML write announces an
#              overwrite. Its <testsuites name> carries the title (F16), so it can be
#              asked which run it came from.
# -----------------------------------------------------------------------------
echo "[A12] JUnit enabled in the plan, two runs into one directory..."
A12_DIR="$RESULTS/A12"
clean_dir "$A12_DIR"
run_into "$A12_DIR" r1 "$JUNIT_JMX" -Jwebinsight.report.title=SEED-RUN-1
A12_SEEDED=no; file_exists "$A12_DIR/web-insight-report.xml" && A12_SEEDED=yes
run_into "$A12_DIR" r2 "$JUNIT_JMX" -Jwebinsight.report.title=RUN-2

A12_NOTES=""
[[ "$A12_SEEDED" == "yes" ]] \
    || A12_NOTES="$(append_note "$A12_NOTES" "run 1 wrote no .xml, so there was no triple to overwrite")"
for ext in html json xml; do
    [[ "$(count_glob "$A12_DIR"/*."$ext")" -eq 1 ]] \
        || A12_NOTES="$(append_note "$A12_NOTES" "$(count_glob "$A12_DIR"/*."$ext") .$ext files, expected 1")"
done
A12_JT="$(json_title "$A12_DIR/$DEFAULT_JSON")"
A12_XT="$(xml_title "$A12_DIR/web-insight-report.xml")"
[[ "$A12_JT" == "RUN-2" ]] || A12_NOTES="$(append_note "$A12_NOTES" "surviving .json testName is '$A12_JT'")"
[[ "$A12_XT" == "RUN-2" ]] || A12_NOTES="$(append_note "$A12_NOTES" "surviving .xml testsuites name is '$A12_XT' — the XML was not replaced")"
if [[ -z "$A12_NOTES" ]]; then
    record A12 "JUnit triple overwritten on rerun" PASS "one html/json/xml, all three from RUN-2"
else
    record A12 "JUnit triple overwritten on rerun" FAIL "$A12_NOTES"
fi

# =============================================================================
#  Family B — the same listener configured from -J properties
# =============================================================================

# -----------------------------------------------------------------------------
# B01 (carry): the -J twin of A01, and the row the whole marker scheme exists for.
#              Contrast C06, where the exporter SKIPS and the surviving testName stays
#              SEED-RUN-1 — one string comparison makes the two paths' opposite
#              behaviour directly visible.
# -----------------------------------------------------------------------------
echo "[B01] Default config via -J, two runs into one directory..."
B01_DIR="$RESULTS/B01"
clean_dir "$B01_DIR"
run_into "$B01_DIR" r1 "$SINGLE" -Jwebinsight.report.title=SEED-RUN-1
B01_SEEDED="$(json_title "$B01_DIR/$DEFAULT_JSON")"
run_into "$B01_DIR" r2 "$SINGLE" -Jwebinsight.report.title=RUN-2

B01_NOTES=""
[[ "$B01_SEEDED" == "SEED-RUN-1" ]] \
    || B01_NOTES="$(append_note "$B01_NOTES" "the seed did not land: testName after run 1 was '$B01_SEEDED'")"
[[ "$(count_glob "$B01_DIR"/*.html)" -eq 1 ]] \
    || B01_NOTES="$(append_note "$B01_NOTES" "$(count_glob "$B01_DIR"/*.html) html files, expected 1")"
B01_TITLE="$(json_title "$B01_DIR/$DEFAULT_JSON")"
[[ "$B01_TITLE" == "RUN-2" ]] \
    || B01_NOTES="$(append_note "$B01_NOTES" "surviving testName is '$B01_TITLE' — the listener did not overwrite")"
assert_log_contains "$B01_DIR/r2-runtime.log" "Overwriting an existing report" \
    || B01_NOTES="$(append_note "$B01_NOTES" "no overwrite WARN on run 2")"
if [[ -z "$B01_NOTES" ]]; then
    record B01 "Listener overwrites (marker check)" PASS "SEED-RUN-1 replaced by RUN-2, with a WARN"
else
    record B01 "Listener overwrites (marker check)" FAIL "$B01_NOTES"
fi

# -----------------------------------------------------------------------------
# B02 (carry): fixed -J filename, twice.
# -----------------------------------------------------------------------------
echo "[B02] Fixed -J filename, two runs into one directory..."
B02_DIR="$RESULTS/B02"
clean_dir "$B02_DIR"
run_into "$B02_DIR" r1 "$SINGLE" -Jwebinsight.report.filename=b02-report.html -Jwebinsight.report.title=SEED-RUN-1
B02_SEEDED=no; file_exists "$B02_DIR/b02-report.html" && B02_SEEDED=yes
run_into "$B02_DIR" r2 "$SINGLE" -Jwebinsight.report.filename=b02-report.html -Jwebinsight.report.title=RUN-2

B02_NOTES=""
[[ "$B02_SEEDED" == "yes" ]] || B02_NOTES="$(append_note "$B02_NOTES" "run 1 produced nothing to carry into")"
[[ "$(count_glob "$B02_DIR"/*.html)" -eq 1 ]] \
    || B02_NOTES="$(append_note "$B02_NOTES" "$(count_glob "$B02_DIR"/*.html) html files, expected 1")"
B02_TITLE="$(json_title "$B02_DIR/b02-report.json")"
[[ "$B02_TITLE" == "RUN-2" ]] || B02_NOTES="$(append_note "$B02_NOTES" "surviving testName is '$B02_TITLE'")"
if [[ -z "$B02_NOTES" ]]; then
    record B02 "Fixed -J filename, run 2 wins" PASS "one pair, testName RUN-2"
else
    record B02 "Fixed -J filename, run 2 wins" FAIL "$B02_NOTES"
fi

# -----------------------------------------------------------------------------
# B03 (carry): -J ${timestamp}, with a gap. Two pairs retained.
# -----------------------------------------------------------------------------
echo "[B03] -J \${timestamp} filename, two runs with a gap..."
B03_DIR="$RESULTS/B03"
clean_dir "$B03_DIR"
run_into "$B03_DIR" r1 "$SINGLE" -Jwebinsight.report.filename='b03-${timestamp}.html' -Jwebinsight.report.title=RUN-1
sleep 2
run_into "$B03_DIR" r2 "$SINGLE" -Jwebinsight.report.filename='b03-${timestamp}.html' -Jwebinsight.report.title=RUN-2

B03_NOTES=""
B03_HTML="$(count_glob "$B03_DIR"/b03-*.html)"
[[ "$B03_HTML" -eq 2 ]] || B03_NOTES="$(append_note "$B03_NOTES" "$B03_HTML timestamped reports, expected 2")"
[[ "$(count_glob "$B03_DIR"/b03-*.json)" -eq 2 ]] \
    || B03_NOTES="$(append_note "$B03_NOTES" "$(count_glob "$B03_DIR"/b03-*.json) timestamped .json files, expected 2")"
if [[ -z "$B03_NOTES" ]]; then
    record B03 "-J \${timestamp} keeps both runs" PASS "two distinct pairs retained"
else
    record B03 "-J \${timestamp} keeps both runs" FAIL "$B03_NOTES"
fi

# -----------------------------------------------------------------------------
# B04 (carry): -J ${timestamp} with no gap. The row accepts EITHER outcome — distinct
#              names when the seconds differ, an overwrite when they do not — so it
#              records which one this machine produced instead of demanding one.
# -----------------------------------------------------------------------------
echo "[B04] -J \${timestamp} filename, two runs with no gap..."
B04_DIR="$RESULTS/B04"
clean_dir "$B04_DIR"
run_into "$B04_DIR" r1 "$SINGLE" -Jwebinsight.report.filename='b04-${timestamp}.html' -Jwebinsight.report.title=RUN-1
run_into "$B04_DIR" r2 "$SINGLE" -Jwebinsight.report.filename='b04-${timestamp}.html' -Jwebinsight.report.title=RUN-2
B04_HTML="$(count_glob "$B04_DIR"/b04-*.html)"
if [[ "$B04_HTML" -eq 2 ]]; then
    record B04 "-J \${timestamp}, no gap" PASS "the seconds differed, so both runs kept their own name"
elif [[ "$B04_HTML" -eq 1 ]] && assert_log_contains "$B04_DIR/r2-runtime.log" "Overwriting an existing report"; then
    record B04 "-J \${timestamp}, no gap" PASS "the stamps collided and run 2 overwrote, with a WARN"
else
    record B04 "-J \${timestamp}, no gap" FAIL "$B04_HTML report(s), and no overwrite WARN to explain it"
fi

# -----------------------------------------------------------------------------
# B05 (clean): -J filename against a JMX filename. -J wins (F13), and the JMX name must
#              not also appear — the failure worth catching is TWO reports, not the
#              wrong one.
# -----------------------------------------------------------------------------
echo "[B05] -J filename beats the JMX filename..."
B05_DIR="$RESULTS/B05"
clean_dir "$B05_DIR"
run_into "$B05_DIR" r1 "$GUI_JMX" -Jwebinsight.report.filename=b05-cli.html

B05_NOTES=""
file_exists "$B05_DIR/b05-cli.html"  || B05_NOTES="$(append_note "$B05_NOTES" "b05-cli.html missing — -J did not win")"
file_absent "$B05_DIR/gui-name.html" || B05_NOTES="$(append_note "$B05_NOTES" "the JMX name gui-name.html was written as well")"
[[ "$(count_glob "$B05_DIR"/*.html)" -eq 1 ]] \
    || B05_NOTES="$(append_note "$B05_NOTES" "$(count_glob "$B05_DIR"/*.html) html files, expected 1")"
if [[ -z "$B05_NOTES" ]]; then
    record B05 "-J filename beats JMX" PASS "one pair, under the -J name"
else
    record B05 "-J filename beats JMX" FAIL "$B05_NOTES"
fi

# -----------------------------------------------------------------------------
# B06 (carry): -Jjunit=true twice into one directory — the -J twin of A12.
# -----------------------------------------------------------------------------
echo "[B06] -J junit=true, two runs into one directory..."
B06_DIR="$RESULTS/B06"
clean_dir "$B06_DIR"
run_into "$B06_DIR" r1 "$SINGLE" -Jwebinsight.report.junit=true -Jwebinsight.report.title=SEED-RUN-1
B06_SEEDED="$(xml_title "$B06_DIR/web-insight-report.xml")"
run_into "$B06_DIR" r2 "$SINGLE" -Jwebinsight.report.junit=true -Jwebinsight.report.title=RUN-2

B06_NOTES=""
[[ "$B06_SEEDED" == "SEED-RUN-1" ]] \
    || B06_NOTES="$(append_note "$B06_NOTES" "run 1's .xml did not carry the seed marker (got '$B06_SEEDED')")"
for ext in html json xml; do
    [[ "$(count_glob "$B06_DIR"/*."$ext")" -eq 1 ]] \
        || B06_NOTES="$(append_note "$B06_NOTES" "$(count_glob "$B06_DIR"/*."$ext") .$ext files, expected 1")"
done
B06_JT="$(json_title "$B06_DIR/$DEFAULT_JSON")"
B06_XT="$(xml_title "$B06_DIR/web-insight-report.xml")"
[[ "$B06_JT" == "RUN-2" ]] || B06_NOTES="$(append_note "$B06_NOTES" "surviving .json testName is '$B06_JT'")"
[[ "$B06_XT" == "RUN-2" ]] || B06_NOTES="$(append_note "$B06_NOTES" "surviving .xml testsuites name is '$B06_XT'")"
if [[ -z "$B06_NOTES" ]]; then
    record B06 "-J JUnit triple overwritten" PASS "one html/json/xml, all three from RUN-2"
else
    record B06 "-J JUnit triple overwritten" FAIL "$B06_NOTES"
fi

# -----------------------------------------------------------------------------
# B07 (carry): two runs into one directory under DIFFERENT -J filenames, JUnit on. This
#              is the supported shared-folder pattern (F1, F5): two complete triples,
#              nothing shared, each carrying its own title.
# -----------------------------------------------------------------------------
echo "[B07] Two -J filenames into one directory, JUnit on..."
B07_DIR="$RESULTS/B07"
clean_dir "$B07_DIR"
run_into "$B07_DIR" r1 "$SINGLE" -Jwebinsight.report.junit=true \
    -Jwebinsight.report.filename=b07-one.html -Jwebinsight.report.title=RUN-1
B07_SEEDED=no; file_exists "$B07_DIR/b07-one.html" && B07_SEEDED=yes
run_into "$B07_DIR" r2 "$SINGLE" -Jwebinsight.report.junit=true \
    -Jwebinsight.report.filename=b07-two.html -Jwebinsight.report.title=RUN-2

B07_NOTES=""
[[ "$B07_SEEDED" == "yes" ]] || B07_NOTES="$(append_note "$B07_NOTES" "run 1 produced nothing to carry into")"
for f in b07-one.html b07-one.json b07-one.xml b07-two.html b07-two.json b07-two.xml; do
    file_exists "$B07_DIR/$f" || B07_NOTES="$(append_note "$B07_NOTES" "$f missing")"
done
B07_T1="$(json_title "$B07_DIR/b07-one.json")"
B07_T2="$(json_title "$B07_DIR/b07-two.json")"
[[ "$B07_T1" == "RUN-1" ]] || B07_NOTES="$(append_note "$B07_NOTES" "b07-one.json testName is '$B07_T1'")"
[[ "$B07_T2" == "RUN-2" ]] || B07_NOTES="$(append_note "$B07_NOTES" "b07-two.json testName is '$B07_T2'")"
xml_valid "$B07_DIR/b07-one.xml" || B07_NOTES="$(append_note "$B07_NOTES" "b07-one.xml is not well-formed")"
xml_valid "$B07_DIR/b07-two.xml" || B07_NOTES="$(append_note "$B07_NOTES" "b07-two.xml is not well-formed")"
if [[ -z "$B07_NOTES" ]]; then
    record B07 "Two triples in one directory" PASS "both complete and independent, each with its own title"
else
    record B07 "Two triples in one directory" FAIL "$B07_NOTES"
fi

# -----------------------------------------------------------------------------
# B08 (carry): a different JTL, the same report name. The JTL is not what decides the
#              report path, so run 2 overwrites and run 1 leaves no trace but the WARN.
# -----------------------------------------------------------------------------
echo "[B08] Different JTL, same report name..."
B08_DIR="$RESULTS/B08"
clean_dir "$B08_DIR"
run_into "$B08_DIR" first "$SINGLE" -Jwebinsight.report.filename=b08-report.html -Jwebinsight.report.title=SEED-RUN-1
B08_SEEDED="$(json_title "$B08_DIR/b08-report.json")"
run_into "$B08_DIR" second "$SINGLE" -Jwebinsight.report.filename=b08-report.html -Jwebinsight.report.title=RUN-2

B08_NOTES=""
[[ "$B08_SEEDED" == "SEED-RUN-1" ]] \
    || B08_NOTES="$(append_note "$B08_NOTES" "the seed did not land (testName was '$B08_SEEDED')")"
[[ -f "$B08_DIR/first.jtl" && -f "$B08_DIR/second.jtl" ]] \
    || B08_NOTES="$(append_note "$B08_NOTES" "the two runs did not use two separate JTLs")"
[[ "$(count_glob "$B08_DIR"/*.html)" -eq 1 ]] \
    || B08_NOTES="$(append_note "$B08_NOTES" "$(count_glob "$B08_DIR"/*.html) html files, expected 1")"
B08_TITLE="$(json_title "$B08_DIR/b08-report.json")"
[[ "$B08_TITLE" == "RUN-2" ]] || B08_NOTES="$(append_note "$B08_NOTES" "surviving testName is '$B08_TITLE'")"
assert_log_contains "$B08_DIR/second-runtime.log" "Overwriting an existing report" \
    || B08_NOTES="$(append_note "$B08_NOTES" "the only trace of run 1 — the WARN — is missing too")"
if [[ -z "$B08_NOTES" ]]; then
    record B08 "New JTL, same report name" PASS "run 2 overwrote; the WARN is all that is left of run 1"
else
    record B08 "New JTL, same report name" FAIL "$B08_NOTES"
fi

# -----------------------------------------------------------------------------
# B09 (seed: a FILE at the output path): the output directory is not a directory.
#
#      The seed is the scenario — on a clean directory this row simply generates a
#      report and passes as something else entirely.
# -----------------------------------------------------------------------------
echo "[B09] Output path is an existing file..."
B09_BASE="$RESULTS/B09"
clean_dir "$B09_BASE"
B09_BLOCKED="$B09_BASE/blocked"
: > "$B09_BLOCKED"
B09_NOTES=""
[[ -f "$B09_BLOCKED" && ! -d "$B09_BLOCKED" ]] \
    || B09_NOTES="$(append_note "$B09_NOTES" "the seed file was not created")"
run_out "$B09_BASE" r1 "$SINGLE" "$B09_BLOCKED" -Jwebinsight.report.title=B09
B09_RC=$?

# The run must have HAPPENED — otherwise "no artifacts" is free.
produced_samples "$B09_BASE/r1.jtl" \
    || B09_NOTES="$(append_note "$B09_NOTES" "the run produced no samples, so 'no artifacts' proves nothing")"
assert_contains "$B09_BASE/r1.log" "WEB-INSIGHT-REPORT: FAILED" \
    || B09_NOTES="$(append_note "$B09_NOTES" "no FAILED marker on the console")"
assert_contains "$B09_BASE/r1.log" "not a directory" \
    || B09_NOTES="$(append_note "$B09_NOTES" "the message does not name the output directory as the problem")"
[[ "$(count_glob "$B09_BASE"/*.html)" -eq 0 ]] \
    || B09_NOTES="$(append_note "$B09_NOTES" "partial artifacts were left behind")"
[[ "$(stat_size "$B09_BLOCKED")" -eq 0 ]] \
    || B09_NOTES="$(append_note "$B09_NOTES" "the seed file itself was written to")"

if [[ -n "$B09_NOTES" ]]; then
    record B09 "Output path is a file" FAIL "$B09_NOTES"
elif [[ "$B09_RC" -ne 0 ]]; then
    record B09 "Output path is a file" PASS \
        "clear failure, no artifacts, exit $B09_RC — the CSV's 'non-zero exit' now holds on the listener path too: webinsight.report.failonerror defaults to true, and the listener arranges the status from a shutdown hook rather than by aborting the test"
else
    record B09 "Output path is a file" DIFF \
        "the failure is clear and nothing partial is left, but JMeter still exits 0 — nothing was asked for here, so with failonerror defaulting to true this run should have failed on its own. An exit 0 means the new default did not reach the listener path, and the CSV's 'non-zero exit' is unmet for the same reason it always was."
fi

# -----------------------------------------------------------------------------
# B10 (seed: read-only dir): the output directory exists and cannot be written to.
#
#      Staged with a real ACL denial, not the DOS read-only attribute: attrib +R on a
#      directory leaves both File.canWrite() and an actual file creation succeeding on
#      Windows, so it stages nothing. The ACE is removed immediately after the run —
#      a directory left denied cannot be cleaned up by the next cycle.
# -----------------------------------------------------------------------------
echo "[B10] Output directory is not writable..."
B10_BASE="$RESULTS/B10"
clean_dir "$B10_BASE"
B10_OUT="$B10_BASE/readonly"
mkdir -p "$B10_OUT"
if ! deny_writes "$B10_OUT"; then
    allow_writes "$B10_OUT"
    record B10 "Unwritable output directory" BLOCKED \
        "could not make the directory unwritable for this user on this platform (tried an icacls deny ACE, then chmod a-w; a probe file was still created afterwards), so the precondition cannot be staged and any result would be a false pass"
else
    run_out "$B10_BASE" r1 "$SINGLE" "$B10_OUT" -Jwebinsight.report.title=B10
    B10_RC=$?
    allow_writes "$B10_OUT"

    B10_NOTES=""
    produced_samples "$B10_BASE/r1.jtl" \
        || B10_NOTES="$(append_note "$B10_NOTES" "the run produced no samples, so 'nothing left behind' proves nothing")"
    assert_contains "$B10_BASE/r1.log" "WEB-INSIGHT-REPORT: FAILED" \
        || B10_NOTES="$(append_note "$B10_NOTES" "no FAILED marker on the console")"
    [[ "$(count_glob "$B10_OUT"/*.html)" -eq 0 ]] \
        || B10_NOTES="$(append_note "$B10_NOTES" "an HTML file was left in the unwritable directory")"
    [[ "$(count_glob "$B10_OUT"/*.json)" -eq 0 ]] \
        || B10_NOTES="$(append_note "$B10_NOTES" "a JSON file was left in the unwritable directory")"
    # Nothing was produced, so failonerror — now on by default — has to have fired.
    [[ "$B10_RC" -ne 0 ]] \
        || B10_NOTES="$(append_note "$B10_NOTES" "no report was produced and the run still exited 0")"

    # The second half of the row: does it fail BEFORE rendering, naming the DIRECTORY?
    # ensureWritableDirectory is meant to be that check, and File.canWrite() is how it
    # asks — which on Windows answers "yes" for a directory denied by ACL.
    B10_EARLY=no
    assert_contains "$B10_BASE/r1.log" "not writable" && B10_EARLY=yes

    if [[ -n "$B10_NOTES" ]]; then
        record B10 "Unwritable output directory" FAIL "$B10_NOTES"
    elif [[ "$B10_EARLY" == "yes" ]]; then
        record B10 "Unwritable output directory" PASS "refused up front, naming the directory; nothing left behind (exit $B10_RC)"
    else
        record B10 "Unwritable output directory" DIFF \
            "it fails and leaves nothing behind, but NOT before rendering and NOT naming the directory: the pre-flight guard asks File.canWrite(), which returns true for an ACL-denied directory on Windows, so the run renders the whole report and then fails on the write — the message names the report file, not the directory that is wrong. See $B10_BASE/r1.log"
    fi
fi

# -----------------------------------------------------------------------------
# B11 (seed: unwritable .json path): the HTML succeeds and its CI/CD sibling cannot be
#      written. Staged by pre-creating the .json path as a DIRECTORY — the one way to
#      make a single named file unwritable that behaves identically on every platform.
#
#      The failure is swallowed by design (a report is still worth having), so what is
#      under test is whether it is REPORTED: on the console, at ERROR, and — since
#      failonerror defaults to true and now fires on a PARTIAL failure as well as a
#      total one — in the exit status.
# -----------------------------------------------------------------------------
echo "[B11] The CI/CD JSON path cannot be written..."
B11_DIR="$RESULTS/B11"
clean_dir "$B11_DIR"
mkdir -p "$B11_DIR/b11-report.json"
B11_NOTES=""
[[ -d "$B11_DIR/b11-report.json" ]] || B11_NOTES="$(append_note "$B11_NOTES" "the seed directory was not created")"
run_into "$B11_DIR" r1 "$SINGLE" -Jwebinsight.report.filename=b11-report.html -Jwebinsight.report.title=B11
B11_RC=$?

file_exists "$B11_DIR/b11-report.html" \
    || B11_NOTES="$(append_note "$B11_NOTES" "the HTML was not kept")"
assert_log_contains "$B11_DIR/r1-runtime.log" "Failed to write the CI/CD JSON" \
    || B11_NOTES="$(append_note "$B11_NOTES" "the JSON failure was not logged at ERROR")"
assert_contains "$B11_DIR/r1.log" "JSON : FAILED" \
    || B11_NOTES="$(append_note "$B11_NOTES" "the console summary does not show JSON : FAILED")"
assert_contains "$B11_DIR/r1.log" "WEB-INSIGHT-REPORT: INCOMPLETE" \
    || B11_NOTES="$(append_note "$B11_NOTES" "no INCOMPLETE marker on the console")"
[[ "$B11_RC" -ne 0 ]] \
    || B11_NOTES="$(append_note "$B11_NOTES" "the set is incomplete and the run still exited 0")"
if [[ -z "$B11_NOTES" ]]; then
    record B11 "JSON write failure" PASS \
        "HTML kept; the failure is on the console, at ERROR, and in the exit status ($B11_RC)"
else
    record B11 "JSON write failure" FAIL "$B11_NOTES"
fi

# -----------------------------------------------------------------------------
# B12 (clean): the listener element is present but disabled. `clean` is essential — a
#              leftover report would read as "the listener ran". And the absence only
#              means anything once the run is shown to have happened, so the JTL has to
#              contain samples before the missing report proves anything.
#
#              This row is EXEMPT from the failonerror default and must stay exit 0:
#              failonerror fires on an artifact that was planned and could not be
#              written, and a disabled listener plans none — the plugin never ran, so
#              there is no failure to report. A non-zero status here would mean the
#              opposite of what it means everywhere else in this file: not "the report
#              failed" but "something ran that was switched off". Do not "fix" it to
#              match C19 or B09.
# -----------------------------------------------------------------------------
echo "[B12] Listener element disabled..."
B12_DIR="$RESULTS/B12"
clean_dir "$B12_DIR"
run_into "$B12_DIR" r1 "$DISABLED_JMX" -Jwebinsight.report.title=B12
B12_RC=$?

B12_NOTES=""
produced_samples "$B12_DIR/r1.jtl" \
    || B12_NOTES="$(append_note "$B12_NOTES" "the run produced no samples, so the missing report proves nothing")"
[[ "$(count_glob "$B12_DIR"/*.html)" -eq 0 ]] \
    || B12_NOTES="$(append_note "$B12_NOTES" "a disabled listener still produced a report")"
assert_log_lacks "$B12_DIR/r1-runtime.log" "Web Insight Report: outputDir=" \
    || B12_NOTES="$(append_note "$B12_NOTES" "no report, but something logged an intent line")"
assert_exit_code "$B12_RC" 0 \
    || B12_NOTES="$(append_note "$B12_NOTES" "expected a clean exit — no artifact was planned, so the failonerror default has nothing to fire on — got $B12_RC")"
if [[ -z "$B12_NOTES" ]]; then
    record B12 "Disabled listener" PASS \
        "samples recorded, no report, no intent line, clean exit — nothing was attempted, so nothing failed"
else
    record B12 "Disabled listener" FAIL "$B12_NOTES"
fi

print_summary "FAMILY A & B SCENARIO SUMMARY"

exit $FAIL
