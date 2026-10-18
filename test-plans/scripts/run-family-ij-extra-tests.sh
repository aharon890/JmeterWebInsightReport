#!/usr/bin/env bash
# =============================================================================
#  Family I & J — the rows run-title-junit-tests.sh cannot reach (I34-I36, J30-J34)
#
#  T01-T29 cover the title and the JUnit flag on ONE entry point: the listener,
#  under `-n -t`, into a directory that starts empty. Eight rows of the matrix ask
#  questions that shape cannot answer:
#
#    I34/I35  the `-g` EXPORTER path. It runs outside the test plan, so there is no
#             test element and no evaluated JMX field — only `-J`. F14 says a `-J`
#             value is a raw string, so a function reference must land literally;
#             this is the only path where that is the ONLY thing that can happen.
#    I36      both paths in one JVM (`-n -t … -e -o`). The listener evaluates its
#             JMX field (F15) and writes; the exporter must then find that report
#             and skip (F8). The surviving artifact must carry the EVALUATED title.
#    J30-J34  JUnit XML beyond "was a file written": a shared folder reused across
#             runs, a ${timestamp} name shared with the HTML, a run with no samples,
#             an .xml path that cannot be written, and a gate that actually FAILS.
#
#  Preconditions are part of these tests, not hygiene (SCENARIO-MATRIX.md):
#    clean  — removed, recreated, and PROVEN empty before the run
#    carry  — J30 deliberately reuses run 1's directory; cleaning would delete the
#             collision being tested
#    seed   — J33 pre-populates the .xml path; on a clean directory the row silently
#             becomes "JUnit XML is written", which passes and proves nothing
#
#  Two rules the absence-shaped rows follow, because both have produced false passes
#  in this repo before: never conclude "X is legitimately missing" without first
#  proving a report WAS produced, and never conclude "the other path skipped" without
#  proving the other path RAN.
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
# an empty string — reporting a mangled value when the artifact on disk was correct.
export PYTHONIOENCODING=utf-8

# NOT `RESULTS`: common.sh already uses that name for the array print_summary prints.
# Assigning a scalar to it sets element 0, so the output directory appears in the summary
# as a phantom result line above the real ones.
RESULTS_ROOT="$RESULTS_DIR/family-ij-extra"

FN_JMX="$TEST_PLANS/title-function-test.jmx"          # title = ${__P(wir.jmx.title,JMX Fallback Title)}
SINGLE_JMX="$TEST_PLANS/single-sampler-test.jmx"
ASSERTS_JMX="$TEST_PLANS/asserts-scenario-test.jmx"
NO_LISTENER_JMX="$TEST_PLANS/no-listener-test.jmx"    # no listener element — the -g source runs
ASSERTS_STRICT="$ANNOTATIONS_DIR/asserts-strict.json"     # p95 50ms / mean 30ms — everything fails

# Short runs. Long enough that every sampler is exercised, short enough that eight
# scenarios stay under a couple of minutes.
SHORT=(-Jthreads=2 -Jduration=4 -Jrampup=1)
# J32 only: zero threads is the one configuration that reliably produces NO samples at
# all. duration=1 gives "near-zero", which is a different scenario and would let a
# tests="1" XML pass as tests="0".
NO_THREADS=(-Jthreads=0 -Jduration=4 -Jrampup=1)

DEFAULT_BASE="web-insight-report"

print_banner "Family I & J — Exporter Title & JUnit XML Scenarios (I34-I36, J30-J34)"

rm -rf "$RESULTS_ROOT"
mkdir -p "$RESULTS_ROOT"

# -----------------------------------------------------------------------------
#  Helpers
# -----------------------------------------------------------------------------

# clean_dir <id> <dir> — the `clean` precondition, asserted rather than assumed.
# A stale report left by an aborted earlier invocation is exactly the debris that makes
# a broken run look green, so emptiness is checked, not trusted.
clean_dir() {
    local id="$1" dir="$2"
    rm -rf "$dir"
    mkdir -p "$dir"
    if [[ -n "$(ls -A "$dir" 2>/dev/null)" ]]; then
        echo "  [PRECONDITION] $id: $dir is not empty at start — results would be unsound" >&2
        return 1
    fi
    return 0
}

# listener_run <dir> <jmx> [jmeter args...]
# The report output directory defaults to <dir>; set LISTENER_OUT to send it elsewhere
# (I36 needs it to BE the -e -o dashboard directory). Timing flags are the caller's, so
# J32 can ask for zero threads without a duplicate -Jthreads on the command line.
LISTENER_OUT=""
listener_run() {
    local dir="$1" jmx="$2"; shift 2
    local out="${LISTENER_OUT:-$dir}"
    WIR_RUNTIME_LOG="$dir/jmeter-runtime.log" \
    run_jmeter -n -t "$jmx" -l "$dir/results.jtl" \
        -Jwebinsight.report.output="$out" \
        "$@" > "$dir/jmeter.log" 2>&1
}

# make_source_jtl <dir> — a JTL for the -g rows, from a plan with NO listener element,
# so nothing but the exporter can produce a report in the case that follows.
make_source_jtl() {
    local dir="$1"
    WIR_RUNTIME_LOG="$dir/src-runtime.log" \
    run_jmeter -n -t "$NO_LISTENER_JMX" -l "$dir/src.jtl" "${SHORT[@]}" \
        > "$dir/src.log" 2>&1
    file_exists "$dir/src.jtl" && [[ "$(jtl_rows "$dir/src.jtl")" -gt 0 ]]
}

# exporter_run <dir> <output dir> [jmeter args...]
# NOTE the output directory is -o, not -Jwebinsight.report.output: the exporter resolves
# jmeter.reportgenerator.outputdir FIRST, so the plugin's own property is ignored here.
exporter_run() {
    local dir="$1" out="$2"; shift 2
    WIR_RUNTIME_LOG="$dir/gen-runtime.log" \
    run_jmeter -g "$dir/src.jtl" -o "$out" "$@" > "$dir/gen.log" 2>&1
}

json_title()   { "$PYTHON" -c "import json,sys;print(json.load(open(sys.argv[1],encoding='utf-8'))['testName'])" "$1" 2>/dev/null; }
json_valid()   { "$PYTHON" -c "import json,sys;json.load(open(sys.argv[1],encoding='utf-8'))" "$1" >/dev/null 2>&1; }
xml_title()    { "$PYTHON" -c "import xml.etree.ElementTree as E,sys;print(E.parse(sys.argv[1]).getroot().get('name'))" "$1" 2>/dev/null; }
xml_attr()     { "$PYTHON" -c "import xml.etree.ElementTree as E,sys;print(E.parse(sys.argv[1]).getroot().get(sys.argv[2],''))" "$1" "$2" 2>/dev/null; }
xml_valid()    { "$PYTHON" -c "import xml.etree.ElementTree as E,sys;E.parse(sys.argv[1])" "$1" >/dev/null 2>&1; }
# Every <failure> in the document, both testsuites — the per-sampler one and the
# assertions one appended after it.
xml_failures() { "$PYTHON" -c "import xml.etree.ElementTree as E,sys;print(sum(1 for _ in E.parse(sys.argv[1]).iter('failure')))" "$1" 2>/dev/null; }

# json_field <file> <key> — booleans printed the way JSON spells them, not the way
# Python does, so a comparison against 'true' does not silently fail on 'True'.
json_field() {
    "$PYTHON" -c "import json,sys
v = json.load(open(sys.argv[1], encoding='utf-8')).get(sys.argv[2], '')
print(str(v).lower() if isinstance(v, bool) else v)" "$1" "$2" 2>/dev/null
}

# jtl_rows <file> — data rows, excluding the CSV header. Proof of how many samples a
# run actually recorded, which is what makes "zero samples" a fact rather than a hope.
jtl_rows() {
    [[ -f "$1" ]] || { echo 0; return 0; }
    "$PYTHON" -c "import sys;print(max(0,sum(1 for _ in open(sys.argv[1],encoding='utf-8',errors='replace'))-1))" "$1" 2>/dev/null
}

# html_title <file> — the contents of the <title> element, unescaped by the parser.
html_title() {
    "$PYTHON" - "$1" <<'PY' 2>/dev/null
import re, sys, html
src = open(sys.argv[1], encoding='utf-8', errors='replace').read()
m = re.search(r'<title>(.*?)</title>', src, re.S)
print(html.unescape(m.group(1)).strip() if m else '')
PY
}

# assert_title <id> <name> <dir> <basename> <expected> — HTML and JSON agree on the title.
assert_title() {
    local id="$1" name="$2" dir="$3" base="$4" expected="$5"
    local html="$dir/$base.html" json="$dir/$base.json" notes="" got_html got_json

    if ! file_exists "$html"; then
        record "$id" "$name" FAIL "no report at $html"
        return
    fi

    got_html="$(html_title "$html")"
    [[ "$got_html" == "$expected" ]] \
        || notes="$(append_note "$notes" "HTML title was '$got_html', expected '$expected'")"

    if file_exists "$json"; then
        got_json="$(json_title "$json")"
        [[ "$got_json" == "$expected" ]] \
            || notes="$(append_note "$notes" "JSON testName was '$got_json'")"
    else
        notes="$(append_note "$notes" "no .json written")"
    fi

    if [[ -z "$notes" ]]; then
        record "$id" "$name" PASS "title '$expected' consistent across artifacts"
    else
        record "$id" "$name" FAIL "$notes"
    fi
}

# =============================================================================
#  I34-I36 — the title on the entry points T01-T29 cannot reach
# =============================================================================

# -----------------------------------------------------------------------------
# I34: `-g` with a -J title containing a function reference.
#
#      The exporter runs outside the test plan, so the evaluated surface (F15) does
#      not exist here at all — -J is the only way in, and F14 says -J is a raw string.
#      A literal ${__P(...)} in the report is therefore the CORRECT outcome, and this
#      is the one path where nothing else is even possible.
#
#      -Jwir.jmx.title is set deliberately: if the value were ever evaluated, the
#      report would say "Evaluated Would Look Like This" and the difference is visible
#      rather than a null result.
# -----------------------------------------------------------------------------
echo "[I34] -g exporter: a -J title containing a function reference..."
I34_DIR="$RESULTS_ROOT/I34"
if ! clean_dir I34 "$I34_DIR"; then
    record I34 "-g: -J function stays literal" FAIL "precondition: could not establish a clean directory"
elif ! make_source_jtl "$I34_DIR"; then
    record I34 "-g: -J function stays literal" FAIL "the source run produced no usable JTL"
else
    exporter_run "$I34_DIR" "$I34_DIR/dash" \
        -Jwir.jmx.title="Evaluated Would Look Like This" \
        -Jwebinsight.report.title='${__P(wir.jmx.title)}'
    assert_title I34 "-g: -J function stays literal" "$I34_DIR/dash" "$DEFAULT_BASE" \
        '${__P(wir.jmx.title)}'
fi

# -----------------------------------------------------------------------------
# I35: `-g` with a plain -J title — the control for I34.
#
#      Without it, I34 cannot distinguish "the property was taken literally" from
#      "the property was never read on this path at all", which was a real defect:
#      the exporter ignored webinsight.report.title entirely and every -g report
#      carried the default name of the test.
# -----------------------------------------------------------------------------
echo "[I35] -g exporter: a plain -J title..."
I35_DIR="$RESULTS_ROOT/I35"
if ! clean_dir I35 "$I35_DIR"; then
    record I35 "-g: plain -J title applied" FAIL "precondition: could not establish a clean directory"
elif ! make_source_jtl "$I35_DIR"; then
    record I35 "-g: plain -J title applied" FAIL "the source run produced no usable JTL"
else
    exporter_run "$I35_DIR" "$I35_DIR/dash" \
        -Jwebinsight.report.title="Exporter Literal Title"
    assert_title I35 "-g: plain -J title applied" "$I35_DIR/dash" "$DEFAULT_BASE" \
        "Exporter Literal Title"
fi

# -----------------------------------------------------------------------------
# I36: both paths in one JVM, title in the JMX only, containing a function.
#
#      The listener evaluates its field (F15) and writes; the exporter, finding that
#      report, must skip (F8). Three things are asserted and none of them is
#      sufficient alone:
#
#        - JMeter's own index.html exists, which proves the report generator RAN.
#          Without it "the exporter skipped" is indistinguishable from "-e was
#          ignored and the exporter never ran", and the row would pass for free.
#        - the skip WARN is in JMeter's own log (nothing reaches the console; see
#          WIR_RUNTIME_LOG in common.sh).
#        - the surviving title is the EVALUATED one, not the literal field text.
# -----------------------------------------------------------------------------
echo "[I36] -n -t with -e -o: listener evaluates, exporter skips..."
I36_DIR="$RESULTS_ROOT/I36"
I36_DASH="$I36_DIR/dash"
if ! clean_dir I36 "$I36_DIR"; then
    record I36 "Both paths: evaluated title survives" FAIL "precondition: could not establish a clean directory"
else
    # $I36_DASH must not exist yet — JMeter refuses a non-empty -o directory. The
    # listener creates it when it writes, before the dashboard generator runs.
    LISTENER_OUT="$I36_DASH" listener_run "$I36_DIR" "$FN_JMX" "${SHORT[@]}" \
        -Jwir.jmx.title="Evaluated By Listener" \
        -e -o "$I36_DASH"

    I36_NOTES=""
    file_exists "$I36_DASH/index.html" \
        || I36_NOTES="$(append_note "$I36_NOTES" "JMeter's own dashboard is absent — the report generator never ran, so a skip cannot be claimed")"
    assert_log_contains "$I36_DIR/jmeter-runtime.log" "skipping CLI generation" \
        || I36_NOTES="$(append_note "$I36_NOTES" "no 'skipping CLI generation' WARN — the exporter did not detect the listener's report")"
    if file_exists "$I36_DASH/$DEFAULT_BASE.html"; then
        I36_GOT="$(html_title "$I36_DASH/$DEFAULT_BASE.html")"
        [[ "$I36_GOT" == "Evaluated By Listener" ]] \
            || I36_NOTES="$(append_note "$I36_NOTES" "surviving title was '$I36_GOT', expected the evaluated 'Evaluated By Listener'")"
    else
        I36_NOTES="$(append_note "$I36_NOTES" "no Web Insight report in the dashboard directory")"
    fi

    if [[ -z "$I36_NOTES" ]]; then
        record I36 "Both paths: evaluated title survives" PASS \
            "dashboard generated, exporter skipped, one report carrying the evaluated title"
    else
        record I36 "Both paths: evaluated title survives" FAIL "$I36_NOTES"
    fi
fi

# =============================================================================
#  J30-J34 — JUnit XML beyond "a file appeared"
# =============================================================================

# -----------------------------------------------------------------------------
# J30: two runs into ONE folder. Pre = carry — the second run must NOT be cleaned.
#
#      Overwriting is the intended default (F2) and the only signal is a WARN in a log
#      nothing reads, so the artifacts must say which run wrote them. The title is the
#      one field reaching all three (F16), so RUN-1 -> RUN-2 turns "the mtime moved"
#      into a string comparison that also distinguishes "overwrote" from "skipped".
#
#      Run 1 is verified before run 2 starts: if it produced nothing, the carry
#      precondition never existed and run 2 is just a clean-directory run.
# -----------------------------------------------------------------------------
echo "[J30] JUnit on, two runs into one folder (carry)..."
J30_DIR="$RESULTS_ROOT/J30"
if ! clean_dir J30 "$J30_DIR"; then
    record J30 "Repeat runs overwrite all three" FAIL "precondition: could not establish a clean directory"
else
    listener_run "$J30_DIR" "$SINGLE_JMX" "${SHORT[@]}" \
        -Jwebinsight.report.junit=true -Jwebinsight.report.title="RUN-1"

    J30_SEEDED=1
    for f in "$DEFAULT_BASE.html" "$DEFAULT_BASE.json" "$DEFAULT_BASE.xml"; do
        file_exists "$J30_DIR/$f" || J30_SEEDED=0
    done
    [[ "$(json_title "$J30_DIR/$DEFAULT_BASE.json")" == "RUN-1" ]] || J30_SEEDED=0

    if [[ "$J30_SEEDED" -eq 0 ]]; then
        record J30 "Repeat runs overwrite all three" FAIL \
            "run 1 did not leave three RUN-1 artifacts, so there was nothing to carry into run 2"
    else
        # Deliberately NOT cleaned — the pre-existing artifacts are the scenario.
        listener_run "$J30_DIR" "$SINGLE_JMX" "${SHORT[@]}" \
            -Jwebinsight.report.junit=true -Jwebinsight.report.title="RUN-2"

        J30_NOTES=""
        J30_H="$(html_title "$J30_DIR/$DEFAULT_BASE.html")"
        J30_J="$(json_title "$J30_DIR/$DEFAULT_BASE.json")"
        J30_X="$(xml_title "$J30_DIR/$DEFAULT_BASE.xml")"
        [[ "$J30_H" == "RUN-2" ]] || J30_NOTES="$(append_note "$J30_NOTES" "HTML still says '$J30_H'")"
        [[ "$J30_J" == "RUN-2" ]] || J30_NOTES="$(append_note "$J30_NOTES" "JSON testName still says '$J30_J'")"
        [[ "$J30_X" == "RUN-2" ]] || J30_NOTES="$(append_note "$J30_NOTES" "XML testsuites name still says '$J30_X'")"

        if [[ -z "$J30_NOTES" ]]; then
            record J30 "Repeat runs overwrite all three" PASS \
                "HTML, JSON and JUnit XML all carry RUN-2 — the listener overwrote every artifact (F2)"
        else
            record J30 "Repeat runs overwrite all three" FAIL "$J30_NOTES"
        fi
    fi
fi

# -----------------------------------------------------------------------------
# J31: a ${timestamp} filename, resolved ONCE for every artifact.
#
#      F3 makes the stamp second-granular, so two independent resolutions a few
#      hundred milliseconds apart usually agree and occasionally do not — the failure
#      would be rare, non-reproducible, and would leave a pipeline's .xml orphaned
#      from the .html it belongs to. Comparing the basenames is what pins it.
# -----------------------------------------------------------------------------
echo "[J31] JUnit on with a \${timestamp} filename..."
J31_DIR="$RESULTS_ROOT/J31"
if ! clean_dir J31 "$J31_DIR"; then
    record J31 "One timestamp across .html and .xml" FAIL "precondition: could not establish a clean directory"
else
    listener_run "$J31_DIR" "$SINGLE_JMX" "${SHORT[@]}" \
        -Jwebinsight.report.junit=true \
        -Jwebinsight.report.title="J31-Stamped" \
        -Jwebinsight.report.filename='J31-${timestamp}.html'

    shopt -s nullglob
    J31_HTMLS=("$J31_DIR"/J31-*.html)
    J31_XMLS=("$J31_DIR"/J31-*.xml)
    J31_JSONS=("$J31_DIR"/J31-*.json)
    shopt -u nullglob

    J31_NOTES=""
    [[ "${#J31_HTMLS[@]}" -eq 1 ]] || J31_NOTES="$(append_note "$J31_NOTES" "${#J31_HTMLS[@]} stamped .html file(s)")"
    [[ "${#J31_XMLS[@]}" -eq 1 ]]  || J31_NOTES="$(append_note "$J31_NOTES" "${#J31_XMLS[@]} stamped .xml file(s)")"
    [[ "${#J31_JSONS[@]}" -eq 1 ]] || J31_NOTES="$(append_note "$J31_NOTES" "${#J31_JSONS[@]} stamped .json file(s)")"

    if [[ -z "$J31_NOTES" ]]; then
        J31_HB="$(basename "${J31_HTMLS[0]}" .html)"
        J31_XB="$(basename "${J31_XMLS[0]}" .xml)"
        J31_JB="$(basename "${J31_JSONS[0]}" .json)"
        if [[ "$J31_HB" == "$J31_XB" && "$J31_HB" == "$J31_JB" ]]; then
            record J31 "One timestamp across .html and .xml" PASS \
                "all three artifacts are $J31_HB.{html,json,xml} — the stamp was resolved once (F3)"
        else
            record J31 "One timestamp across .html and .xml" FAIL \
                "basenames disagree: html '$J31_HB', json '$J31_JB', xml '$J31_XB' — the stamp was resolved per artifact"
        fi
    else
        record J31 "One timestamp across .html and .xml" FAIL "$J31_NOTES"
    fi
fi

# -----------------------------------------------------------------------------
# J32: JUnit on, ZERO samples.
#
#      threads=0 rather than duration=1: "near-zero" (N01) still records a sample or
#      two, and a tests="1" document would satisfy a loose assertion about a run that
#      was supposed to have nothing in it. The JTL row count is asserted so "zero
#      samples" is a measured fact, not an assumption about JMeter's scheduler.
#
#      A JUnit consumer must get a well-formed document with tests="0" — an absent or
#      truncated file is what breaks a pipeline stage, not an empty one.
# -----------------------------------------------------------------------------
echo "[J32] JUnit on, zero samples..."
J32_DIR="$RESULTS_ROOT/J32"
if ! clean_dir J32 "$J32_DIR"; then
    record J32 "Zero samples still yields valid XML" FAIL "precondition: could not establish a clean directory"
else
    listener_run "$J32_DIR" "$SINGLE_JMX" "${NO_THREADS[@]}" \
        -Jwebinsight.report.junit=true -Jwebinsight.report.title="J32-ZeroSamples"

    J32_NOTES=""
    J32_ROWS="$(jtl_rows "$J32_DIR/results.jtl")"
    [[ "$J32_ROWS" -eq 0 ]] \
        || J32_NOTES="$(append_note "$J32_NOTES" "the run recorded $J32_ROWS sample(s) — this is not the zero-sample scenario")"
    file_exists "$J32_DIR/$DEFAULT_BASE.html" \
        || J32_NOTES="$(append_note "$J32_NOTES" "no HTML report — the run produced nothing at all")"
    if file_exists "$J32_DIR/$DEFAULT_BASE.xml" && xml_valid "$J32_DIR/$DEFAULT_BASE.xml"; then
        J32_TESTS="$(xml_attr "$J32_DIR/$DEFAULT_BASE.xml" tests)"
        J32_FAILS="$(xml_failures "$J32_DIR/$DEFAULT_BASE.xml")"
        [[ "$J32_TESTS" == "0" ]] \
            || J32_NOTES="$(append_note "$J32_NOTES" "testsuites tests='$J32_TESTS', expected 0")"
        [[ "$J32_FAILS" == "0" ]] \
            || J32_NOTES="$(append_note "$J32_NOTES" "$J32_FAILS <failure> element(s) in a run with no samples")"
    else
        J32_NOTES="$(append_note "$J32_NOTES" ".xml is missing or not well-formed")"
    fi

    if [[ -z "$J32_NOTES" ]]; then
        record J32 "Zero samples still yields valid XML" PASS \
            "0 JTL rows; well-formed <testsuites tests=\"0\" failures=\"0\">"
    else
        record J32 "Zero samples still yields valid XML" FAIL "$J32_NOTES"
    fi
fi

# -----------------------------------------------------------------------------
# J33: the .xml path cannot be written. Pre = seed — the obstruction IS the scenario.
#
#      An ACL-based read-only path is not portable to Windows (the runner's own account
#      routinely keeps write access, so the seed silently evaporates and the row passes
#      as "JUnit XML is written"). A non-empty DIRECTORY at the .xml path fails the same
#      write on every platform: AtomicFileWriter stages a sibling and moves it into
#      place, and a move onto a non-empty directory cannot succeed.
#
#      Expected shape: HTML and JSON unaffected (the XML is written last, step 11), the
#      failure swallowed, recorded and logged at ERROR, and the run marked INCOMPLETE
#      on stderr. The JSON's testName proves THIS run wrote it, so "the HTML survived"
#      cannot be satisfied by a leftover.
# -----------------------------------------------------------------------------
echo "[J33] JUnit on with an unwritable .xml path (seeded)..."
J33_DIR="$RESULTS_ROOT/J33"
if ! clean_dir J33 "$J33_DIR"; then
    record J33 "Unwritable .xml path" FAIL "precondition: could not establish a clean directory"
else
    # The seed: a directory where the .xml has to go, with something in it.
    mkdir -p "$J33_DIR/$DEFAULT_BASE.xml"
    echo "blocker" > "$J33_DIR/$DEFAULT_BASE.xml/blocker.txt"

    if [[ ! -d "$J33_DIR/$DEFAULT_BASE.xml" ]]; then
        record J33 "Unwritable .xml path" BLOCKED "could not create the obstruction at $J33_DIR/$DEFAULT_BASE.xml"
    else
        listener_run "$J33_DIR" "$SINGLE_JMX" "${SHORT[@]}" \
            -Jwebinsight.report.junit=true -Jwebinsight.report.title="J33-Blocked"
        J33_RC=$?

        J33_NOTES=""
        # An incomplete set is a failed run now, by default. This row is the partial case the
        # widening was for: the report and its .json are fine and only the .xml is missing, so
        # the old failonerror -- which fired only when generateReport itself threw -- left a CI
        # job gating on the JUnit XML with exit 0 and no file.
        [[ "$J33_RC" -ne 0 ]] \
            || J33_NOTES="$(append_note "$J33_NOTES" "exited 0 despite a missing .xml — the failonerror default did not fire on a partial failure")"
        file_exists "$J33_DIR/$DEFAULT_BASE.html" \
            || J33_NOTES="$(append_note "$J33_NOTES" "HTML was not written")"
        if file_exists "$J33_DIR/$DEFAULT_BASE.json" && json_valid "$J33_DIR/$DEFAULT_BASE.json"; then
            J33_JT="$(json_title "$J33_DIR/$DEFAULT_BASE.json")"
            [[ "$J33_JT" == "J33-Blocked" ]] \
                || J33_NOTES="$(append_note "$J33_NOTES" "JSON testName was '$J33_JT' — not written by this run")"
        else
            J33_NOTES="$(append_note "$J33_NOTES" "JSON is missing or invalid")"
        fi
        assert_log_contains "$J33_DIR/jmeter-runtime.log" "Failed to write the JUnit XML" \
            || J33_NOTES="$(append_note "$J33_NOTES" "the XML failure was not logged at ERROR")"
        assert_contains "$J33_DIR/jmeter.log" "INCOMPLETE" \
            || J33_NOTES="$(append_note "$J33_NOTES" "no INCOMPLETE marker on the console — a pipeline sees unqualified success")"
        # And the obstruction must still be an obstruction, not a clobbered directory.
        [[ -d "$J33_DIR/$DEFAULT_BASE.xml" ]] \
            || J33_NOTES="$(append_note "$J33_NOTES" "the seeded directory was destroyed")"

        if [[ -z "$J33_NOTES" ]]; then
            record J33 "Unwritable .xml path" PASS \
                "HTML and JSON written by this run; XML failure logged at ERROR and flagged INCOMPLETE"
        else
            record J33 "Unwritable .xml path" FAIL "$J33_NOTES"
        fi
    fi
fi

# -----------------------------------------------------------------------------
# J34: JUnit on with a gate that FAILS.
#
#      The whole point of the XML is that a build stops on it, so "a .xml appeared" is
#      not the property under test — `failures` and the <failure> elements have to
#      reflect the verdict, and they have to AGREE with the JSON status. GateEvaluation
#      is the single place a run is judged precisely so those two cannot drift; this is
#      the row that would notice if they did.
#
#      asserts-strict.json is p95 50ms / mean 30ms against a plan whose fastest sampler
#      sleeps 30-100ms, so the verdict does not depend on machine load.
# -----------------------------------------------------------------------------
echo "[J34] JUnit on with a FAILING assert gate..."
J34_DIR="$RESULTS_ROOT/J34"
if ! clean_dir J34 "$J34_DIR"; then
    record J34 "Failing gate reaches the XML" FAIL "precondition: could not establish a clean directory"
elif ! file_exists "$ASSERTS_STRICT"; then
    record J34 "Failing gate reaches the XML" FAIL "annotations fixture missing: $ASSERTS_STRICT"
else
    listener_run "$J34_DIR" "$ASSERTS_JMX" "${SHORT[@]}" \
        -Jwebinsight.report.junit=true \
        -Jwebinsight.report.annotations="$ASSERTS_STRICT" \
        -Jwebinsight.report.title="J34-FailingGate"

    J34_NOTES=""
    J34_STATUS=""
    if file_exists "$J34_DIR/$DEFAULT_BASE.json" && json_valid "$J34_DIR/$DEFAULT_BASE.json"; then
        J34_STATUS="$(json_field "$J34_DIR/$DEFAULT_BASE.json" status)"
        [[ "$(json_field "$J34_DIR/$DEFAULT_BASE.json" assertsEvaluated)" == "true" ]] \
            || J34_NOTES="$(append_note "$J34_NOTES" "assertsEvaluated is not true — the gate never ran, so a FAIL would be meaningless")"
    else
        J34_NOTES="$(append_note "$J34_NOTES" "JSON is missing or invalid")"
    fi

    if file_exists "$J34_DIR/$DEFAULT_BASE.xml" && xml_valid "$J34_DIR/$DEFAULT_BASE.xml"; then
        J34_FAILATTR="$(xml_attr "$J34_DIR/$DEFAULT_BASE.xml" failures)"
        J34_FAILELEM="$(xml_failures "$J34_DIR/$DEFAULT_BASE.xml")"
        [[ "${J34_FAILATTR:-0}" -gt 0 ]] \
            || J34_NOTES="$(append_note "$J34_NOTES" "testsuites failures='$J34_FAILATTR' on a failing gate")"
        [[ "${J34_FAILELEM:-0}" -gt 0 ]] \
            || J34_NOTES="$(append_note "$J34_NOTES" "no <failure> elements on a failing gate")"
        # The agreement, in both directions — a green XML beside a FAIL JSON is the one
        # outcome that would let a pipeline pass a run the report calls failed.
        if [[ "$J34_STATUS" != "FAIL" ]]; then
            J34_NOTES="$(append_note "$J34_NOTES" "JSON status was '$J34_STATUS', expected FAIL against asserts-strict.json")"
        fi
    else
        J34_NOTES="$(append_note "$J34_NOTES" ".xml is missing or not well-formed")"
    fi

    if [[ -z "$J34_NOTES" ]]; then
        record J34 "Failing gate reaches the XML" PASS \
            "JSON status FAIL; testsuites failures=${J34_FAILATTR}, ${J34_FAILELEM} <failure> element(s)"
    else
        record J34 "Failing gate reaches the XML" FAIL "$J34_NOTES"
    fi
fi

print_summary "FAMILY I & J EXTRA TEST SUMMARY (I34-I36, J30-J34)"

exit $FAIL
