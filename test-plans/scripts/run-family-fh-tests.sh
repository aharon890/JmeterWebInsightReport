#!/usr/bin/env bash
# =============================================================================
#  External-Data & Negative/Edge Scenario Tests (F01-F05, H01-H04, H06)
#
#  Families F and H. F06,
#  H05 and H07 are deliberately absent: they are Type=manual (a rendered page
#  judged by eye, a full disk, a SIGKILL) and cannot be decided headlessly.
#
#  Why these two families need their own runner rather than more C/N cases:
#
#  * Family F is the only place the report is written as TWO files that must
#    agree. The HTML and the chart payload are separate writes with nothing
#    between them (F7), so every interesting failure is a SKEW — the page
#    describing one run while its charts describe another — not a missing file.
#    Asserting "both files exist" is exactly the assertion that cannot see it.
#    So every F row here reads the title/runId out of BOTH artifacts and
#    compares them.
#
#  * Family H rows are defined by their PRECONDITION, not by their arguments.
#    "Baseline absent" and "a directory sitting where the report goes" are
#    states of the output folder; run them on a clean directory and they
#    quietly become a different, passing scenario. `clean_dir`, `carry_dir` and
#    `seed_dir` below assert the starting state instead of assuming it.
#
#  Assumes the plugin is already deployed (run ./scripts/build-and-deploy.sh).
# =============================================================================
set -uo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../../scripts/lib/common.sh"

require_jmeter
require_python || exit 1

# The helpers below print artifact contents back to the shell for comparison. On Windows
# Python's stdout defaults to the console codepage (cp1252), so printing anything outside
# it raises UnicodeEncodeError, the helper dies, and the caller silently compares against
# an empty string -- reporting a mangled value when the artifact on disk was correct.
export PYTHONIOENCODING=utf-8

RESULTS="$RESULTS_DIR/family-fh-tests"
SINGLE_JMX="$TEST_PLANS/single-sampler-test.jmx"
STRESS_JMX="$TEST_PLANS/report-stress-test.jmx"

DEFAULT_HTML="web-insight-report.html"
DEFAULT_JSON="web-insight-report.json"
DEFAULT_DATA="web-insight-data.js"
DEFAULT_THRESHOLD=10000000     # ReportConfiguration.externalDataThreshold

print_banner "External-Data & Negative/Edge Scenario Tests (F01-F05, H01-H04, H06)"

rm -rf "$RESULTS"
mkdir -p "$RESULTS"

# -----------------------------------------------------------------------------
#  Preconditions — asserted, never assumed
# -----------------------------------------------------------------------------

# clean_dir <dir> — Pre=clean. Remove, recreate, and PROVE it is empty.
# The emptiness is checked rather than trusted: a report left by an aborted earlier
# invocation is exactly the debris that makes a broken run look green.
clean_dir() {
    local dir="$1"
    rm -rf "$dir"
    mkdir -p "$dir"
    if [[ -n "$(ls -A "$dir" 2>/dev/null)" ]]; then
        echo "  [PRECONDITION] $dir is not empty at start -- results would be unsound" >&2
        return 1
    fi
}

# carry_dir <dir> — Pre=carry. The previous run's output MUST still be there; that
# leftover state is the scenario. An empty directory here means the setup run failed
# and the collision being tested cannot happen, so the row must not report on it.
carry_dir() {
    local dir="$1" must_contain="$2"
    if [[ ! -f "$dir/$must_contain" ]]; then
        echo "  [PRECONDITION] $dir/$must_contain is missing -- the carried state this row" >&2
        echo "                 depends on was never produced" >&2
        return 1
    fi
}

# seed_dir <dir> — Pre=seed. Clean first, then the caller plants the seed and calls
# seed_present to confirm it. Split in two so a seed that failed to appear is a
# precondition failure rather than a silently-different scenario.
seed_present() {
    local what="$1"
    if [[ ! -e "$what" ]]; then
        echo "  [PRECONDITION] seed $what was not created" >&2
        return 1
    fi
}

# -----------------------------------------------------------------------------
#  Running
# -----------------------------------------------------------------------------

# run_in <dir> <tag> <jmx> [extra jmeter args...]
#
# <tag> names the two logs, so a `carry` row's second run does not truncate the first
# run's evidence: JMeter's -j File appender is append=false, and every plugin WARN
# lands there and nowhere else (the default log4j2.xml has no console appender).
run_in() {
    local dir="$1" tag="$2" jmx="$3"; shift 3
    mkdir -p "$dir"
    WIR_RUNTIME_LOG="$dir/$tag-runtime.log" \
    run_jmeter -n -t "$jmx" -l "$dir/$tag.jtl" \
        -Jwebinsight.report.output="$dir" \
        "$@" > "$dir/$tag.log" 2>&1
}

# Short by default; only F01 needs a payload big enough to talk about.
SMALL_LOAD=(-Jthreads=2 -Jduration=4 -Jrampup=1)
BIG_LOAD=(-Jthreads=8 -Jduration=15 -Jrampup=2)

# -----------------------------------------------------------------------------
#  Reading the artifacts
# -----------------------------------------------------------------------------

# payload_field <web-insight-data.js> <key> — a top-level key of the external payload.
# The file is `window.__WIR_DATA__={...};` — one assignment, so everything after the
# first '=' minus the trailing ';' is the JSON. Parsed rather than grepped because the
# whole point of these rows is to compare an identity, and a substring match on a
# multi-megabyte file would happily find a title anywhere inside it.
payload_field() {
    "$PYTHON" - "$1" "$2" <<'PY' 2>/dev/null
import json, sys
src = open(sys.argv[1], encoding='utf-8', errors='replace').read()
i = src.find('=')
if i < 0:
    sys.exit(1)
body = src[i + 1:].strip()
if body.endswith(';'):
    body = body[:-1]
d = json.loads(body)
v = d.get(sys.argv[2], '')
print(v if isinstance(v, str) else json.dumps(v))
PY
}

payload_prefix_ok() { [[ -f "$1" ]] && head -c 20 "$1" 2>/dev/null | grep -qF 'window.__WIR_DATA__='; }

json_title() { "$PYTHON" -c "import json,sys;print(json.load(open(sys.argv[1],encoding='utf-8'))['testName'])" "$1" 2>/dev/null; }

html_title() {
    "$PYTHON" - "$1" <<'PY' 2>/dev/null
import re, sys, html
src = open(sys.argv[1], encoding='utf-8', errors='replace').read()
m = re.search(r'<title>(.*?)</title>', src, re.S)
print(html.unescape(m.group(1)).strip() if m else '')
PY
}

# html_run_id <file> — the runId Thymeleaf stamped into the page, which the template
# compares against the payload's own runId at load time. Empty when the report is not
# in external-data mode (the block is inside th:if="${externalDataMode}").
html_run_id() {
    "$PYTHON" - "$1" <<'PY' 2>/dev/null
import re, sys
src = open(sys.argv[1], encoding='utf-8', errors='replace').read()
m = re.search(r'var pageRunId = (.*?);', src)
print(m.group(1).strip().strip('"\'') if m else '')
PY
}

# inline_payload_len <file> — length of the inline chart-data literal, or 0 when the
# report is external (the literal is then "{}"). Used only to size F01's threshold.
inline_payload_len() {
    "$PYTHON" - "$1" <<'PY' 2>/dev/null
import re, sys
src = open(sys.argv[1], encoding='utf-8', errors='replace').read()
m = re.search(r'var chartData = (.*);', src)
print(len(m.group(1)) if m else 0)
PY
}

# inline_has_key <html> <key> — is <key> a top-level key of the INLINE chart payload?
#
# Not a grep. report.js is inlined into every report and reads chartData.comparisonDiffs
# by name, so `grep comparisonDiffs report.html` matches on every report ever produced —
# it reported comparison data present in a run with no baseline configured at all. The
# literal is a JSON-encoded string, so it is decoded twice: once to unwrap the JS string,
# once to parse the object.
inline_has_key() {
    "$PYTHON" - "$1" "$2" <<'PY' 2>/dev/null
import json, re, sys
src = open(sys.argv[1], encoding='utf-8', errors='replace').read()
m = re.search(r'var chartData = (.*);', src)
if not m:
    sys.exit(2)
lit = m.group(1).strip()
try:
    obj = json.loads(lit)
    if isinstance(obj, str):
        obj = json.loads(obj)
except ValueError:
    sys.exit(2)
sys.exit(0 if isinstance(obj, dict) and sys.argv[2] in obj else 1)
PY
}

# html_references <html> <payload name> — the <script src> the self-containment promise
# rests on. th:src renders a plain src attribute, so this is the rendered form.
html_references() { assert_contains "$1" "src=\"$2\""; }

# =============================================================================
#  F — external data
# =============================================================================

# -----------------------------------------------------------------------------
# F01: the payload naturally exceeds the threshold, default filename. Pre=clean.
#
# Two runs, because "naturally" has to be established rather than asserted. The first
# measures what a real listener run actually produces; the second re-runs the same plan
# against a threshold set BELOW that measurement, so the size branch
# (WebInsightReportGenerator:180) is the thing that trips — not the forcing boolean,
# which is F02's subject and takes a different code path entirely.
#
# The distinguishing evidence is the intent line: it is logged before the payload is
# serialized, so a threshold-driven run says "data=inline (external:… above N chars)"
# and still writes the payload. A forced run says "data=external:…" up front.
# -----------------------------------------------------------------------------
echo "[F01] Payload naturally exceeds the threshold (default filename)..."
F01_DIR="$RESULTS/F01"
F01_NOTES=""
if ! clean_dir "$F01_DIR/measure" || ! clean_dir "$F01_DIR/run"; then
    record F01 "Payload exceeds threshold" FAIL "precondition: output dir not clean"
else
    run_in "$F01_DIR/measure" measure "$STRESS_JMX" "${BIG_LOAD[@]}" \
        -Jwebinsight.report.title=F01-MEASURE
    F01_LEN=0
    if file_exists "$F01_DIR/measure/$DEFAULT_HTML"; then
        F01_LEN="$(inline_payload_len "$F01_DIR/measure/$DEFAULT_HTML")"
    fi
    if [[ "${F01_LEN:-0}" -lt 200 ]]; then
        record F01 "Payload exceeds threshold" FAIL \
            "the measuring run produced no inline payload to size a threshold against (len=$F01_LEN)"
    else
        # Half the inline literal's length. The literal is the JSON escaped as a JS string,
        # so it is never SHORTER than the JSON the threshold is compared against -- half of
        # it is therefore comfortably below the real payload size on any run of this plan.
        F01_T=$((F01_LEN / 2))
        run_in "$F01_DIR/run" run "$STRESS_JMX" "${BIG_LOAD[@]}" \
            -Jwebinsight.report.title=F01-NATURAL \
            -Jwebinsight.report.externaldata.threshold="$F01_T"

        F01_HTML="$F01_DIR/run/$DEFAULT_HTML"
        F01_DATA="$F01_DIR/run/$DEFAULT_DATA"
        file_exists "$F01_HTML" || F01_NOTES="$(append_note "$F01_NOTES" "no report written")"
        file_exists "$F01_DATA" || F01_NOTES="$(append_note "$F01_NOTES" "$DEFAULT_DATA not written")"
        payload_prefix_ok "$F01_DATA" \
            || F01_NOTES="$(append_note "$F01_NOTES" "payload does not start with window.__WIR_DATA__=")"
        html_references "$F01_HTML" "$DEFAULT_DATA" \
            || F01_NOTES="$(append_note "$F01_NOTES" "HTML has no <script src=\"$DEFAULT_DATA\">")"
        # The size branch, not the forced one.
        assert_log_contains "$F01_DIR/run/run-runtime.log" "data=inline (external:$DEFAULT_DATA above $F01_T chars)" \
            || F01_NOTES="$(append_note "$F01_NOTES" "intent line did not show the size branch at threshold $F01_T")"
        assert_log_contains "$F01_DIR/run/run-runtime.log" "External data written to" \
            || F01_NOTES="$(append_note "$F01_NOTES" "nothing logged about writing the payload")"

        F01_RATIO=$((DEFAULT_THRESHOLD / F01_LEN))
        if [[ -z "$F01_NOTES" ]]; then
            record F01 "Payload exceeds threshold" PASS \
                "size branch wrote $DEFAULT_DATA and the HTML loads it via <script src>; an 8-thread 15s run inlines ~$F01_LEN chars, so the shipped $DEFAULT_THRESHOLD default needs a run ~${F01_RATIO}x larger"
        else
            record F01 "Payload exceeds threshold" FAIL "$F01_NOTES"
        fi
    fi
fi

# -----------------------------------------------------------------------------
# F02: -Jwebinsight.report.externaldata=true forced on a small run. Pre=clean.
# Same artifact set as F01, reached without ever materialising the payload as a String.
# -----------------------------------------------------------------------------
echo "[F02] Forced external-data mode on a small run..."
F02_DIR="$RESULTS/F02"
F02_NOTES=""
if ! clean_dir "$F02_DIR"; then
    record F02 "Forced external-data mode" FAIL "precondition: output dir not clean"
else
    run_in "$F02_DIR" run "$SINGLE_JMX" "${SMALL_LOAD[@]}" \
        -Jwebinsight.report.title=F02-FORCED \
        -Jwebinsight.report.externaldata=true

    F02_HTML="$F02_DIR/$DEFAULT_HTML"
    F02_DATA="$F02_DIR/$DEFAULT_DATA"
    file_exists "$F02_HTML" || F02_NOTES="$(append_note "$F02_NOTES" "no report written")"
    file_exists "$F02_DIR/$DEFAULT_JSON" || F02_NOTES="$(append_note "$F02_NOTES" "no .json sibling")"
    file_exists "$F02_DATA" || F02_NOTES="$(append_note "$F02_NOTES" "$DEFAULT_DATA not written")"
    payload_prefix_ok "$F02_DATA" \
        || F02_NOTES="$(append_note "$F02_NOTES" "payload does not start with window.__WIR_DATA__=")"
    html_references "$F02_HTML" "$DEFAULT_DATA" \
        || F02_NOTES="$(append_note "$F02_NOTES" "HTML has no <script src=\"$DEFAULT_DATA\">")"
    # Forced runs declare external mode BEFORE serialising anything -- that is the streaming
    # path. A run that reached external mode through the size check would say "inline (…)".
    assert_log_contains "$F02_DIR/run-runtime.log" "data=external:$DEFAULT_DATA" \
        || F02_NOTES="$(append_note "$F02_NOTES" "intent line did not declare external mode up front")"
    F02_PT="$(payload_field "$F02_DATA" runTestName)"
    [[ "$F02_PT" == "F02-FORCED" ]] \
        || F02_NOTES="$(append_note "$F02_NOTES" "payload runTestName was '$F02_PT'")"

    if [[ -z "$F02_NOTES" ]]; then
        record F02 "Forced external-data mode" PASS \
            "external declared up front; html + json + $DEFAULT_DATA, payload names its own run"
    else
        record F02 "Forced external-data mode" FAIL "$F02_NOTES"
    fi
fi

# -----------------------------------------------------------------------------
# F03: two external reports in one directory with DISTINCT filenames. Pre=carry.
#
# The payload name derives from the HTML name (F5), so a.html must own a-data.js and
# b.html must own b-data.js. Run 2 deliberately inherits run 1's directory: cleaning
# between them destroys the only thing this row is about.
# -----------------------------------------------------------------------------
echo "[F03] Two external reports, distinct filenames, one directory..."
F03_DIR="$RESULTS/F03"
F03_NOTES=""
if ! clean_dir "$F03_DIR"; then
    record F03 "Distinct names do not share a payload" FAIL "precondition: output dir not clean"
else
    run_in "$F03_DIR" a "$SINGLE_JMX" "${SMALL_LOAD[@]}" \
        -Jwebinsight.report.filename=a.html \
        -Jwebinsight.report.title=RUN-A \
        -Jwebinsight.report.externaldata=true
    if ! carry_dir "$F03_DIR" a.html; then
        record F03 "Distinct names do not share a payload" FAIL \
            "precondition: run 1 produced no a.html, so there is nothing for run 2 to carry"
    else
        run_in "$F03_DIR" b "$SINGLE_JMX" "${SMALL_LOAD[@]}" \
            -Jwebinsight.report.filename=b.html \
            -Jwebinsight.report.title=RUN-B \
            -Jwebinsight.report.externaldata=true

        for f in a.html a-data.js b.html b-data.js; do
            file_exists "$F03_DIR/$f" || F03_NOTES="$(append_note "$F03_NOTES" "$f missing")"
        done
        file_absent "$F03_DIR/$DEFAULT_DATA" \
            || F03_NOTES="$(append_note "$F03_NOTES" "a shared $DEFAULT_DATA was written as well")"
        F03_A="$(payload_field "$F03_DIR/a-data.js" runTestName)"
        F03_B="$(payload_field "$F03_DIR/b-data.js" runTestName)"
        [[ "$F03_A" == "RUN-A" ]] || F03_NOTES="$(append_note "$F03_NOTES" "a-data.js describes '$F03_A'")"
        [[ "$F03_B" == "RUN-B" ]] || F03_NOTES="$(append_note "$F03_NOTES" "b-data.js describes '$F03_B'")"
        html_references "$F03_DIR/a.html" "a-data.js" \
            || F03_NOTES="$(append_note "$F03_NOTES" "a.html does not reference a-data.js")"
        html_references "$F03_DIR/b.html" "b-data.js" \
            || F03_NOTES="$(append_note "$F03_NOTES" "b.html does not reference b-data.js")"
        # Run 1 must be untouched: its HTML still says RUN-A after run 2 wrote alongside it.
        F03_AT="$(html_title "$F03_DIR/a.html")"
        [[ "$F03_AT" == "RUN-A" ]] || F03_NOTES="$(append_note "$F03_NOTES" "a.html's title became '$F03_AT'")"

        if [[ -z "$F03_NOTES" ]]; then
            record F03 "Distinct names do not share a payload" PASS \
                "a.html/a-data.js and b.html/b-data.js each describe their own run; no sharing"
        else
            record F03 "Distinct names do not share a payload" FAIL "$F03_NOTES"
        fi
    fi
fi

# -----------------------------------------------------------------------------
# F04: two DEFAULT-named external reports in one directory, sequential. Pre=carry.
#
# The row E04 chases without needing concurrency. Both runs resolve to
# web-insight-report.html and web-insight-data.js, so run 2 overwrites both -- and the
# question is whether the two writes stay in step. The report title is the provenance
# marker (SCENARIO-MATRIX.md, "Seeds must be distinguishable"): run 1 is RUN-1, run 2 is
# RUN-2, and afterwards the HTML and the payload must BOTH say RUN-2. A mismatch is a
# page whose header cards describe one run and whose charts describe another.
#
# runId is checked alongside the title because it is what the report itself compares at
# load time, and the two can disagree in either direction. That disagreement stopped being
# cosmetic: under F11b the identity check is fatal, so a title match with a runId mismatch
# is a page that withholds every one of its charts over a skew that is not there. The title
# says the HTML and the payload came out of the same run; the ids are what the browser will
# act on, and acting on them wrongly costs the whole report rather than adding a banner to it.
# -----------------------------------------------------------------------------
echo "[F04] Two default-named external reports, sequential, in one directory..."
F04_DIR="$RESULTS/F04"
F04_NOTES=""
if ! clean_dir "$F04_DIR"; then
    record F04 "Default-named external reports collide" FAIL "precondition: output dir not clean"
else
    run_in "$F04_DIR" run1 "$SINGLE_JMX" "${SMALL_LOAD[@]}" \
        -Jwebinsight.report.title=RUN-1 \
        -Jwebinsight.report.externaldata=true

    F04_HTML="$F04_DIR/$DEFAULT_HTML"
    F04_DATA="$F04_DIR/$DEFAULT_DATA"
    if ! carry_dir "$F04_DIR" "$DEFAULT_HTML" || ! carry_dir "$F04_DIR" "$DEFAULT_DATA"; then
        record F04 "Default-named external reports collide" FAIL \
            "precondition: run 1 left no report+payload for run 2 to overwrite"
    else
        F04_T1="$(html_title "$F04_HTML")"
        F04_P1="$(payload_field "$F04_DATA" runTestName)"

        run_in "$F04_DIR" run2 "$SINGLE_JMX" "${SMALL_LOAD[@]}" \
            -Jwebinsight.report.title=RUN-2 \
            -Jwebinsight.report.externaldata=true

        F04_T2="$(html_title "$F04_HTML")"
        F04_P2="$(payload_field "$F04_DATA" runTestName)"
        F04_HID="$(html_run_id "$F04_HTML")"
        F04_PID="$(payload_field "$F04_DATA" runId)"

        # Both runs shared ONE payload, as the row expects -- so exactly one .js file, and it
        # is the default name. (A *-data.js glob cannot express this: web-insight-data.js
        # matches it, so the check would pass on any outcome.)
        F04_JS="$(count_glob "$F04_DIR"/*.js)"
        [[ "$F04_JS" -eq 1 && -f "$F04_DATA" ]] \
            || F04_NOTES="$(append_note "$F04_NOTES" "expected exactly one payload ($DEFAULT_DATA), found $F04_JS .js file(s)")"
        [[ "$F04_T1" == "RUN-1" ]] \
            || F04_NOTES="$(append_note "$F04_NOTES" "run 1's HTML said '$F04_T1', not RUN-1 -- the marker never took")"
        [[ "$F04_P1" == "RUN-1" ]] \
            || F04_NOTES="$(append_note "$F04_NOTES" "run 1's payload said '$F04_P1'")"
        [[ "$F04_T2" == "RUN-2" ]] \
            || F04_NOTES="$(append_note "$F04_NOTES" "after run 2 the HTML says '$F04_T2'")"
        [[ "$F04_P2" == "RUN-2" ]] \
            || F04_NOTES="$(append_note "$F04_NOTES" "after run 2 the payload says '$F04_P2'")"
        if [[ -n "$F04_HID" && -n "$F04_PID" && "$F04_HID" != "$F04_PID" ]]; then
            F04_NOTES="$(append_note "$F04_NOTES" "runId skew: page '$F04_HID' vs payload '$F04_PID' -- the report would withhold every chart (F11b)")"
        fi
        [[ -n "$F04_HID" ]] || F04_NOTES="$(append_note "$F04_NOTES" "the page carries no runId to compare against")"

        if [[ -z "$F04_NOTES" ]]; then
            record F04 "Default-named external reports collide" PASS \
                "run 2 overwrote both files together: HTML and $DEFAULT_DATA both say RUN-2 and share runId $F04_PID -- no skew sequentially"
        else
            record F04 "Default-named external reports collide" FAIL "$F04_NOTES"
        fi
    fi
fi

# -----------------------------------------------------------------------------
# F05: is the external-data THRESHOLD configurable from -J? Pre=clean.
#
# F12 records this as "no such property, only the forcing boolean -- verify". Verifying
# it needs three probes, because "the property does nothing" and "the property was
# rejected" look identical from the artifacts alone:
#   a) a small value must move the switch point AND flip a small run to external
#   b) a non-numeric value must be refused, not silently parsed as 0 (which would force
#      external mode on every run, empty ones included)
#   c) <= 0 must be refused for the same reason
# The intent line carries the threshold in force, so (b) and (c) are decidable even
# though their artifacts look exactly like an unconfigured run's.
# -----------------------------------------------------------------------------
echo "[F05] External-data threshold via -J (F12 says it does not exist)..."
F05_DIR="$RESULTS/F05"
F05_NOTES=""
F05_EXISTS=no
if ! clean_dir "$F05_DIR/low" || ! clean_dir "$F05_DIR/junk" || ! clean_dir "$F05_DIR/zero"; then
    record F05 "External-data threshold property" FAIL "precondition: output dir not clean"
else
    run_in "$F05_DIR/low" low "$SINGLE_JMX" "${SMALL_LOAD[@]}" \
        -Jwebinsight.report.title=F05-LOW \
        -Jwebinsight.report.externaldata.threshold=500
    run_in "$F05_DIR/junk" junk "$SINGLE_JMX" "${SMALL_LOAD[@]}" \
        -Jwebinsight.report.title=F05-JUNK \
        -Jwebinsight.report.externaldata.threshold=not-a-number
    run_in "$F05_DIR/zero" zero "$SINGLE_JMX" "${SMALL_LOAD[@]}" \
        -Jwebinsight.report.title=F05-ZERO \
        -Jwebinsight.report.externaldata.threshold=0

    # Never conclude "the property is absent" from a run that never produced a report.
    for sub in low junk zero; do
        file_exists "$F05_DIR/$sub/$DEFAULT_HTML" \
            || F05_NOTES="$(append_note "$F05_NOTES" "$sub: no report produced -- nothing can be concluded")"
    done

    if [[ -z "$F05_NOTES" ]]; then
        if assert_log_contains "$F05_DIR/low/low-runtime.log" "above 500 chars"; then
            F05_EXISTS=yes
        fi
        if [[ "$F05_EXISTS" == "no" ]]; then
            record F05 "External-data threshold property" FAIL \
                "webinsight.report.externaldata.threshold had no effect: the intent line still shows the built-in default -- F12's doc/feature gap is real"
        else
            file_exists "$F05_DIR/low/$DEFAULT_DATA" \
                || F05_NOTES="$(append_note "$F05_NOTES" "threshold 500 was accepted but the payload stayed inline")"
            assert_log_contains "$F05_DIR/junk/junk-runtime.log" "Ignoring invalid webinsight.report.externaldata.threshold" \
                || F05_NOTES="$(append_note "$F05_NOTES" "a non-numeric threshold was not reported")"
            assert_log_contains "$F05_DIR/junk/junk-runtime.log" "above $DEFAULT_THRESHOLD chars" \
                || F05_NOTES="$(append_note "$F05_NOTES" "a non-numeric threshold did not fall back to the default")"
            assert_log_contains "$F05_DIR/zero/zero-runtime.log" "it must be greater than 0" \
                || F05_NOTES="$(append_note "$F05_NOTES" "threshold 0 was not rejected")"
            file_absent "$F05_DIR/zero/$DEFAULT_DATA" \
                || F05_NOTES="$(append_note "$F05_NOTES" "threshold 0 forced external mode on a tiny run")"

            if [[ -z "$F05_NOTES" ]]; then
                record F05 "External-data threshold property" PASS \
                    "-Jwebinsight.report.externaldata.threshold EXISTS and works (500 flipped a 4s run to external; 'not-a-number' and 0 are refused with a warning and keep $DEFAULT_THRESHOLD) -- F12 is stale"
            else
                record F05 "External-data threshold property" FAIL "$F05_NOTES"
            fi
        fi
    else
        record F05 "External-data threshold property" FAIL "$F05_NOTES"
    fi
fi

# =============================================================================
#  H — negative and edge
# =============================================================================

# -----------------------------------------------------------------------------
# H01: near-zero samples, twice into one shared directory. Pre=carry.
# A valid report both times, and run 2 overwrites run 1 (F2). The title separates
# "overwrote" from "skipped", which mtimes cannot.
# -----------------------------------------------------------------------------
echo "[H01] Near-zero samples, repeated into one directory..."
H01_DIR="$RESULTS/H01"
H01_NOTES=""
if ! clean_dir "$H01_DIR"; then
    record H01 "Near-zero samples, repeated" FAIL "precondition: output dir not clean"
else
    run_in "$H01_DIR" run1 "$SINGLE_JMX" -Jthreads=1 -Jduration=1 -Jrampup=1 \
        -Jwebinsight.report.title=RUN-1
    if ! carry_dir "$H01_DIR" "$DEFAULT_HTML"; then
        record H01 "Near-zero samples, repeated" FAIL \
            "precondition: run 1 wrote no report, so run 2 has nothing to overwrite"
    else
        H01_T1="$(html_title "$H01_DIR/$DEFAULT_HTML")"
        H01_S1="$(stat_size "$H01_DIR/$DEFAULT_HTML")"
        run_in "$H01_DIR" run2 "$SINGLE_JMX" -Jthreads=1 -Jduration=1 -Jrampup=1 \
            -Jwebinsight.report.title=RUN-2
        H01_T2="$(html_title "$H01_DIR/$DEFAULT_HTML")"

        [[ "$H01_T1" == "RUN-1" ]] || H01_NOTES="$(append_note "$H01_NOTES" "run 1's title was '$H01_T1'")"
        [[ "$H01_S1" -gt 1000 ]] || H01_NOTES="$(append_note "$H01_NOTES" "run 1's report is only $H01_S1 bytes")"
        [[ "$H01_T2" == "RUN-2" ]] \
            || H01_NOTES="$(append_note "$H01_NOTES" "after run 2 the title is '$H01_T2' -- run 2 did not overwrite")"
        file_exists "$H01_DIR/$DEFAULT_JSON" || H01_NOTES="$(append_note "$H01_NOTES" "no .json")"
        [[ "$(json_title "$H01_DIR/$DEFAULT_JSON")" == "RUN-2" ]] \
            || H01_NOTES="$(append_note "$H01_NOTES" ".json did not follow the HTML to RUN-2")"
        assert_log_contains "$H01_DIR/run2-runtime.log" "Overwriting an existing report" \
            || H01_NOTES="$(append_note "$H01_NOTES" "the overwrite was silent -- F2 says it warns")"

        if [[ -z "$H01_NOTES" ]]; then
            record H01 "Near-zero samples, repeated" PASS \
                "both runs produced a valid report; run 2 overwrote run 1 and said so"
        else
            record H01 "Near-zero samples, repeated" FAIL "$H01_NOTES"
        fi
    fi
fi

# -----------------------------------------------------------------------------
# H02: malformed annotations JSON. Pre=seed.
# The broken file IS the scenario, so it is planted and its presence confirmed before
# the run. Explicitly configured rather than auto-detected: N02 already covers the
# auto-detected location, and an explicit path cannot be quietly ignored.
# -----------------------------------------------------------------------------
echo "[H02] Malformed annotations file..."
H02_DIR="$RESULTS/H02"
H02_NOTES=""
if ! clean_dir "$H02_DIR"; then
    record H02 "Malformed annotations JSON" FAIL "precondition: output dir not clean"
else
    printf '%s\n' '{ "assertions": [ this is not JSON' > "$H02_DIR/broken-annotations.json"
    if ! seed_present "$H02_DIR/broken-annotations.json"; then
        record H02 "Malformed annotations JSON" FAIL "precondition: the malformed seed was not written"
    else
        run_in "$H02_DIR" run "$SINGLE_JMX" "${SMALL_LOAD[@]}" \
            -Jwebinsight.report.title=H02-BADJSON \
            -Jwebinsight.report.annotations="$H02_DIR/broken-annotations.json"

        file_exists "$H02_DIR/$DEFAULT_HTML" \
            || H02_NOTES="$(append_note "$H02_NOTES" "no report -- the run did not continue")"
        file_size_gt "$H02_DIR/$DEFAULT_HTML" 1000 \
            || H02_NOTES="$(append_note "$H02_NOTES" "report is suspiciously small")"
        file_exists "$H02_DIR/$DEFAULT_JSON" || H02_NOTES="$(append_note "$H02_NOTES" "no .json")"
        [[ "$(html_title "$H02_DIR/$DEFAULT_HTML")" == "H02-BADJSON" ]] \
            || H02_NOTES="$(append_note "$H02_NOTES" "the report is not this run's")"
        assert_log_contains "$H02_DIR/run-runtime.log" "the annotations file could not be read" \
            || H02_NOTES="$(append_note "$H02_NOTES" "the parse failure was never reported")"

        if [[ -z "$H02_NOTES" ]]; then
            record H02 "Malformed annotations JSON" PASS \
                "parse failure logged and the run continued to a full report"
        else
            record H02 "Malformed annotations JSON" FAIL "$H02_NOTES"
        fi
    fi
fi

# -----------------------------------------------------------------------------
# H03: an explicitly configured baseline that does not exist. Pre=clean, baseline ABSENT.
#
# The absence IS the precondition, so it is asserted: a stray file at that path would
# turn this into an ordinary comparison run that passes for the wrong reason.
# "No Compare tab" is checked in the payload rather than the markup -- the tab button is
# always present in the template and unhidden by report.js only when comparison data
# arrives, so the absence of comparisonDiffs is what actually decides it.
# -----------------------------------------------------------------------------
echo "[H03] Configured baseline that does not exist..."
H03_DIR="$RESULTS/H03"
H03_NOTES=""
H03_BASELINE="$H03_DIR/no-such-baseline.jtl"
if ! clean_dir "$H03_DIR"; then
    record H03 "Missing configured baseline" FAIL "precondition: output dir not clean"
elif [[ -e "$H03_BASELINE" ]]; then
    record H03 "Missing configured baseline" FAIL "precondition: the baseline is supposed to be ABSENT"
else
    run_in "$H03_DIR" run "$SINGLE_JMX" "${SMALL_LOAD[@]}" \
        -Jwebinsight.report.title=H03-NOBASELINE \
        -Jwebinsight.report.baseline="$H03_BASELINE"

    file_exists "$H03_DIR/$DEFAULT_HTML" \
        || H03_NOTES="$(append_note "$H03_NOTES" "no report produced")"
    file_size_gt "$H03_DIR/$DEFAULT_HTML" 1000 \
        || H03_NOTES="$(append_note "$H03_NOTES" "report is suspiciously small")"
    assert_log_contains "$H03_DIR/run-runtime.log" "Baseline JTL not found, comparison skipped" \
        || H03_NOTES="$(append_note "$H03_NOTES" "the missing baseline was never reported")"
    if file_exists "$H03_DIR/$DEFAULT_HTML"; then
        # Positive control first. "comparisonDiffs is absent" is an absence assertion, and a
        # helper that answered "absent" to everything -- a changed literal, a parse failure --
        # would satisfy it for free. runId is in every payload, so proving the helper finds
        # THAT is what makes the next answer mean anything.
        if ! inline_has_key "$H03_DIR/$DEFAULT_HTML" runId; then
            H03_NOTES="$(append_note "$H03_NOTES" "cannot read the inline payload at all -- 'no comparison data' would be unproven")"
        else
            inline_has_key "$H03_DIR/$DEFAULT_HTML" comparisonDiffs \
                && H03_NOTES="$(append_note "$H03_NOTES" "comparison data reached the payload despite no baseline")"
        fi
    fi

    if [[ -z "$H03_NOTES" ]]; then
        record H03 "Missing configured baseline" PASS \
            "WARN 'Baseline JTL not found, comparison skipped' logged; report written with no comparison data"
    else
        record H03 "Missing configured baseline" FAIL "$H03_NOTES"
    fi
fi

# -----------------------------------------------------------------------------
# H04: a DIRECTORY already sitting at the report path. Pre=seed.
#
# AtomicFileWriter stages a temp sibling and moves it onto the target, so the failure
# lands on the move -- after the render, before the .json (F7). What must hold: a clear
# error, no half-written artifacts, and no stranded .<name>.tmp (one leftover temp file
# turns the NEXT -g -o run into a startup failure, because JMeter refuses a non-empty -o).
#
# And now the exit code as well. This row used to report it rather than assert it, on the
# reasoning that JMeter owns the status and a listener aborting a whole test run over a
# report would be worse -- so it recorded "it stays 0" in the note for anyone scripting on
# `$?`. That is no longer true and no longer the trade: webinsight.report.failonerror
# defaults to true, the status is set from a shutdown hook rather than by aborting the run,
# and a run that produced NO report must not exit 0.
# -----------------------------------------------------------------------------
echo "[H04] A directory occupies the report path..."
H04_DIR="$RESULTS/H04"
H04_NOTES=""
if ! clean_dir "$H04_DIR"; then
    record H04 "Directory at the report path" FAIL "precondition: output dir not clean"
else
    mkdir -p "$H04_DIR/blocked.html"
    if ! seed_present "$H04_DIR/blocked.html" || [[ ! -d "$H04_DIR/blocked.html" ]]; then
        record H04 "Directory at the report path" BLOCKED \
            "could not create a directory named blocked.html on this filesystem"
    else
        run_in "$H04_DIR" run "$SINGLE_JMX" "${SMALL_LOAD[@]}" \
            -Jwebinsight.report.title=H04-BLOCKED \
            -Jwebinsight.report.filename=blocked.html
        H04_RC=$?

        # The seed must still be a directory: silently replacing it would be worse than failing.
        [[ -d "$H04_DIR/blocked.html" ]] \
            || H04_NOTES="$(append_note "$H04_NOTES" "the seeded directory was replaced")"
        # No partial artifacts: the .json/.xml are written after the HTML, so neither may exist.
        file_absent "$H04_DIR/blocked.json" \
            || H04_NOTES="$(append_note "$H04_NOTES" "a .json was written even though the HTML never landed")"
        [[ "$(count_glob "$H04_DIR"/.*.tmp)" -eq 0 ]] \
            || H04_NOTES="$(append_note "$H04_NOTES" "a staging .tmp was stranded in the output directory")"
        # A CLEAR error, not merely a mention of the path -- the intent line names the report
        # on every run, successful or not, so "the log contains blocked.html" is satisfied by a
        # run that worked perfectly. Require the failure itself, on the console as well as in
        # JMeter's log: the default log4j2.xml has no console appender, so an operator watching
        # stdout sees only what the plugin prints itself.
        assert_log_contains "$H04_DIR/run-runtime.log" "Failed to generate Web Insight Report" \
            || H04_NOTES="$(append_note "$H04_NOTES" "the generation failure was never logged")"
        assert_contains "$H04_DIR/run.log" "WEB-INSIGHT-REPORT: FAILED" \
            || H04_NOTES="$(append_note "$H04_NOTES" "nothing on the console said the report failed")"
        # No report was produced, so the run has to say so where a pipeline can see it.
        [[ "$H04_RC" -ne 0 ]] \
            || H04_NOTES="$(append_note "$H04_NOTES" "no report was produced and jmeter still exited 0 -- the failonerror default did not fire")"

        if [[ -z "$H04_NOTES" ]]; then
            record H04 "Directory at the report path" PASS \
                "write refused with AccessDeniedException on the staged .tmp move; seed still a directory, no .json, no stranded .tmp, error on console and in the log, and jmeter exited $H04_RC"
        else
            record H04 "Directory at the report path" FAIL "$H04_NOTES"
        fi
    fi
fi

# -----------------------------------------------------------------------------
# H06: a report named web-insight-data.js. Pre=clean.
#
# Extension handling replaces a .js suffix rather than stacking .html on top of it, so
# the report becomes web-insight-data.html and its payload web-insight-data-data.js --
# the collision should be impossible by construction. Run FORCED so the payload actually
# exists; without it there is nothing to collide with and the row proves nothing.
# -----------------------------------------------------------------------------
echo "[H06] Report named web-insight-data.js..."
H06_DIR="$RESULTS/H06"
H06_NOTES=""
if ! clean_dir "$H06_DIR"; then
    record H06 "Report named web-insight-data.js" FAIL "precondition: output dir not clean"
else
    run_in "$H06_DIR" run "$SINGLE_JMX" "${SMALL_LOAD[@]}" \
        -Jwebinsight.report.title=H06-COLLIDE \
        -Jwebinsight.report.filename=web-insight-data.js \
        -Jwebinsight.report.externaldata=true

    H06_HTML="$H06_DIR/web-insight-data.html"
    H06_DATA="$H06_DIR/web-insight-data-data.js"
    file_exists "$H06_HTML" \
        || H06_NOTES="$(append_note "$H06_NOTES" "no web-insight-data.html -- the .js suffix was not replaced")"
    file_exists "$H06_DATA" \
        || H06_NOTES="$(append_note "$H06_NOTES" "no web-insight-data-data.js payload")"
    file_exists "$H06_DIR/web-insight-data.json" \
        || H06_NOTES="$(append_note "$H06_NOTES" "no .json sibling")"
    # The name that could have collided must not exist at all.
    file_absent "$H06_DIR/$DEFAULT_DATA" \
        || H06_NOTES="$(append_note "$H06_NOTES" "$DEFAULT_DATA exists -- report and payload collided")"
    if file_exists "$H06_HTML"; then
        [[ "$(html_title "$H06_HTML")" == "H06-COLLIDE" ]] \
            || H06_NOTES="$(append_note "$H06_NOTES" "the report is not this run's")"
        html_references "$H06_HTML" "web-insight-data-data.js" \
            || H06_NOTES="$(append_note "$H06_NOTES" "the HTML does not reference its own payload")"
    fi
    if file_exists "$H06_DATA"; then
        payload_prefix_ok "$H06_DATA" \
            || H06_NOTES="$(append_note "$H06_NOTES" "payload does not start with window.__WIR_DATA__=")"
    fi

    if [[ -z "$H06_NOTES" ]]; then
        record H06 "Report named web-insight-data.js" PASS \
            "resolved to web-insight-data.html + web-insight-data-data.js; the collision is impossible by construction"
    else
        record H06 "Report named web-insight-data.js" FAIL "$H06_NOTES"
    fi
fi

print_summary "FAMILY F & H SCENARIO SUMMARY (F01-F05, H01-H04, H06)"

exit $FAIL
