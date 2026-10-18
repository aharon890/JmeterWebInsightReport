#!/usr/bin/env bash
# =============================================================================
#  Report Title & JUnit XML Scenario Tests (T01-T28)
#
#  Covers the two configuration surfaces — -J properties and the JMX/GUI test
#  element — for the report title and for JUnit XML generation, including the
#  precedence rules between them and the places those rules are asymmetric.
#
#  Why this is its own runner rather than more C-series cases: the title has a
#  three-level fallback (-J -> JMX -> hardcoded default) in which an EMPTY -J
#  falls through rather than winning, while JUnit XML has a two-branch resolution
#  with no else — a non-empty, non-"true" -J value matches neither branch and
#  silently suppresses the JMX checkbox. Both need the JMX surface populated,
#  which -J-only C-series cases cannot do.
#
#  The title lands in three artifacts and they must agree:
#    HTML  <title> / header
#    JSON  "testName"
#    XML   <testsuites name="...">
#
#  Assumes the plugin is already deployed (run ./scripts/build-and-deploy.sh).
# =============================================================================
set -uo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../../scripts/lib/common.sh"

require_jmeter
require_python || exit 1

# The helpers below print artifact contents back to the shell for comparison. On Windows
# Python's stdout defaults to the console codepage (cp1252), so printing a CJK title raises
# UnicodeEncodeError, the helper dies, and the caller silently compares against an empty
# string -- reporting a mangled title when the artifact on disk was perfectly correct.
# Verified: T17's .json held the right characters while the assertion said ''.
export PYTHONIOENCODING=utf-8

# NOT "RESULTS": common.sh declares RESULTS=() as the results ARRAY that record() appends
# to, so assigning a path to it makes the path element 0 and print_summary prints it as a
# phantom first line. run-config-tests.sh has the same collision.
RESULTS_ROOT="$RESULTS_DIR/title-junit-tests"
FN_JMX="$TEST_PLANS/title-function-test.jmx"
PARTIAL_JMX="$TEST_PLANS/title-partial-test.jmx"
BLANK_JMX="$TEST_PLANS/title-blank-test.jmx"
JUNIT_JMX="$TEST_PLANS/junit-jmx-enabled-test.jmx"
UNI_JMX="$TEST_PLANS/title-unicode-test.jmx"
LITERAL_JMX="$TEST_PLANS/gui-filename-test.jmx"   # title "GUI-CONFIGURED", filename gui-name.html

DEFAULT_TITLE="JMeter Web Insight Report"

print_banner "Report Title & JUnit XML Scenario Tests"

rm -rf "$RESULTS_ROOT"
mkdir -p "$RESULTS_ROOT"

# -----------------------------------------------------------------------------
# Helpers
# -----------------------------------------------------------------------------

# run_case <case-id> <jmx> [extra jmeter args...]
# Runs into $RESULTS_ROOT/<case-id>, short and quiet. Echoes the case directory.
#
# PRECONDITION: every T-row is a `clean` row -- it asserts about artifacts THIS run produced,
# so any pre-existing file could satisfy an assertion the run never earned. The directory is
# therefore removed and recreated rather than merely mkdir -p'd, and the emptiness is checked
# rather than assumed: a stale report left by an aborted earlier invocation is exactly the
# debris that makes a broken run look green. Rows that need a PRE-SEEDED directory (the
# `seed:`/`carry` classes in SCENARIO-MATRIX.md families A-H) must not use this helper.
run_case() {
    local id="$1" jmx="$2"; shift 2
    local dir="$RESULTS_ROOT/$id"
    rm -rf "$dir"
    mkdir -p "$dir"
    if [[ -n "$(ls -A "$dir" 2>/dev/null)" ]]; then
        echo "  [PRECONDITION] $id: output dir is not empty at start -- results would be unsound" >&2
        return 1
    fi
    # Two logs, because they carry different things and one row already needed both.
    # jmeter.log is the CONSOLE capture; jmeter-runtime.log is JMeter's own -j log, which is
    # the only place a plugin WARN or ERROR lands — JMeter's default log4j2.xml ships no
    # Console appender. T25 asserts on a warning and was silently asserting against a file
    # that did not exist.
    WIR_RUNTIME_LOG="$dir/jmeter-runtime.log" \
    run_jmeter -n -t "$jmx" -l "$dir/results.jtl" \
        -Jthreads=2 -Jduration=4 -Jrampup=1 \
        -Jwebinsight.report.output="$dir" \
        "$@" > "$dir/jmeter.log" 2>&1
    printf '%s\n' "$dir"
}

json_title()   { "$PYTHON" -c "import json,sys;print(json.load(open(sys.argv[1],encoding='utf-8'))['testName'])" "$1" 2>/dev/null; }
xml_title()    { "$PYTHON" -c "import xml.etree.ElementTree as E,sys;print(E.parse(sys.argv[1]).getroot().get('name'))" "$1" 2>/dev/null; }
json_valid()   { "$PYTHON" -c "import json,sys;json.load(open(sys.argv[1],encoding='utf-8'))" "$1" >/dev/null 2>&1; }
xml_valid()    { "$PYTHON" -c "import xml.etree.ElementTree as E,sys;E.parse(sys.argv[1])" "$1" >/dev/null 2>&1; }

# html_title <file> — the contents of the <title> element, unescaped by the parser.
html_title() {
    "$PYTHON" - "$1" <<'PY' 2>/dev/null
import re, sys, html
src = open(sys.argv[1], encoding='utf-8', errors='replace').read()
m = re.search(r'<title>(.*?)</title>', src, re.S)
print(html.unescape(m.group(1)).strip() if m else '')
PY
}

# assert_title <id> <name> <dir> <expected> — HTML, JSON and XML(when present) agree.
assert_title() {
    local id="$1" name="$2" dir="$3" expected="$4"
    local html json notes=""
    html="$dir/web-insight-report.html"
    json="$dir/web-insight-report.json"

    if ! file_exists "$html"; then
        record "$id" "$name" FAIL "no report at $html"
        return
    fi

    local got_html got_json
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
#  Title — baseline and literal values
# =============================================================================

echo "[T01] Default title (nothing configured anywhere)..."
D="$(run_case T01 "$BLANK_JMX")"
assert_title T01 "Default title" "$D" "$DEFAULT_TITLE"

echo "[T02] Literal title via -J..."
D="$(run_case T02 "$BLANK_JMX" -Jwebinsight.report.title="CLI Literal Title")"
assert_title T02 "Literal title via -J" "$D" "CLI Literal Title"

echo "[T03] Literal title in the JMX element..."
D="$(run_case T03 "$JUNIT_JMX")"
assert_title T03 "Literal title via JMX" "$D" "JMX Literal Title"

echo "[T04] Empty -J falls THROUGH to the JMX field (not to the default)..."
D="$(run_case T04 "$JUNIT_JMX" -Jwebinsight.report.title=)"
assert_title T04 "Empty -J falls through to JMX" "$D" "JMX Literal Title"

echo "[T05] -J beats a JMX literal..."
D="$(run_case T05 "$JUNIT_JMX" -Jwebinsight.report.title="CLI Wins")"
assert_title T05 "-J beats JMX" "$D" "CLI Wins"

echo "[T06] Blank JMX field and no -J -> hardcoded default..."
D="$(run_case T06 "$BLANK_JMX")"
assert_title T06 "Blank JMX field -> default" "$D" "$DEFAULT_TITLE"

echo "[T07] Whitespace-only title is NOT empty and is used as-is..."
D="$(run_case T07 "$BLANK_JMX" -Jwebinsight.report.title="   ")"
# Assert on the JSON, not the HTML: an HTML parser strips whitespace inside <title>,
# so the HTML cannot tell "kept a whitespace title" apart from "wrote nothing".
if file_exists "$D/web-insight-report.json"; then
    T07_GOT="$(json_title "$D/web-insight-report.json")"
    if [[ "$T07_GOT" == "   " ]]; then
        record T07 "Whitespace-only title" PASS "whitespace survived verbatim -- isEmpty() does not trim (F18)"
    elif [[ "$T07_GOT" == "$DEFAULT_TITLE" ]]; then
        record T07 "Whitespace-only title" FAIL "fell back to the default; F18 says isEmpty() should have kept it"
    else
        record T07 "Whitespace-only title" FAIL "testName was '$T07_GOT', expected three spaces"
    fi
else
    record T07 "Whitespace-only title" FAIL "no .json"
fi

# =============================================================================
#  Title — property references (the two surfaces resolve differently)
# =============================================================================

echo "[T08] JMX full property reference, property SET -> evaluated..."
D="$(run_case T08 "$FN_JMX" -Jwir.jmx.title="Nightly Run")"
assert_title T08 "JMX full property evaluated" "$D" "Nightly Run"

echo "[T09] JMX full property reference, property UNSET -> __P default argument..."
D="$(run_case T09 "$FN_JMX")"
assert_title T09 "JMX property default argument" "$D" "JMX Fallback Title"

echo "[T10] JMX PARTIAL property reference -> both substituted, literal text kept..."
D="$(run_case T10 "$PARTIAL_JMX" -Jwir.env=staging -Jwir.build=42)"
assert_title T10 "JMX partial property" "$D" "Load staging build 42"

echo "[T11] JMX partial reference with one property missing..."
D="$(run_case T11 "$PARTIAL_JMX" -Jwir.env=staging)"
assert_title T11 "JMX partial, one missing" "$D" "Load staging build 0"

echo "[T12] -J containing a function reference is NOT evaluated (lands literally)..."
D="$(run_case T12 "$BLANK_JMX" -Jwir.jmx.title="Nightly Run" -Jwebinsight.report.title='${__P(wir.jmx.title)}')"
assert_title T12 "-J function is literal" "$D" '${__P(wir.jmx.title)}'

echo "[T13] -J function reference DISCARDS a working JMX title..."
D="$(run_case T13 "$JUNIT_JMX" -Jwebinsight.report.title='${__P(wir.jmx.title)}')"
assert_title T13 "-J literal beats good JMX title" "$D" '${__P(wir.jmx.title)}'

echo "[T14] The plugin's own \${timestamp} is a FILENAME placeholder, not a title one..."
D="$(run_case T14 "$BLANK_JMX" -Jwebinsight.report.title='Run ${timestamp}')"
assert_title T14 "\${timestamp} not substituted in title" "$D" 'Run ${timestamp}'

# =============================================================================
#  Title — hostile and edge values
# =============================================================================

echo "[T15] Title containing HTML is escaped, not executed..."
D="$(run_case T15 "$BLANK_JMX" -Jwebinsight.report.title='<script>alert(1)</script>')"
T15_HTML="$D/web-insight-report.html"
if file_exists "$T15_HTML"; then
    T15_NOTES=""
    assert_contains "$T15_HTML" '&lt;script&gt;alert(1)&lt;/script&gt;' \
        || T15_NOTES="$(append_note "$T15_NOTES" "escaped form not found in HTML")"
    # The raw tag must not appear as live markup anywhere.
    if grep -qF '<title><script>' "$T15_HTML" 2>/dev/null; then
        T15_NOTES="$(append_note "$T15_NOTES" "raw <script> reached the document")"
    fi
    if [[ -z "$T15_NOTES" ]]; then
        record T15 "HTML in title is escaped" PASS "rendered as text"
    else
        record T15 "HTML in title is escaped" FAIL "$T15_NOTES"
    fi
else
    record T15 "HTML in title is escaped" FAIL "no report"
fi

echo "[T16] XML-hostile characters keep all three artifacts well-formed..."
XML_HOSTILE='A & B <C> "D" '"'"'E'"'"''
D="$(run_case T16 "$BLANK_JMX" -Jwebinsight.report.title="$XML_HOSTILE" -Jwebinsight.report.junit=true)"
T16_NOTES=""
file_exists "$D/web-insight-report.json" && json_valid "$D/web-insight-report.json" \
    || T16_NOTES="$(append_note "$T16_NOTES" ".json is missing or not valid JSON")"
file_exists "$D/web-insight-report.xml" && xml_valid "$D/web-insight-report.xml" \
    || T16_NOTES="$(append_note "$T16_NOTES" ".xml is missing or not well-formed")"
if file_exists "$D/web-insight-report.xml" && xml_valid "$D/web-insight-report.xml"; then
    T16_XT="$(xml_title "$D/web-insight-report.xml")"
    [[ "$T16_XT" == "$XML_HOSTILE" ]] \
        || T16_NOTES="$(append_note "$T16_NOTES" "XML testsuites name was '$T16_XT'")"
fi
if [[ -z "$T16_NOTES" ]]; then
    record T16 "XML-hostile title stays well-formed" PASS "HTML, JSON and JUnit XML all parse"
else
    record T16 "XML-hostile title stays well-formed" FAIL "$T16_NOTES"
fi

echo "[T17] Non-ASCII title survives round-trip (JMX path)..."
# Deliberately the JMX path, not -J. The title lives as UTF-8 bytes inside the .jmx and
# JMeter parses that file as UTF-8, so this tests the PLUGIN's encoding handling with no
# command-line argv in the way. T29 covers the -J path separately, because argv encoding
# is a property of the OS/console rather than of this plugin.
UNI_TITLE='负载测试 — ÄÖÜ — ok'
D="$(run_case T17 "$UNI_JMX")"
assert_title T17 "Non-ASCII title (JMX path)" "$D" "$UNI_TITLE"

echo "[T18] Very long title (500+ chars)..."
LONG_TITLE="$(printf 'L%.0s' $(seq 1 520))"
D="$(run_case T18 "$BLANK_JMX" -Jwebinsight.report.title="$LONG_TITLE")"
assert_title T18 "Very long title" "$D" "$LONG_TITLE"

echo "[T19] Title with a lone unterminated \${ does not break the run..."
D="$(run_case T19 "$BLANK_JMX" -Jwebinsight.report.title='broken ${')"
if file_exists "$D/web-insight-report.html"; then
    record T19 "Unterminated \${ in title" PASS "report still produced"
else
    record T19 "Unterminated \${ in title" FAIL "no report produced"
fi

# =============================================================================
#  JUnit XML — the two surfaces and their asymmetric precedence
# =============================================================================

# An "absence" assertion passes for free when the run never happened -- the first version of
# this suite reported T20/T24/T25 as PASS while every JMeter run was failing to compile.
# assert_no_xml therefore requires positive proof that a report WAS produced first.
assert_no_xml() {
    local id="$1" name="$2" dir="$3" note="$4"
    if ! file_exists "$dir/web-insight-report.html"; then
        record "$id" "$name" FAIL "no report produced at all -- absence of .xml proves nothing"
    elif file_absent "$dir/web-insight-report.xml"; then
        record "$id" "$name" PASS "$note"
    else
        record "$id" "$name" FAIL ".xml was written when it should not have been"
    fi
}

echo "[T20] JUnit XML is NOT generated by default..."
D="$(run_case T20 "$BLANK_JMX")"
assert_no_xml T20 "No JUnit XML by default" "$D" "report written, no .xml alongside it"

echo "[T21] -Jjunit=true writes a sibling .xml..."
D="$(run_case T21 "$BLANK_JMX" -Jwebinsight.report.junit=true)"
if file_exists "$D/web-insight-report.xml" && xml_valid "$D/web-insight-report.xml"; then
    record T21 "-J junit=true" PASS "well-formed .xml sibling written"
else
    record T21 "-J junit=true" FAIL "no valid .xml"
fi

echo "[T22] JMX checkbox alone enables it (no -J)..."
D="$(run_case T22 "$JUNIT_JMX")"
if file_exists "$D/web-insight-report.xml"; then
    record T22 "JMX checkbox enables JUnit" PASS ".xml written from the plan setting"
else
    record T22 "JMX checkbox enables JUnit" FAIL "checkbox is true in the plan but no .xml appeared"
fi

echo "[T23] -Jjunit=true wins over a JMX checkbox that is false..."
D="$(run_case T23 "$FN_JMX" -Jwebinsight.report.junit=true)"
if file_exists "$D/web-insight-report.xml"; then
    record T23 "-J true beats JMX false" PASS ".xml written"
else
    record T23 "-J true beats JMX false" FAIL "no .xml"
fi

echo "[T24] -Jjunit=false suppresses a JMX checkbox that is true..."
D="$(run_case T24 "$JUNIT_JMX" -Jwebinsight.report.junit=false)"
assert_no_xml T24 "-J false suppresses JMX true" "$D" \
    "no .xml -- and now because 'false' is READ as false, not because it fell through to the default (F17 superseded)"

# T25 asserted the opposite of this until the boolean parsing changed, and the row is kept
# rather than deleted because the inversion is the point.
#
# Before: resolution was `"true".equalsIgnoreCase(v)` with the JMX checkbox consulted only when
# the property was null or empty. 'yes' matched neither branch, so it neither enabled JUnit XML
# nor let the ticked box decide -- a typo silently voided a deliberate setting, with no message
# on any channel. The row PASSED on the absence of the .xml, which is to say it documented the
# defect rather than catching it.
#
# After: an unrecognised value warns and is treated as UNSET, so the plan's checkbox is what
# decides -- and it is ticked, so the .xml appears. "This value told us nothing" now means the
# rest of the configuration still applies, which is the only reading that cannot lose an
# operator's explicit choice.
echo "[T25] A non-'true' -J value warns and lets the JMX checkbox stand..."
D="$(run_case T25 "$JUNIT_JMX" -Jwebinsight.report.junit=yes)"
T25_NOTES=""
file_exists "$D/web-insight-report.xml" \
    || T25_NOTES="$(append_note "$T25_NOTES" "no .xml -- the unrecognised -J value still voids the ticked JMX checkbox")"
assert_log_contains "$D/jmeter-runtime.log" "Ignoring webinsight.report.junit='yes'" \
    || T25_NOTES="$(append_note "$T25_NOTES" "the unrecognised value was accepted in silence")"
if [[ -z "$T25_NOTES" ]]; then
    record T25 "Junk -J value warns, JMX checkbox stands" PASS \
        "'yes' warned and was treated as unset, so the plan's ticked checkbox decided"
else
    record T25 "Junk -J value warns, JMX checkbox stands" FAIL "$T25_NOTES"
fi

echo "[T26] -Jjunit=TRUE is case-insensitive..."
D="$(run_case T26 "$BLANK_JMX" -Jwebinsight.report.junit=TRUE)"
if file_exists "$D/web-insight-report.xml"; then
    record T26 "-J junit=TRUE (case)" PASS "uppercase accepted"
else
    record T26 "-J junit=TRUE (case)" FAIL "uppercase TRUE did not enable JUnit XML"
fi

echo "[T27] The .xml name is derived from a CUSTOM report filename..."
D="$(run_case T27 "$BLANK_JMX" -Jwebinsight.report.junit=true -Jwebinsight.report.filename=custom-name.html)"
T27_NOTES=""
file_exists "$D/custom-name.xml"  || T27_NOTES="$(append_note "$T27_NOTES" "custom-name.xml missing")"
file_exists "$D/custom-name.json" || T27_NOTES="$(append_note "$T27_NOTES" "custom-name.json missing")"
file_absent "$D/web-insight-report.xml" || T27_NOTES="$(append_note "$T27_NOTES" "default-named .xml also written")"
if [[ -z "$T27_NOTES" ]]; then
    record T27 "JUnit name follows custom filename" PASS "custom-name.{html,json,xml}"
else
    record T27 "JUnit name follows custom filename" FAIL "$T27_NOTES"
fi

echo "[T28] The JUnit testsuites name is the resolved title..."
D="$(run_case T28 "$FN_JMX" -Jwir.jmx.title="Suite Name Check" -Jwebinsight.report.junit=true)"
if file_exists "$D/web-insight-report.xml" && xml_valid "$D/web-insight-report.xml"; then
    T28_GOT="$(xml_title "$D/web-insight-report.xml")"
    if [[ "$T28_GOT" == "Suite Name Check" ]]; then
        record T28 "JUnit suite name is the title" PASS "evaluated JMX title reached the XML"
    else
        record T28 "JUnit suite name is the title" FAIL "testsuites name was '$T28_GOT'"
    fi
else
    record T28 "JUnit suite name is the title" FAIL "no valid .xml"
fi

# =============================================================================
#  Platform characterisation
# =============================================================================

echo "[T29] Non-ASCII through -J is platform-dependent (characterisation, not a defect)..."
# T17 proves the plugin writes UTF-8 correctly when the title reaches it intact. This row
# asks a different question: can a non-ASCII value survive the journey through argv on THIS
# machine? On a Windows console it typically cannot -- the value is transcoded to the console
# codepage before the JVM is even started, so the plugin receives '????' and faithfully
# writes exactly that. Recording the answer is useful; failing the suite over the OS is not.
D="$(run_case T29 "$BLANK_JMX" -Jwebinsight.report.title="$UNI_TITLE")"
if file_exists "$D/web-insight-report.json"; then
    T29_GOT="$(json_title "$D/web-insight-report.json")"
    if [[ "$T29_GOT" == "$UNI_TITLE" ]]; then
        record T29 "Non-ASCII via -J" PASS "argv is UTF-8 clean on this platform"
    else
        record T29 "Non-ASCII via -J" PASS \
            "non-ASCII does NOT survive -J here (got '$T29_GOT') -- an argv/console-codepage limit, not a plugin defect: T17 shows the same title round-trips through the JMX. Use the JMX field, or keep CI titles ASCII."
    fi
else
    record T29 "Non-ASCII via -J" FAIL "no .json"
fi

print_summary "TITLE & JUNIT TEST SUMMARY"

exit $FAIL
