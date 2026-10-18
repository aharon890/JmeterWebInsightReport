#!/usr/bin/env bash
# =============================================================================
#  Concurrency Scenario Tests (E01-E07)
#
#  Two JMeter processes writing into ONE output directory at the same time.
#  Nothing else in the suite covers this: run-all-tests.sh runs 4-up but gives
#  every scenario its own test-results/<ID>/, which is precisely the discipline
#  these rows violate on purpose.
#
#  What is under test (see test-plans/SCENARIO-MATRIX.md):
#    F6  WRITTEN_THIS_JVM is a static Set -- it catches two listeners in ONE JVM
#        and is structurally blind across processes, so neither run warns.
#    F10 AtomicFileWriter stages at a DETERMINISTIC ".<name>.tmp" sibling, not a
#        process-unique one, so two writers share one staging file.
#    F7  HTML and payload are separate writes, so an interleaving can pair one
#        run's HTML with the other's chart data.
#
#  These rows are TIMING-DEPENDENT. A single clean run proves you missed the
#  window, not that the window is closed -- so each racing row loops, and the
#  report is a tally across iterations rather than a single pass/fail. Both
#  processes are released from a shared wall-clock barrier so the overlap is a
#  real race rather than startup jitter.
#
#  Assumes the plugin is already deployed (run ./scripts/build-and-deploy.sh).
# =============================================================================
set -uo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../../scripts/lib/common.sh"

require_jmeter
require_python || exit 1

# See run-title-junit-tests.sh: Python's stdout defaults to the console codepage on
# Windows and dies on non-ASCII, which silently turns a comparison into "".
export PYTHONIOENCODING=utf-8

# NOT "RESULTS": common.sh declares RESULTS=() as the results ARRAY record() appends to.
SUITE_DIR="$RESULTS_DIR/family-e"
PLAN="$TEST_PLANS/single-sampler-test.jmx"

ITER="${E_ITERATIONS:-10}"       # racing rows loop this many times
BARRIER_LEAD="${BARRIER_LEAD:-12}"  # seconds to allow both JVMs to reach the barrier

print_banner "Concurrency Scenario Tests (E01-E07)"
rm -rf "$SUITE_DIR"
mkdir -p "$SUITE_DIR"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

now_ms() { "$PYTHON" -c "import time;print(int(time.time()*1000))"; }

json_field() {
    "$PYTHON" -c "import json,sys
try:
    print(json.load(open(sys.argv[1],encoding='utf-8')).get(sys.argv[2],''))
except Exception:
    print('')" "$1" "$2" 2>/dev/null
}

html_title() {
    "$PYTHON" - "$1" <<'PY' 2>/dev/null
import re, sys, html
try:
    src = open(sys.argv[1], encoding='utf-8', errors='replace').read()
except Exception:
    print(''); raise SystemExit
m = re.search(r'<title>(.*?)</title>', src, re.S)
print(html.unescape(m.group(1)).strip() if m else '')
PY
}

# payload_field <web-insight-data.js> <key> -- read window.__WIR_DATA__={...}
payload_field() {
    "$PYTHON" - "$1" "$2" <<'PY' 2>/dev/null
import json, sys
try:
    src = open(sys.argv[1], encoding='utf-8', errors='replace').read()
except Exception:
    print(''); raise SystemExit
i = src.find('=')
try:
    print(json.loads(src[i+1:].strip().rstrip(';')).get(sys.argv[2], ''))
except Exception:
    print('')
PY
}

# race <dir> <labelA> <argsA...> -- launches two jmeter runs against one barrier.
# Callers pass two arg arrays via the globals RACE_A_ARGS / RACE_B_ARGS.
race() {
    local dir="$1"; shift
    local start_at; start_at=$(( $(now_ms) + BARRIER_LEAD * 1000 ))
    local pidA pidB

    (
        while (( $(now_ms) < start_at )); do :; done
        run_jmeter -n -t "$PLAN" -l "$dir/a.jtl" -Jthreads=1 -Jduration=3 -Jrampup=1 \
            "${RACE_A_ARGS[@]}" > "$dir/a.log" 2>&1
        echo $? > "$dir/a.exit"
    ) & pidA=$!
    (
        while (( $(now_ms) < start_at )); do :; done
        run_jmeter -n -t "$PLAN" -l "$dir/b.jtl" -Jthreads=1 -Jduration=3 -Jrampup=1 \
            "${RACE_B_ARGS[@]}" > "$dir/b.log" 2>&1
        echo $? > "$dir/b.exit"
    ) & pidB=$!

    wait $pidA $pidB
}

# =============================================================================
# E01 -- two runs, one directory, one default filename
# =============================================================================
echo "[E01] Two concurrent runs, same dir, same default name (x$ITER)..."
E01_CLEAN=0; E01_DAMAGED=0; E01_LOST=0; E01_WARN=0; E01_TMP=0
for i in $(seq 1 "$ITER"); do
    d="$SUITE_DIR/E01/iter$i"; mkdir -p "$d"
    RACE_A_ARGS=(-Jwebinsight.report.output="$d" -Jwebinsight.report.title=PROC-A)
    RACE_B_ARGS=(-Jwebinsight.report.output="$d" -Jwebinsight.report.title=PROC-B)
    race "$d"

    ht="$(html_title "$d/web-insight-report.html")"
    jt="$(json_field "$d/web-insight-report.json" testName)"
    [[ -n "$(ls -A "$d"/.*.tmp 2>/dev/null)" ]] && E01_TMP=$((E01_TMP+1))
    grep -qF "already been written in this JVM" "$d/a.log" "$d/b.log" 2>/dev/null && E01_WARN=$((E01_WARN+1))

    if [[ -z "$ht" ]]; then
        E01_LOST=$((E01_LOST+1))
    elif [[ -n "$jt" && "$ht" != "$jt" ]]; then
        E01_DAMAGED=$((E01_DAMAGED+1))
        echo "    iter$i: MISMATCH html='$ht' json='$jt'" >> "$SUITE_DIR/E01/findings.txt"
    else
        E01_CLEAN=$((E01_CLEAN+1))
    fi
done
E01_NOTE="$ITER iterations: clean last-wins=$E01_CLEAN, html/json from DIFFERENT runs=$E01_DAMAGED, no report at all=$E01_LOST; cross-process WARN fired $E01_WARN times (F6 predicts 0); stranded .tmp seen $E01_TMP times"
if (( E01_DAMAGED > 0 || E01_LOST > 0 )); then
    record E01 "Concurrent same-name writes" FAIL "$E01_NOTE"
else
    record E01 "Concurrent same-name writes" PASS "$E01_NOTE -- hazard not observed in this many iterations (absence of evidence only)"
fi

# =============================================================================
# E02 -- distinct filenames
# =============================================================================
echo "[E02] Two concurrent runs, same dir, DISTINCT filenames (x$ITER)..."
E02_OK=0; E02_BAD=0
for i in $(seq 1 "$ITER"); do
    d="$SUITE_DIR/E02/iter$i"; mkdir -p "$d"
    RACE_A_ARGS=(-Jwebinsight.report.output="$d" -Jwebinsight.report.filename=a.html -Jwebinsight.report.title=PROC-A)
    RACE_B_ARGS=(-Jwebinsight.report.output="$d" -Jwebinsight.report.filename=b.html -Jwebinsight.report.title=PROC-B)
    race "$d"
    if [[ "$(html_title "$d/a.html")" == "PROC-A" && "$(html_title "$d/b.html")" == "PROC-B" \
       && "$(json_field "$d/a.json" testName)" == "PROC-A" && "$(json_field "$d/b.json" testName)" == "PROC-B" ]]; then
        E02_OK=$((E02_OK+1))
    else
        E02_BAD=$((E02_BAD+1))
    fi
done
if (( E02_BAD == 0 )); then
    record E02 "Concurrent distinct filenames" PASS "$E02_OK/$ITER iterations produced two complete, correctly-attributed pairs"
else
    record E02 "Concurrent distinct filenames" FAIL "$E02_BAD/$ITER iterations were wrong despite distinct names"
fi

# =============================================================================
# E03 -- control: separate output directories
# =============================================================================
echo "[E03] CONTROL: two concurrent runs, SEPARATE dirs (x3)..."
E03_OK=0
for i in 1 2 3; do
    d="$SUITE_DIR/E03/iter$i"; mkdir -p "$d/a" "$d/b"
    RACE_A_ARGS=(-Jwebinsight.report.output="$d/a" -Jwebinsight.report.title=PROC-A)
    RACE_B_ARGS=(-Jwebinsight.report.output="$d/b" -Jwebinsight.report.title=PROC-B)
    race "$d"
    [[ "$(html_title "$d/a/web-insight-report.html")" == "PROC-A" \
    && "$(html_title "$d/b/web-insight-report.html")" == "PROC-B" ]] && E03_OK=$((E03_OK+1))
done
if (( E03_OK == 3 )); then
    record E03 "Control: separate dirs" PASS "3/3 fully independent -- the hazard is specific to a SHARED directory"
else
    record E03 "Control: separate dirs" FAIL "only $E03_OK/3 clean with separate dirs; something other than sharing is wrong"
fi

# =============================================================================
# E04 -- both in external-data mode, same default name
# =============================================================================
echo "[E04] Two concurrent runs, same name, BOTH external-data (x$ITER)..."
E04_CLEAN=0; E04_SKEW=0; E04_LOST=0
for i in $(seq 1 "$ITER"); do
    d="$SUITE_DIR/E04/iter$i"; mkdir -p "$d"
    RACE_A_ARGS=(-Jwebinsight.report.output="$d" -Jwebinsight.report.externaldata=true -Jwebinsight.report.title=PROC-A)
    RACE_B_ARGS=(-Jwebinsight.report.output="$d" -Jwebinsight.report.externaldata=true -Jwebinsight.report.title=PROC-B)
    race "$d"
    ht="$(html_title "$d/web-insight-report.html")"
    pt="$(payload_field "$d/web-insight-data.js" runTestName)"
    if [[ -z "$ht" ]]; then
        E04_LOST=$((E04_LOST+1))
    elif [[ -n "$pt" && "$ht" != "$pt" ]]; then
        E04_SKEW=$((E04_SKEW+1))
        echo "    iter$i: SKEW html='$ht' payload='$pt'" >> "$SUITE_DIR/E04/findings.txt"
    else
        E04_CLEAN=$((E04_CLEAN+1))
    fi
done
E04_NOTE="$ITER iterations: consistent=$E04_CLEAN, HTML and payload from DIFFERENT runs=$E04_SKEW, no report=$E04_LOST"
# What a skew observed here costs the reader has changed, and it is worth being precise about
# it. Under F11a the page still drew its charts and put a banner over them, so a skew was a
# report that was wrong but usable. Under F11b the identity check is fatal: the runId
# comparison fails, an error block naming both runs goes in at the top of <body>, every chart
# pane is rewritten to say the data belongs to a different test run, and initCharts is never
# called. So a FAIL on this row now means the race produced a report with NO charts at all --
# not one with the wrong charts and a warning above them.
if (( E04_SKEW > 0 || E04_LOST > 0 )); then
    record E04 "Concurrent external-data skew" FAIL "$E04_NOTE (F11b: the page refuses a skewed payload outright -- every chart withheld, not bannered)"
else
    record E04 "Concurrent external-data skew" PASS "$E04_NOTE -- skew not observed in this many iterations"
fi

# =============================================================================
# E05 -- one default-named, one custom-named, both external
# =============================================================================
echo "[E05] Concurrent external runs with different names (x3)..."
E05_OK=0
for i in 1 2 3; do
    d="$SUITE_DIR/E05/iter$i"; mkdir -p "$d"
    RACE_A_ARGS=(-Jwebinsight.report.output="$d" -Jwebinsight.report.externaldata=true -Jwebinsight.report.title=PROC-A)
    RACE_B_ARGS=(-Jwebinsight.report.output="$d" -Jwebinsight.report.externaldata=true -Jwebinsight.report.filename=custom.html -Jwebinsight.report.title=PROC-B)
    race "$d"
    [[ "$(html_title "$d/web-insight-report.html")" == "PROC-A" \
    && "$(html_title "$d/custom.html")" == "PROC-B" \
    && -f "$d/web-insight-data.js" && -f "$d/custom-data.js" ]] && E05_OK=$((E05_OK+1))
done
if (( E05_OK == 3 )); then
    record E05 "Concurrent external, distinct payload names" PASS "3/3 -- derived payload names (F5) keep the pairs apart"
else
    record E05 "Concurrent external, distinct payload names" FAIL "only $E05_OK/3 clean"
fi

# =============================================================================
# E06 -- concurrent listener and exporter
# =============================================================================
# Rewritten, because the row could not be judged as it stood.
#
# It counted `ls "$d"/*.html "$d/dash"/web-insight-report.html` — reports in TWO DIFFERENT
# directories, the listener's output dir and the exporter's -o. Two reports there is not a
# defect, it is the documented behaviour of two flags pointing at two places, and they were
# two different runs besides (a live run and a re-export of a seed JTL). So the assertion
# failed on every iteration and proved nothing either way. The requirements doc lists it as a
# known gap in the verification itself.
#
# What is actually worth probing is what the note already said: the check-then-act race is
# only reachable when both target ONE directory. So they do now. The listener writes its
# report at test end; the exporter asks "is this run's report already here?" at roughly the
# same moment. Under the identity-based exists-policy the two are DIFFERENT runs — a live run
# and a re-export of an unrelated seed — so the exporter must NOT skip, and last-writer-wins
# must leave exactly one internally consistent report rather than a mixed set.
echo "[E06] Concurrent listener (-n -t) and exporter (-g) on ONE dir (x3)..."
E06_CLEAN=0; E06_MIXED=0; E06_LOST=0; E06_TMP=0; E06_NOTES=""
for i in 1 2 3; do
    d="$SUITE_DIR/E06/iter$i"; mkdir -p "$d/src"
    # A JTL for the exporter to consume, produced up-front and kept out of the shared dir.
    run_jmeter -n -t "$PLAN" -l "$d/src/seed.jtl" -Jthreads=1 -Jduration=3 -Jrampup=1 \
        -Jwebinsight.report.output="$d/src" -Jwebinsight.report.title=SEEDGEN > "$d/seedgen.log" 2>&1

    # -o insists on an empty (or absent) directory at startup, so the shared target is created
    # by the run itself rather than pre-made.
    shared="$d/shared"
    rm -rf "$shared"
    start_at=$(( $(now_ms) + BARRIER_LEAD * 1000 ))
    (
        while (( $(now_ms) < start_at )); do :; done
        run_jmeter -n -t "$PLAN" -l "$d/live.jtl" -Jthreads=1 -Jduration=3 -Jrampup=1 \
            -Jwebinsight.report.output="$shared" -Jwebinsight.report.title=LISTENER > "$d/listener.log" 2>&1
    ) &
    lp=$!
    (
        while (( $(now_ms) < start_at )); do :; done
        run_jmeter -g "$d/src/seed.jtl" -o "$shared" \
            -Jwebinsight.report.title=EXPORTER > "$d/exporter.log" 2>&1
    ) &
    ep=$!
    wait $lp $ep

    ht="$(html_title "$shared/web-insight-report.html")"
    jt="$(json_field "$shared/web-insight-report.json" testName)"
    [[ -n "$(ls -A "$shared"/.*.tmp 2>/dev/null)" ]] && E06_TMP=$((E06_TMP+1))

    if [[ -z "$ht" ]]; then
        E06_LOST=$((E06_LOST+1))
        E06_NOTES="$(append_note "$E06_NOTES" "iter$i: no report survived in the shared dir")"
    elif [[ -n "$jt" && "$ht" != "$jt" ]]; then
        E06_MIXED=$((E06_MIXED+1))
        E06_NOTES="$(append_note "$E06_NOTES" "iter$i: html='$ht' json='$jt'")"
    else
        E06_CLEAN=$((E06_CLEAN+1))
    fi
done
E06_NOTE="3 iterations into ONE shared dir: internally consistent=$E06_CLEAN, html/json from DIFFERENT runs=$E06_MIXED, no report=$E06_LOST, stranded .tmp=$E06_TMP. Last-writer-wins is acceptable here (the listener's live run and the exporter's re-export are genuinely different runs, so the exists-policy must NOT skip); a mixed set is not."
if (( E06_MIXED > 0 || E06_LOST > 0 || E06_TMP > 0 )); then
    record E06 "Concurrent listener and exporter, one dir" FAIL "$E06_NOTE${E06_NOTES:+ -- $E06_NOTES}"
else
    record E06 "Concurrent listener and exporter, one dir" PASS "$E06_NOTE"
fi

# =============================================================================
# E07 -- stranded staging files
# =============================================================================
echo "[E07] Scanning every E-family directory for stranded .tmp files..."
STRANDED=$(find "$SUITE_DIR" -name ".*.tmp" 2>/dev/null | wc -l)
if (( STRANDED == 0 )); then
    record E07 "No stranded staging files" PASS \
        "0 stranded .<name>.tmp across the whole suite -- AtomicFileWriter's finally-deleteIfExists removes it even on the failure path, so a lost report leaves NO filesystem trace. It is no longer traceless altogether: failonerror defaults to true, so the run that lost it prints WEB-INSIGHT-REPORT: FAILED on stderr and exits non-zero. The log line in a file with no console appender is no longer the only channel"
else
    record E07 "No stranded staging files" FAIL \
        "$STRANDED stranded .tmp files left behind -- in a -g -o directory this turns the NEXT run into a startup failure"
fi

print_summary "CONCURRENCY TEST SUMMARY"

exit $FAIL
