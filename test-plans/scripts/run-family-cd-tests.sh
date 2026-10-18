#!/usr/bin/env bash
# =============================================================================
#  Scenario families C and D — the -g exporter, and both ingestion paths at once
#
#  C01-C08  `jmeter -g <jtl> -o <dir>`          (CLI exporter alone)
#  D01-D06  `jmeter -n -t plan.jmx -e -o <dir>` (listener AND exporter active)
#
#  The grounding for every Expected value
#  facts (F1-F18) are in test-plans/SCENARIO-MATRIX.md.
#
#  WHAT THESE FAMILIES ARE FOR
#  ---------------------------
#  The two ingestion paths disagree about a report that is already there. The
#  listener OVERWRITES it (F2); the exporter SKIPS and produces nothing (F8).
#  A stale report can therefore suppress a fresh one and still look current.
#  C02 and C06 are the rows that make that visible, and both are worthless
#  without their precondition: on a clean directory the exporter simply
#  generates, the row passes, and it passed as a different scenario.
#
#  THE PROVENANCE MARKER
#  ---------------------
#  Never mtimes. The report TITLE reaches all three artifacts (F16) and is
#  settable from either surface, so seed with SEED-RUN-1, run the scenario with
#  RUN-2 and read the JSON "testName":
#      RUN-2      -> the run overwrote   (listener, F2)
#      SEED-RUN-1 -> the run skipped     (exporter, F8)
#
#  TWO WAYS TO POINT THE EXPORTER AT A DIRECTORY, AND WHY BOTH ARE HERE
#  --------------------------------------------------------------------
#  The exporter does NOT read -Jwebinsight.report.output. That property is the
#  LISTENER's output directory; WebInsightDataExporter.resolveOutputDirectory
#  reads jmeter.reportgenerator.outputdir (which -o sets), then its own
#  output_dir from reportgenerator.properties. So a `seed:`/`carry` row cannot
#  route the collision through webinsight.report.output — the exporter would
#  write somewhere else entirely and the scenario would evaporate.
#
#  But `-o` refuses ANY non-empty directory:
#      An error occurred: Cannot write to '<dir>' as folder is not empty
#  thrown by JOrphanUtils before a single exporter runs, and `-f` fixes it by
#  DELETING the directory — which destroys the seed the row exists to test.
#  Verified both ways on JMeter 5.6.3.
#
#  Setting jmeter.reportgenerator.outputdir directly and omitting -o skips that
#  argv-level check: JMeter then only applies each exporter's own guard, and
#  those guards look for that exporter's own output (JsonExporter -> its
#  statistics.json, HtmlTemplateExporter -> its index.html). A directory holding
#  nothing but a Web Insight report pair passes them, which is exactly the state
#  C02/C03/D05 need. Directories that must start EMPTY use -o, so JMeter's check
#  is a second opinion on the precondition.
#
#  That asymmetry is itself a finding and C06 records it: two -g runs into one
#  folder cannot reach the skip check at all, because run 1 left JMeter's own
#  statistics.json behind and run 2 dies on it first.
#
#  Assumes the plugin is already deployed (run ./scripts/build-and-deploy.sh).
# =============================================================================
set -uo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../../scripts/lib/common.sh"

require_jmeter
require_python || exit 1

# The helpers below print artifact contents back to the shell. On Windows Python's stdout
# defaults to the console codepage, so a non-ASCII title raises UnicodeEncodeError, the
# helper dies, and the caller silently compares against an empty string.
export PYTHONIOENCODING=utf-8

# Deliberately NOT named RESULTS: common.sh uses that name for the array print_summary
# iterates, so assigning a string to it makes the suite directory appear as the first line
# of the summary. (run-title-junit-tests.sh has that collision; do not copy it.)
SUITE_DIR="$RESULTS_DIR/family-cd-tests"
SINGLE="$TEST_PLANS/single-sampler-test.jmx"
NOLISTENER="$TEST_PLANS/no-listener-test.jmx"
DISABLED="$TEST_PLANS/listener-disabled-test.jmx"
GUI_JMX="$TEST_PLANS/gui-filename-test.jmx"   # title GUI-CONFIGURED, filename gui-name.html

SHORT=(-Jthreads=2 -Jduration=4 -Jrampup=1)

print_banner "Scenario Families C (CLI exporter) & D (both paths)"

rm -rf "$SUITE_DIR"
mkdir -p "$SUITE_DIR"

# -----------------------------------------------------------------------------
# Helpers
# -----------------------------------------------------------------------------

json_title() { "$PYTHON" -c "import json,sys;print(json.load(open(sys.argv[1],encoding='utf-8'))['testName'])" "$1" 2>/dev/null; }
xml_valid()  { "$PYTHON" -c "import xml.etree.ElementTree as E,sys;E.parse(sys.argv[1])" "$1" >/dev/null 2>&1; }

# fresh_dir <dir> — Pre=clean. Remove, recreate, and PROVE it is empty; a report left by
# an aborted earlier invocation is exactly the debris that satisfies an assertion this run
# never earned. Rows whose Pre is `seed:` or `carry` must not call this.
fresh_dir() {
    local dir="$1"
    rm -rf "$dir"
    mkdir -p "$dir"
    if [[ -n "$(ls -A "$dir" 2>/dev/null)" ]]; then
        echo "  [PRECONDITION] $dir is not empty at start -- results would be unsound" >&2
        return 1
    fi
}

# seed_report <case-dir> <target-dir> <title> [extra -J args...]
# Pre=seed:<report>. A REAL listener run, because a hand-copied file proves nothing about
# what the plugin writes. The JTL is deliberately written OUTSIDE the target, so the seeded
# directory holds the report pair and nothing else — anything else in it changes which of
# JMeter's own exporters refuses to run later.
seed_report() {
    local case_dir="$1" target="$2" title="$3"; shift 3
    mkdir -p "$case_dir/work" "$target"
    WIR_RUNTIME_LOG="$case_dir/seed-runtime.log" \
    run_jmeter -n -t "$SINGLE" -l "$case_dir/work/seed.jtl" \
        "${SHORT[@]}" \
        -Jwebinsight.report.output="$target" \
        -Jwebinsight.report.title="$title" \
        "$@" > "$case_dir/seed.log" 2>&1
}

# export_fresh <case-dir> <tag> <out-dir> [extra args...] — `-g … -o <dir>`, the documented
# form. JMeter enforces the empty-directory precondition itself here.
export_fresh() {
    local case_dir="$1" tag="$2" out="$3"; shift 3
    WIR_RUNTIME_LOG="$case_dir/$tag-runtime.log" \
    run_jmeter -g "$SRC_JTL" -o "$out" "$@" > "$case_dir/$tag.log" 2>&1
}

# export_into <case-dir> <tag> <dir> [extra args...] — `-g` aimed at a directory that is
# ALREADY populated, via the property -o would otherwise set. See the header.
export_into() {
    local case_dir="$1" tag="$2" out="$3"; shift 3
    WIR_RUNTIME_LOG="$case_dir/$tag-runtime.log" \
    run_jmeter -g "$SRC_JTL" -Jjmeter.reportgenerator.outputdir="$out" "$@" \
        > "$case_dir/$tag.log" 2>&1
}

# JMeter's own dashboard reached its export stage. Positive proof that a run happened, so an
# "and no Web Insight report appeared" conclusion is earned rather than free.
dashboard_present() { file_exists "$1/index.html" && file_exists "$1/statistics.json"; }

skipped()  { assert_log_contains "$1" "skipping CLI generation"; }

# .html files that are not JMeter's own index.html — i.e. how many Web Insight reports.
wir_html_count() {
    local n=0 f
    shopt -s nullglob
    for f in "$1"/*.html; do
        [[ "$(basename "$f")" == "index.html" ]] || n=$((n + 1))
    done
    shopt -u nullglob
    echo "$n"
}

# -----------------------------------------------------------------------------
# One source JTL for every C row and for the C08 control.
#
# Produced by a plan with NO listener element, so the JTL carries no report of its own and
# every C row starts from the same samples — a row that behaved differently because its
# input differed would be untraceable.
# -----------------------------------------------------------------------------
SRC_DIR="$SUITE_DIR/_src"
mkdir -p "$SRC_DIR"
SRC_JTL="$SRC_DIR/src.jtl"
echo "[setup] Generating the source JTL for the C family..."
WIR_RUNTIME_LOG="$SRC_DIR/src-runtime.log" \
run_jmeter -n -t "$NOLISTENER" -l "$SRC_JTL" "${SHORT[@]}" > "$SRC_DIR/src.log" 2>&1
if [[ ! -s "$SRC_JTL" ]]; then
    echo "ERROR: no source JTL was produced -- every C row would fail for the same wrong reason." >&2
    echo "       See $SRC_DIR/src.log" >&2
    exit 1
fi

# =============================================================================
#  C — the CLI exporter
# =============================================================================

# -----------------------------------------------------------------------------
# C01  Pre=clean. -g into a fresh empty -o.
#      Expected: report generated normally alongside JMeter's own dashboard.
# -----------------------------------------------------------------------------
echo "[C01] -g into a fresh empty -o..."
C01="$SUITE_DIR/C01"; mkdir -p "$C01"
C01_OUT="$C01/dash"
if ! fresh_dir "$C01_OUT"; then
    record C01 "Exporter into a clean -o" FAIL "precondition: output dir not empty"
else
    export_fresh "$C01" gen "$C01_OUT" -Jwebinsight.report.title=RUN-C01
    C01_NOTES=""
    file_exists "$C01_OUT/web-insight-report.html" \
        || C01_NOTES="$(append_note "$C01_NOTES" "no web-insight-report.html")"
    file_exists "$C01_OUT/web-insight-report.json" \
        || C01_NOTES="$(append_note "$C01_NOTES" "no .json sibling")"
    dashboard_present "$C01_OUT" \
        || C01_NOTES="$(append_note "$C01_NOTES" "JMeter's own dashboard is missing")"
    if file_exists "$C01_OUT/web-insight-report.json"; then
        C01_T="$(json_title "$C01_OUT/web-insight-report.json")"
        [[ "$C01_T" == "RUN-C01" ]] \
            || C01_NOTES="$(append_note "$C01_NOTES" "testName was '$C01_T'")"
    fi
    if [[ -z "$C01_NOTES" ]]; then
        record C01 "Exporter into a clean -o" PASS "report + .json generated beside index.html/statistics.json"
    else
        record C01 "Exporter into a clean -o" FAIL "$C01_NOTES"
    fi
fi

# The control for C08: JMeter's own statistics.json for THIS jtl, written by a run that
# configured no report filename at all.
C08_STATS_CTL="$(file_md5 "$C01_OUT/statistics.json")"

# -----------------------------------------------------------------------------
# C02  Pre=seed: a report titled SEED-RUN-1, from a DIFFERENT run.
#      Expected: the exporter OVERWRITES it and announces that it did — the surviving
#      testName is RUN-2, there is still exactly one report, and no "skipping" WARN.
#
#      This row asserted the opposite until the exists-policy was unified, and the reversal is
#      the point rather than a regression. Given the same directory state the listener
#      overwrote and the exporter skipped; neither is wrong alone, the disagreement is. Worse,
#      the exporter answered the question by NAME, so a stale report from an unrelated run
#      suppressed a fresh one and still looked current — the run produced nothing and said so
#      only in a log with no console appender. Skipping is now reserved for the single case
#      that justifies it: an existing report whose runId proves it is THIS run's, which is the
#      combined `-n -t … -e -o` handshake.
#
#      The seed is a real listener run and its title is asserted BEFORE the scenario runs: on
#      a clean directory there would be nothing to overwrite, and the row would pass as a
#      completely different scenario.
# -----------------------------------------------------------------------------
echo "[C02] Exporter into a directory that already holds a report..."
C02="$SUITE_DIR/C02"; mkdir -p "$C02"
C02_SHARED="$C02/shared"
fresh_dir "$C02_SHARED" >/dev/null 2>&1
seed_report "$C02" "$C02_SHARED" SEED-RUN-1
C02_SEEDED="$(json_title "$C02_SHARED/web-insight-report.json")"
if [[ "$C02_SEEDED" != "SEED-RUN-1" ]]; then
    record C02 "Exporter skips an existing report" FAIL \
        "PRECONDITION not met: the seed run left testName '$C02_SEEDED', not SEED-RUN-1 -- without the seed this row tests nothing"
else
    export_into "$C02" gen "$C02_SHARED" -Jwebinsight.report.title=RUN-2
    C02_NOTES=""
    dashboard_present "$C02_SHARED" \
        || C02_NOTES="$(append_note "$C02_NOTES" "the -g run never reached its export stage, so 'no new report' proves nothing")"
    ! skipped "$C02/gen-runtime.log" \
        || C02_NOTES="$(append_note "$C02_NOTES" "it skipped -- a report from an UNRELATED run suppressed this one, which is the D6 defect")"
    C02_AFTER="$(json_title "$C02_SHARED/web-insight-report.json")"
    [[ "$C02_AFTER" == "RUN-2" ]] \
        || C02_NOTES="$(append_note "$C02_NOTES" "the seeded report was not overwritten: testName is still '$C02_AFTER'")"
    [[ "$(wir_html_count "$C02_SHARED")" -eq 1 ]] \
        || C02_NOTES="$(append_note "$C02_NOTES" "$(wir_html_count "$C02_SHARED") Web Insight reports in the directory, expected 1")"
    # Overwriting silently is the other half of the defect: a ${timestamp} collision used to
    # destroy the previous run's report without a word.
    assert_log_contains "$C02/gen-runtime.log" "Overwriting an existing report" \
        || C02_NOTES="$(append_note "$C02_NOTES" "the overwrite was not announced")"
    if [[ -z "$C02_NOTES" ]]; then
        record C02 "Exporter overwrites a foreign report" PASS \
            "RUN-2 replaced SEED-RUN-1, one report, overwrite announced -- both paths now share one exists-policy (D6/B4)"
    else
        record C02 "Exporter overwrites a foreign report" FAIL "$C02_NOTES"
    fi
fi

# -----------------------------------------------------------------------------
# C03  Pre=seed: a report under a DIFFERENT name.
#      Expected: no collision detected; a new report is generated normally.
# -----------------------------------------------------------------------------
echo "[C03] Existing report under a different name..."
C03="$SUITE_DIR/C03"; mkdir -p "$C03"
C03_SHARED="$C03/shared"
fresh_dir "$C03_SHARED" >/dev/null 2>&1
seed_report "$C03" "$C03_SHARED" SEED-RUN-1 -Jwebinsight.report.filename=seeded-report.html
C03_SEEDED="$(json_title "$C03_SHARED/seeded-report.json")"
if [[ "$C03_SEEDED" != "SEED-RUN-1" ]]; then
    record C03 "Different name is not a collision" FAIL \
        "PRECONDITION not met: no seeded-report.json titled SEED-RUN-1 (got '$C03_SEEDED')"
else
    export_into "$C03" gen "$C03_SHARED" \
        -Jwebinsight.report.filename=c03-report.html -Jwebinsight.report.title=RUN-2
    C03_NOTES=""
    file_exists "$C03_SHARED/c03-report.html" \
        || C03_NOTES="$(append_note "$C03_NOTES" "no c03-report.html was generated")"
    C03_NEW="$(json_title "$C03_SHARED/c03-report.json")"
    [[ "$C03_NEW" == "RUN-2" ]] \
        || C03_NOTES="$(append_note "$C03_NOTES" "the new report's testName was '$C03_NEW'")"
    C03_OLD="$(json_title "$C03_SHARED/seeded-report.json")"
    [[ "$C03_OLD" == "SEED-RUN-1" ]] \
        || C03_NOTES="$(append_note "$C03_NOTES" "the seeded report changed: testName is now '$C03_OLD'")"
    ! skipped "$C03/gen-runtime.log" \
        || C03_NOTES="$(append_note "$C03_NOTES" "it skipped -- a differently named report was treated as a collision")"
    if [[ -z "$C03_NOTES" ]]; then
        record C03 "Different name is not a collision" PASS \
            "generated c03-report.html (RUN-2); the seeded pair is untouched"
    else
        record C03 "Different name is not a collision" FAIL "$C03_NOTES"
    fi
fi

# -----------------------------------------------------------------------------
# C04  Pre=seed: JMeter dashboard files. '${timestamp}' as the ENTIRE filename.
#
#      The seed is intrinsic rather than staged: JsonExporter writes statistics.json BEFORE
#      any registered exporter runs, so by the time the collision check executes the
#      dashboard files are already there — which is precisely how this defect happened. The
#      row therefore starts from a CLEAN -o and asserts the seed was present afterwards.
#
#      CSV Expected: "the glob degenerates to '*' … so the exporter skips and no report
#      appears". That is the pre-fix behaviour. listenerReportExists now refuses to identify
#      a report from a glob with no literal part and generates instead, which C23 also
#      covers. Asserting the fix and recording the DIFF, rather than restating a defect.
# -----------------------------------------------------------------------------
echo "[C04] \${timestamp} as the whole filename, dashboard files present..."
C04="$SUITE_DIR/C04"; mkdir -p "$C04"
C04_OUT="$C04/dash"
if ! fresh_dir "$C04_OUT"; then
    record C04 "Whole-name timestamp glob" FAIL "precondition: output dir not empty"
else
    export_fresh "$C04" gen "$C04_OUT" \
        -Jwebinsight.report.filename='${timestamp}' -Jwebinsight.report.title=RUN-C04
    C04_NOTES=""
    file_exists "$C04_OUT/statistics.json" \
        || C04_NOTES="$(append_note "$C04_NOTES" "statistics.json absent -- the seed condition this row needs never existed")"
    C04_N="$(count_glob "$C04_OUT"/2*.html)"
    [[ "$C04_N" -ge 1 ]] \
        || C04_NOTES="$(append_note "$C04_NOTES" "no timestamp-named report was produced; the glob suppressed it")"
    if [[ -z "$C04_NOTES" ]]; then
        record C04 "Whole-name timestamp glob" PASS \
            "statistics.json was present and a timestamp-named report was still generated -- DIFF from the CSV Expected, which describes the pre-fix skip"
    else
        record C04 "Whole-name timestamp glob" FAIL "$C04_NOTES"
    fi
fi

# -----------------------------------------------------------------------------
# C05  Pre=clean. -g with a filename containing a path separator.
#      Expected: falls back to the default name with a warning; report still produced (F4).
# -----------------------------------------------------------------------------
echo "[C05] Filename containing a path separator..."
C05="$SUITE_DIR/C05"; mkdir -p "$C05"
C05_OUT="$C05/dash"
if ! fresh_dir "$C05_OUT"; then
    record C05 "Path separator in the filename" FAIL "precondition: output dir not empty"
else
    export_fresh "$C05" gen "$C05_OUT" \
        -Jwebinsight.report.filename='sub/dir/r.html' -Jwebinsight.report.title=RUN-C05
    C05_NOTES=""
    file_exists "$C05_OUT/web-insight-report.html" \
        || C05_NOTES="$(append_note "$C05_NOTES" "no fallback-named report")"
    file_absent "$C05_OUT/sub" \
        || C05_NOTES="$(append_note "$C05_NOTES" "a sub/ directory was created")"
    assert_log_contains "$C05/gen-runtime.log" "must be a plain file name" \
        || C05_NOTES="$(append_note "$C05_NOTES" "no WARN naming the rejected filename")"
    if [[ -z "$C05_NOTES" ]]; then
        record C05 "Path separator in the filename" PASS \
            "rejected with a WARN, default name used, no subdirectory created"
    else
        record C05 "Path separator in the filename" FAIL "$C05_NOTES"
    fi
fi

# -----------------------------------------------------------------------------
# C06  Pre=carry. -g twice into the same directory, default names, RUN-1 then RUN-2.
#      Expected: run 1 generates; run 2 SKIPS, so the surviving testName stays RUN-1 —
#      the direct contrast with B01, where the listener makes it RUN-2.
#
#      Both runs go through the property route, so the ONLY difference between them is
#      what the directory already contains.
# -----------------------------------------------------------------------------
echo "[C06] Two -g runs into the same directory (carry)..."
C06="$SUITE_DIR/C06"; mkdir -p "$C06"
C06_SHARED="$C06/shared"
if ! fresh_dir "$C06_SHARED"; then
    record C06 "Exporter run twice into one directory" FAIL "precondition: shared dir not empty at start"
else
    export_into "$C06" run1 "$C06_SHARED" -Jwebinsight.report.title=RUN-1
    C06_FIRST="$(json_title "$C06_SHARED/web-insight-report.json")"
    if [[ "$C06_FIRST" != "RUN-1" ]]; then
        record C06 "Exporter run twice into one directory" FAIL \
            "PRECONDITION not met: run 1 did not leave a report titled RUN-1 (got '$C06_FIRST')"
    else
        export_into "$C06" run2 "$C06_SHARED" -Jwebinsight.report.title=RUN-2
        C06_RC=$?
        C06_AFTER="$(json_title "$C06_SHARED/web-insight-report.json")"
        C06_NOTES=""
        [[ "$C06_AFTER" == "RUN-1" ]] \
            || C06_NOTES="$(append_note "$C06_NOTES" "run 2 replaced the report: testName is now '$C06_AFTER'")"
        [[ "$(wir_html_count "$C06_SHARED")" -eq 1 ]] \
            || C06_NOTES="$(append_note "$C06_NOTES" "$(wir_html_count "$C06_SHARED") Web Insight reports, expected 1")"
        # The plugin's exists-policy is UNREACHABLE here, and that is the finding rather than a
        # failure. JMeter's own JsonExporter writes statistics.json before any registered
        # exporter runs, so run 2 is refused by JMeter itself — "folder is not empty" — and our
        # code never executes. Asserting a WARN that cannot be emitted would restate the
        # environment as a defect; the net outcome is what this row can honestly judge.
        if [[ -n "$C06_NOTES" ]]; then
            record C06 "Exporter run twice into one directory" FAIL "$C06_NOTES"
        elif skipped "$C06/run2-runtime.log"; then
            record C06 "Exporter run twice into one directory" PASS \
                "run 2 reached the exists-policy and skipped; RUN-1 survived"
        elif [[ "$C06_RC" -ne 0 ]] && assert_log_contains "$C06/run2-runtime.log" "as folder is not empty"; then
            # The expected route from -g, not a deviation: it used to be recorded as DIFF, which
            # left this runner red on every machine although nothing behaved worse than claimed.
            record C06 "Exporter run twice into one directory" PASS \
                "RUN-1 survived and no second report appeared: JMeter itself refused the non-empty -o (exit $C06_RC, 'folder is not empty' on run 1's statistics.json) before any exporter ran, so the plugin's skip is never reached from -g"
        else
            record C06 "Exporter run twice into one directory" DIFF \
                "RUN-1 survived and no second report appeared -- but run 2 never reached the plugin at all: JMeter refused the non-empty -o (exit $C06_RC, JsonExporter 'folder is not empty' on run 1's statistics.json). The outcome the CSV predicts holds; the mechanism it names does not, and cannot, from -g"
        fi
    fi
fi

# -----------------------------------------------------------------------------
# C07  Pre=clean. -g with -Jwebinsight.report.junit=true. Expected: a .xml sibling (F1).
# -----------------------------------------------------------------------------
echo "[C07] JUnit XML from the exporter..."
C07="$SUITE_DIR/C07"; mkdir -p "$C07"
C07_OUT="$C07/dash"
if ! fresh_dir "$C07_OUT"; then
    record C07 "JUnit XML under -g" FAIL "precondition: output dir not empty"
else
    export_fresh "$C07" gen "$C07_OUT" \
        -Jwebinsight.report.junit=true -Jwebinsight.report.title=RUN-C07
    C07_NOTES=""
    file_exists "$C07_OUT/web-insight-report.html" \
        || C07_NOTES="$(append_note "$C07_NOTES" "no report at all")"
    file_exists "$C07_OUT/web-insight-report.xml" \
        || C07_NOTES="$(append_note "$C07_NOTES" "no .xml sibling")"
    xml_valid "$C07_OUT/web-insight-report.xml" \
        || C07_NOTES="$(append_note "$C07_NOTES" ".xml is not well-formed")"
    if [[ -z "$C07_NOTES" ]]; then
        record C07 "JUnit XML under -g" PASS "well-formed .xml written beside the .html and .json"
    else
        record C07 "JUnit XML under -g" FAIL "$C07_NOTES"
    fi
fi

# -----------------------------------------------------------------------------
# C08  Pre=seed: JMeter dashboard files. Report named statistics.json.
#      Expected: rejected or handled WITHOUT clobbering JMeter's dashboard (F4).
#      Same intrinsic seed as C04 — JMeter writes statistics.json before the check runs.
#      Compared byte-for-byte against C01's copy, which came from the same JTL.
# -----------------------------------------------------------------------------
echo "[C08] Report named after a reserved dashboard file..."
C08="$SUITE_DIR/C08"; mkdir -p "$C08"
C08_OUT="$C08/dash"
if [[ "$C08_STATS_CTL" == "none" ]]; then
    record C08 "Reserved dashboard filename" FAIL \
        "no control statistics.json from C01 to compare against"
elif ! fresh_dir "$C08_OUT"; then
    record C08 "Reserved dashboard filename" FAIL "precondition: output dir not empty"
else
    export_fresh "$C08" gen "$C08_OUT" \
        -Jwebinsight.report.filename=statistics -Jwebinsight.report.title=RUN-C08
    C08_NOTES=""
    [[ "$(file_md5 "$C08_OUT/statistics.json")" == "$C08_STATS_CTL" ]] \
        || C08_NOTES="$(append_note "$C08_NOTES" "JMeter's statistics.json was modified")"
    file_exists "$C08_OUT/web-insight-report.html" \
        || C08_NOTES="$(append_note "$C08_NOTES" "no Web Insight report was produced under the fallback name")"
    assert_log_contains "$C08/gen-runtime.log" "JMeter's own HTML dashboard" \
        || C08_NOTES="$(append_note "$C08_NOTES" "the reserved name was not reported")"
    if [[ -z "$C08_NOTES" ]]; then
        record C08 "Reserved dashboard filename" PASS \
            "name refused with a WARN, dashboard byte-identical to the control, report still produced"
    else
        record C08 "Reserved dashboard filename" FAIL "$C08_NOTES"
    fi
fi

# =============================================================================
#  D — both ingestion paths active in one run (-n -t … -e -o)
#
#  The listener writes during the test; the exporter runs afterwards in the SAME JVM, so
#  the resolved-filename handshake (F9) is available to it — which is what separates D02
#  and D06 from anything the C family can show.
# =============================================================================

# -----------------------------------------------------------------------------
# D01  Pre=clean. Listener plus -e -o, default config.
#      Expected: the listener writes it, the exporter finds it and skips. Exactly one
#      report, skip WARN present.
# -----------------------------------------------------------------------------
echo "[D01] Listener and exporter, default config..."
D01="$SUITE_DIR/D01"; mkdir -p "$D01"
D01_OUT="$D01/dash"
rm -rf "$D01_OUT"
WIR_RUNTIME_LOG="$D01/jmeter-runtime.log" \
run_jmeter -n -t "$SINGLE" -l "$D01/results.jtl" "${SHORT[@]}" \
    -e -o "$D01_OUT" \
    -Jwebinsight.report.output="$D01_OUT" \
    -Jwebinsight.report.title=RUN-2 > "$D01/jmeter.log" 2>&1
D01_NOTES=""
dashboard_present "$D01_OUT" \
    || D01_NOTES="$(append_note "$D01_NOTES" "the -e stage never ran, so a skip cannot be concluded")"
file_exists "$D01_OUT/web-insight-report.html" \
    || D01_NOTES="$(append_note "$D01_NOTES" "no report")"
D01_T="$(json_title "$D01_OUT/web-insight-report.json")"
[[ "$D01_T" == "RUN-2" ]] \
    || D01_NOTES="$(append_note "$D01_NOTES" "testName was '$D01_T'")"
skipped "$D01/jmeter-runtime.log" \
    || D01_NOTES="$(append_note "$D01_NOTES" "no skip WARN -- the exporter did not notice the listener's report")"
[[ "$(wir_html_count "$D01_OUT")" -eq 1 ]] \
    || D01_NOTES="$(append_note "$D01_NOTES" "$(wir_html_count "$D01_OUT") Web Insight reports, expected 1")"
if [[ -z "$D01_NOTES" ]]; then
    record D01 "Both paths, default config" PASS "listener wrote it, exporter skipped, exactly one report"
else
    record D01 "Both paths, default config" FAIL "$D01_NOTES"
fi

# -----------------------------------------------------------------------------
# D02  Pre=clean. Filename set in the JMX only (gui-filename-test.jmx -> gui-name.html).
#      Expected: the exporter reads the resolved-filename JVM property the listener set,
#      looks for the RIGHT name and skips. One report — not one JMX-named plus one
#      default-named (F9). Reproduced as a real defect before the handshake existed.
# -----------------------------------------------------------------------------
echo "[D02] Filename configured in the JMX only..."
D02="$SUITE_DIR/D02"; mkdir -p "$D02"
D02_OUT="$D02/dash"
rm -rf "$D02_OUT"
WIR_RUNTIME_LOG="$D02/jmeter-runtime.log" \
run_jmeter -n -t "$GUI_JMX" -l "$D02/results.jtl" "${SHORT[@]}" \
    -e -o "$D02_OUT" \
    -Jwebinsight.report.output="$D02_OUT" > "$D02/jmeter.log" 2>&1
D02_NOTES=""
dashboard_present "$D02_OUT" \
    || D02_NOTES="$(append_note "$D02_NOTES" "the -e stage never ran")"
file_exists "$D02_OUT/gui-name.html" \
    || D02_NOTES="$(append_note "$D02_NOTES" "no gui-name.html from the listener")"
file_absent "$D02_OUT/web-insight-report.html" \
    || D02_NOTES="$(append_note "$D02_NOTES" "a second, default-named report was generated -- the exporter looked for the wrong name")"
D02_T="$(json_title "$D02_OUT/gui-name.json")"
[[ "$D02_T" == "GUI-CONFIGURED" ]] \
    || D02_NOTES="$(append_note "$D02_NOTES" "testName was '$D02_T', so the surviving report is not the listener's")"
skipped "$D02/jmeter-runtime.log" \
    || D02_NOTES="$(append_note "$D02_NOTES" "no skip WARN")"
assert_log_contains "$D02/jmeter-runtime.log" "gui-name.html" \
    || D02_NOTES="$(append_note "$D02_NOTES" "the skip WARN does not name gui-name.html, so the handshake was not used")"
if [[ -z "$D02_NOTES" ]]; then
    record D02 "JMX-only filename, exporter handshake" PASS \
        "exporter derived gui-name.html from the resolved-filename property and skipped; one report"
else
    record D02 "JMX-only filename, exporter handshake" FAIL "$D02_NOTES"
fi

# -----------------------------------------------------------------------------
# D03  Pre=clean. Listener element DISABLED, -e -o still passed.
#      Expected: the exporter generates. Clean is essential — a stale report would make it
#      skip and the row would wrongly read as "the listener wrote it".
#      The listener's own output directory is set to a separate, empty folder so that
#      "the listener did not run" is checked rather than assumed.
# -----------------------------------------------------------------------------
echo "[D03] Listener disabled, exporter active..."
D03="$SUITE_DIR/D03"; mkdir -p "$D03"
D03_OUT="$D03/dash"; D03_LST="$D03/listener-out"
rm -rf "$D03_OUT"
fresh_dir "$D03_LST" >/dev/null 2>&1
WIR_RUNTIME_LOG="$D03/jmeter-runtime.log" \
run_jmeter -n -t "$DISABLED" -l "$D03/results.jtl" "${SHORT[@]}" \
    -e -o "$D03_OUT" \
    -Jwebinsight.report.output="$D03_LST" \
    -Jwebinsight.report.title=EXPORTER-RUN > "$D03/jmeter.log" 2>&1
D03_NOTES=""
[[ -z "$(ls -A "$D03_LST" 2>/dev/null)" ]] \
    || D03_NOTES="$(append_note "$D03_NOTES" "the disabled listener wrote something into its output directory")"
file_exists "$D03_OUT/web-insight-report.html" \
    || D03_NOTES="$(append_note "$D03_NOTES" "the exporter produced no report")"
D03_T="$(json_title "$D03_OUT/web-insight-report.json")"
[[ "$D03_T" == "EXPORTER-RUN" ]] \
    || D03_NOTES="$(append_note "$D03_NOTES" "testName was '$D03_T'")"
! skipped "$D03/jmeter-runtime.log" \
    || D03_NOTES="$(append_note "$D03_NOTES" "the exporter skipped -- something in the directory looked like a listener report")"
if [[ -z "$D03_NOTES" ]]; then
    record D03 "Disabled listener, exporter generates" PASS \
        "nothing from the listener, report written by the exporter (EXPORTER-RUN)"
else
    record D03 "Disabled listener, exporter generates" FAIL "$D03_NOTES"
fi

# -----------------------------------------------------------------------------
# D04  Pre=clean. No listener element at all, -e -o passed. Expected: the exporter
#      generates. Pins that installing the plugin changes the output of other people's
#      test plans on that machine.
# -----------------------------------------------------------------------------
echo "[D04] No listener element, exporter active..."
D04="$SUITE_DIR/D04"; mkdir -p "$D04"
D04_OUT="$D04/dash"
rm -rf "$D04_OUT"
WIR_RUNTIME_LOG="$D04/jmeter-runtime.log" \
run_jmeter -n -t "$NOLISTENER" -l "$D04/results.jtl" "${SHORT[@]}" \
    -e -o "$D04_OUT" \
    -Jwebinsight.report.title=EXPORTER-RUN > "$D04/jmeter.log" 2>&1
D04_NOTES=""
file_exists "$D04_OUT/web-insight-report.html" \
    || D04_NOTES="$(append_note "$D04_NOTES" "no report")"
D04_T="$(json_title "$D04_OUT/web-insight-report.json")"
[[ "$D04_T" == "EXPORTER-RUN" ]] \
    || D04_NOTES="$(append_note "$D04_NOTES" "testName was '$D04_T'")"
! skipped "$D04/jmeter-runtime.log" \
    || D04_NOTES="$(append_note "$D04_NOTES" "the exporter skipped in a directory no listener wrote to")"
if [[ -z "$D04_NOTES" ]]; then
    record D04 "No listener element, exporter generates" PASS "report written by the exporter (EXPORTER-RUN)"
else
    record D04 "No listener element, exporter generates" FAIL "$D04_NOTES"
fi

# -----------------------------------------------------------------------------
# D05  Pre=seed: a report titled SEED-RUN-1. Both paths active into that directory.
#      Expected: the listener OVERWRITES, so testName becomes RUN-2; the exporter then
#      sees the fresh file and skips. Net one report, from the listener (F2 + F8).
#      The marker separates "listener overwrote" from "exporter regenerated" — without it
#      the two are indistinguishable on disk.
# -----------------------------------------------------------------------------
echo "[D05] Both paths into a directory that already holds a report..."
D05="$SUITE_DIR/D05"; mkdir -p "$D05"
D05_SHARED="$D05/shared"
fresh_dir "$D05_SHARED" >/dev/null 2>&1
seed_report "$D05" "$D05_SHARED" SEED-RUN-1
D05_SEEDED="$(json_title "$D05_SHARED/web-insight-report.json")"
if [[ "$D05_SEEDED" != "SEED-RUN-1" ]]; then
    record D05 "Both paths over an existing report" FAIL \
        "PRECONDITION not met: the seed left testName '$D05_SEEDED'"
else
    WIR_RUNTIME_LOG="$D05/jmeter-runtime.log" \
    run_jmeter -n -t "$SINGLE" -l "$D05/work/run2.jtl" "${SHORT[@]}" \
        -e -Jjmeter.reportgenerator.outputdir="$D05_SHARED" \
        -Jwebinsight.report.output="$D05_SHARED" \
        -Jwebinsight.report.title=RUN-2 > "$D05/jmeter.log" 2>&1
    D05_NOTES=""
    dashboard_present "$D05_SHARED" \
        || D05_NOTES="$(append_note "$D05_NOTES" "the -e stage never ran, so the skip cannot be concluded")"
    D05_T="$(json_title "$D05_SHARED/web-insight-report.json")"
    [[ "$D05_T" == "RUN-2" ]] \
        || D05_NOTES="$(append_note "$D05_NOTES" "testName is '$D05_T' -- the listener did not overwrite the seed")"
    skipped "$D05/jmeter-runtime.log" \
        || D05_NOTES="$(append_note "$D05_NOTES" "no skip WARN from the exporter")"
    assert_log_contains "$D05/jmeter-runtime.log" "Overwriting an existing report" \
        || D05_NOTES="$(append_note "$D05_NOTES" "the listener overwrote the seed without the F2 WARN")"
    [[ "$(wir_html_count "$D05_SHARED")" -eq 1 ]] \
        || D05_NOTES="$(append_note "$D05_NOTES" "$(wir_html_count "$D05_SHARED") Web Insight reports, expected 1")"
    if [[ -z "$D05_NOTES" ]]; then
        record D05 "Both paths over an existing report" PASS \
            "listener overwrote (RUN-2, with the WARN), exporter then skipped -- net one report"
    else
        record D05 "Both paths over an existing report" FAIL "$D05_NOTES"
    fi
fi

# -----------------------------------------------------------------------------
# D06  Pre=clean. Both paths active, listener filename run-${timestamp}.html.
#      Expected: the exporter must match the STAMP the listener resolved, not the literal
#      placeholder, and skip instead of producing a second report (F3 + F8). This is the
#      case the resolved-filename handshake exists for: deriving the name from the raw
#      -J property would leave it hunting for a file called 'run-${timestamp}.html'.
# -----------------------------------------------------------------------------
echo "[D06] Both paths with a \${timestamp} filename..."
D06="$SUITE_DIR/D06"; mkdir -p "$D06"
D06_OUT="$D06/dash"
rm -rf "$D06_OUT"
WIR_RUNTIME_LOG="$D06/jmeter-runtime.log" \
run_jmeter -n -t "$SINGLE" -l "$D06/results.jtl" "${SHORT[@]}" \
    -e -o "$D06_OUT" \
    -Jwebinsight.report.output="$D06_OUT" \
    -Jwebinsight.report.filename='run-${timestamp}.html' \
    -Jwebinsight.report.title=RUN-2 > "$D06/jmeter.log" 2>&1
D06_NOTES=""
dashboard_present "$D06_OUT" \
    || D06_NOTES="$(append_note "$D06_NOTES" "the -e stage never ran")"
D06_N="$(count_glob "$D06_OUT"/run-*.html)"
[[ "$D06_N" -eq 1 ]] \
    || D06_NOTES="$(append_note "$D06_NOTES" "$D06_N stamped reports, expected exactly 1")"
file_absent "$D06_OUT/web-insight-report.html" \
    || D06_NOTES="$(append_note "$D06_NOTES" "a second, default-named report appeared -- the glob did not match the stamp")"
D06_JSON="$(count_glob "$D06_OUT"/run-*.json)"
if [[ "$D06_JSON" -eq 1 ]]; then
    D06_T="$(json_title "$(echo "$D06_OUT"/run-*.json)")"
    [[ "$D06_T" == "RUN-2" ]] \
        || D06_NOTES="$(append_note "$D06_NOTES" "testName was '$D06_T'")"
else
    D06_NOTES="$(append_note "$D06_NOTES" "$D06_JSON stamped .json siblings, expected 1")"
fi
skipped "$D06/jmeter-runtime.log" \
    || D06_NOTES="$(append_note "$D06_NOTES" "no skip WARN -- the exporter did not recognise the stamped name")"
if [[ -z "$D06_NOTES" ]]; then
    record D06 "Both paths with a stamped filename" PASS \
        "one stamped report (RUN-2); the exporter matched the resolved name and skipped"
else
    record D06 "Both paths with a stamped filename" FAIL "$D06_NOTES"
fi

print_summary "FAMILY C & D TEST SUMMARY"

exit $FAIL
