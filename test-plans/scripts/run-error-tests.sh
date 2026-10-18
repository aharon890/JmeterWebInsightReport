#!/usr/bin/env bash
# =============================================================================
#  Web Insight Report — Error Handling & Negative Scenario Tests
#  Tests N01-N09: robustness under edge-case and invalid inputs
#
#  Assumes the plugin is already deployed (run ./scripts/build-and-deploy.sh).
# =============================================================================
set -uo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../../scripts/lib/common.sh"

require_jmeter

RESULTS_BASE="$RESULTS_DIR/error-tests"
SINGLE="$TEST_PLANS/single-sampler-test.jmx"

print_banner "Web Insight Report — Error Handling & Negative Tests"

mkdir -p "$RESULTS_BASE"

# Fresh directory per test — these tests assert on file presence, so stale
# artifacts from a previous run would mask a genuine failure.
fresh_dir() {
    rm -rf "$1"
    mkdir -p "$1"
}

# =============================================================================
# N01: Zero/near-zero samples (duration=1, 1 thread)
# =============================================================================
echo "[N01] Zero/near-zero samples (duration=1, 1 thread) ..."
N01_DIR="$RESULTS_BASE/N01"
fresh_dir "$N01_DIR"

run_jmeter -n -t "$SINGLE" -l "$N01_DIR/results.jtl" \
    -Jthreads=1 -Jduration=1 -Jrampup=0 \
    -Jwebinsight.report.output="$N01_DIR" \
    -Jwebinsight.report.filename=N01-report.html \
    -Jwebinsight.report.title=N01-ZeroSamples \
    > "$N01_DIR/jmeter-stdout.log" 2>&1

N01_STATUS="PASS"
N01_NOTE=""

if ! file_exists "$N01_DIR/N01-report.html"; then
    N01_STATUS="FAIL"; N01_NOTE="HTML report not generated"
elif ! file_size_gt "$N01_DIR/N01-report.html" 1000; then
    N01_STATUS="FAIL"; N01_NOTE="HTML report suspiciously small"
fi

if ! file_exists "$N01_DIR/N01-report.json"; then
    N01_STATUS="FAIL"; N01_NOTE="$(append_note "$N01_NOTE" "JSON not generated")"
fi

if log_has_stacktrace "$N01_DIR/jmeter-stdout.log"; then
    N01_STATUS="FAIL"; N01_NOTE="$(append_note "$N01_NOTE" "Stack trace in log")"
fi

record "N01" "Zero/near-zero samples" "$N01_STATUS" "$N01_NOTE"

# =============================================================================
# N02: Invalid annotations JSON — graceful handling
# =============================================================================
echo ""
echo "[N02] Invalid annotations JSON ..."
N02_DIR="$RESULTS_BASE/N02"
fresh_dir "$N02_DIR"

# Broken JSON at the auto-detected annotations location.
echo '{this is broken!!!' > "$N02_DIR/report-annotations.json"

run_jmeter -n -t "$SINGLE" -l "$N02_DIR/results.jtl" \
    -Jthreads=2 -Jduration=5 -Jrampup=1 \
    -Jwebinsight.report.output="$N02_DIR" \
    -Jwebinsight.report.filename=N02-report.html \
    -Jwebinsight.report.title=N02-BadAnnotations \
    > "$N02_DIR/jmeter-stdout.log" 2>&1

N02_STATUS="PASS"
N02_NOTE=""

if ! file_exists "$N02_DIR/N02-report.html"; then
    N02_STATUS="FAIL"; N02_NOTE="HTML not generated despite bad annotations"
elif ! file_size_gt "$N02_DIR/N02-report.html" 1000; then
    N02_STATUS="FAIL"; N02_NOTE="HTML suspiciously small"
fi

if ! file_exists "$N02_DIR/N02-report.json"; then
    N02_STATUS="FAIL"; N02_NOTE="$(append_note "$N02_NOTE" "JSON not generated")"
fi

if grep -qi "Failed to load annotations" "$N02_DIR/jmeter-stdout.log" 2>/dev/null; then
    N02_NOTE="$(append_note "$N02_NOTE" "Annotations warning logged (good)")"
fi

record "N02" "Invalid annotations JSON" "$N02_STATUS" "$N02_NOTE"

# =============================================================================
# N03: Missing baseline.jtl reference
# =============================================================================
echo ""
echo "[N03] Missing baseline.jtl reference ..."
N03_DIR="$RESULTS_BASE/N03"
fresh_dir "$N03_DIR"

run_jmeter -n -t "$SINGLE" -l "$N03_DIR/results.jtl" \
    -Jthreads=2 -Jduration=5 -Jrampup=1 \
    -Jwebinsight.report.output="$N03_DIR" \
    -Jwebinsight.report.filename=N03-report.html \
    -Jwebinsight.report.title=N03-MissingBaseline \
    -Jwebinsight.report.baseline=/nonexistent/fake.jtl \
    > "$N03_DIR/jmeter-stdout.log" 2>&1

N03_STATUS="PASS"
N03_NOTE=""

if ! file_exists "$N03_DIR/N03-report.html"; then
    N03_STATUS="FAIL"; N03_NOTE="HTML not generated"
elif ! file_size_gt "$N03_DIR/N03-report.html" 1000; then
    N03_STATUS="FAIL"; N03_NOTE="HTML suspiciously small"
fi

if ! file_exists "$N03_DIR/N03-report.json"; then
    N03_STATUS="FAIL"; N03_NOTE="$(append_note "$N03_NOTE" "JSON not generated")"
fi

# No Compare tab expected, since the baseline is missing.
#
# Look for the marker in the serialized payload, not for the tab markup. Every report
# ships the Compare tab hidden and inlines the report.js that references it, so
# "tab-compare" matches a report with no baseline at all — this branch reported
# "Compare tab unexpectedly present" on every single run while still passing, which
# is worse than no check: a contradictory note trains you to ignore it.
# The escaped \"hasComparison\":true appears only when a baseline was actually applied.
if file_exists "$N03_DIR/N03-report.html"; then
    if assert_contains "$N03_DIR/N03-report.html" '\"hasComparison\":true'; then
        N03_STATUS="FAIL"
        N03_NOTE="$(append_note "$N03_NOTE" "comparison data present despite a missing baseline")"
    else
        N03_NOTE="$(append_note "$N03_NOTE" "No comparison data (correct)")"
    fi
fi

if log_has_stacktrace "$N03_DIR/jmeter-stdout.log"; then
    N03_STATUS="FAIL"; N03_NOTE="$(append_note "$N03_NOTE" "Stack trace in log")"
fi

record "N03" "Missing baseline.jtl reference" "$N03_STATUS" "$N03_NOTE"

# =============================================================================
# N04: Very long report title (500+ chars)
# =============================================================================
echo ""
echo "[N04] Very long report title (500+ chars) ..."
N04_DIR="$RESULTS_BASE/N04"
fresh_dir "$N04_DIR"

# Build the title in bash rather than shelling out to python3.
LONG_TITLE="LongTitle_"
for _ in $(seq 1 49); do LONG_TITLE+="ABCDEFGHIJ"; done

run_jmeter -n -t "$SINGLE" -l "$N04_DIR/results.jtl" \
    -Jthreads=2 -Jduration=5 -Jrampup=1 \
    -Jwebinsight.report.output="$N04_DIR" \
    -Jwebinsight.report.filename=N04-report.html \
    -Jwebinsight.report.title="$LONG_TITLE" \
    > "$N04_DIR/jmeter-stdout.log" 2>&1

N04_STATUS="PASS"
N04_NOTE=""

if ! file_exists "$N04_DIR/N04-report.html"; then
    N04_STATUS="FAIL"; N04_NOTE="HTML not generated"
elif ! file_size_gt "$N04_DIR/N04-report.html" 1000; then
    N04_STATUS="FAIL"; N04_NOTE="HTML suspiciously small"
fi

if ! file_exists "$N04_DIR/N04-report.json"; then
    N04_STATUS="FAIL"; N04_NOTE="$(append_note "$N04_NOTE" "JSON not generated")"
fi

if file_exists "$N04_DIR/N04-report.html"; then
    if assert_contains "$N04_DIR/N04-report.html" "LongTitle_"; then
        N04_NOTE="$(append_note "$N04_NOTE" "Long title present in HTML")"
    else
        N04_NOTE="$(append_note "$N04_NOTE" "Title not found in HTML")"
    fi
fi

record "N04" "Very long report title" "$N04_STATUS" "$N04_NOTE"

# =============================================================================
# N05: Empty sampler name in JTL (via the -g -o pipeline)
# =============================================================================
echo ""
echo "[N05] Empty sampler name in JTL ..."
N05_DIR="$RESULTS_BASE/N05"
fresh_dir "$N05_DIR"

# All standard JMeter columns — the -g pipeline requires responseMessage,
# threadName, dataType, etc. even when the label is blank.
cat > "$N05_DIR/empty-label.jtl" << 'JTLEOF'
timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success,failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect
1700000000000,100,,200,OK,Thread-1,text,true,,512,64,1,1,null,50,0,10
1700000000100,150,Normal,200,OK,Thread-1,text,true,,1024,128,1,1,null,60,0,12
1700000000200,200,,200,OK,Thread-1,text,true,,768,96,1,1,null,55,0,11
1700000000300,120,Normal,500,Internal Server Error,Thread-1,text,false,,256,64,1,1,null,45,0,8
1700000000400,180,,200,OK,Thread-1,text,true,,640,80,1,1,null,52,0,9
JTLEOF

run_jmeter -g "$N05_DIR/empty-label.jtl" -o "$N05_DIR/report" \
    -Jwebinsight.report.title=N05-EmptyLabel \
    -Jwebinsight.report.filename=N05-report.html \
    > "$N05_DIR/jmeter-stdout.log" 2>&1

N05_STATUS="PASS"
N05_NOTE=""
N05_REPORT_DIR="$N05_DIR/report"

if ! file_exists "$N05_REPORT_DIR/N05-report.html"; then
    # -g -o may not pick up the -J filename override; the default name is fine.
    if file_exists "$N05_REPORT_DIR/web-insight-report.html"; then
        N05_NOTE="Report generated as default filename"
    else
        N05_STATUS="FAIL"; N05_NOTE="HTML not generated from empty-label JTL"
    fi
fi

if ! file_exists "$N05_REPORT_DIR/N05-report.json" && ! file_exists "$N05_REPORT_DIR/web-insight-report.json"; then
    N05_STATUS="FAIL"; N05_NOTE="$(append_note "$N05_NOTE" "JSON not generated")"
fi

if log_has_stacktrace "$N05_DIR/jmeter-stdout.log"; then
    N05_STATUS="FAIL"; N05_NOTE="$(append_note "$N05_NOTE" "Stack trace in log")"
fi

record "N05" "Empty sampler name in JTL" "$N05_STATUS" "$N05_NOTE"

# =============================================================================
# N06: Report overwrite (same filename, no timestamp)
# =============================================================================
echo ""
echo "[N06] Report overwrite (same filename) ..."
N06_DIR="$RESULTS_BASE/N06"
fresh_dir "$N06_DIR"

run_jmeter -n -t "$SINGLE" -l "$N06_DIR/results-run1.jtl" \
    -Jthreads=2 -Jduration=5 -Jrampup=1 \
    -Jwebinsight.report.output="$N06_DIR" \
    -Jwebinsight.report.filename=overwrite-test.html \
    -Jwebinsight.report.title=N06-Run1-First \
    > "$N06_DIR/jmeter-stdout-run1.log" 2>&1

FIRST_MD5="$(file_md5 "$N06_DIR/overwrite-test.html")"

run_jmeter -n -t "$SINGLE" -l "$N06_DIR/results-run2.jtl" \
    -Jthreads=2 -Jduration=5 -Jrampup=1 \
    -Jwebinsight.report.output="$N06_DIR" \
    -Jwebinsight.report.filename=overwrite-test.html \
    -Jwebinsight.report.title=N06-Run2-Second \
    > "$N06_DIR/jmeter-stdout-run2.log" 2>&1

N06_STATUS="PASS"
N06_NOTE=""

if ! file_exists "$N06_DIR/overwrite-test.html"; then
    N06_STATUS="FAIL"; N06_NOTE="HTML not generated on second run"
else
    SECOND_MD5="$(file_md5 "$N06_DIR/overwrite-test.html")"

    if [[ "$FIRST_MD5" == "$SECOND_MD5" && "$FIRST_MD5" != "none" ]]; then
        N06_NOTE="File not overwritten (same hash) -- may be coincidence with identical data"
    fi

    if assert_contains "$N06_DIR/overwrite-test.html" "N06-Run2-Second"; then
        N06_NOTE="$(append_note "$N06_NOTE" "Second run title present (correct overwrite)")"
    elif assert_contains "$N06_DIR/overwrite-test.html" "N06-Run1-First"; then
        N06_STATUS="FAIL"; N06_NOTE="$(append_note "$N06_NOTE" "First run title still present (not overwritten)")"
    fi

    HTML_COUNT="$(find "$N06_DIR" -maxdepth 1 -name "overwrite-test*.html" | wc -l | tr -d ' ')"
    if [[ "$HTML_COUNT" -gt 1 ]]; then
        N06_STATUS="FAIL"; N06_NOTE="$(append_note "$N06_NOTE" "Multiple HTML files found ($HTML_COUNT)")"
    fi
fi

record "N06" "Report overwrite" "$N06_STATUS" "$N06_NOTE"

# =============================================================================
# N07: Empty report title property — should fall back to the default
# =============================================================================
echo ""
echo "[N07] Empty report title property ..."
N07_DIR="$RESULTS_BASE/N07"
fresh_dir "$N07_DIR"

run_jmeter -n -t "$SINGLE" -l "$N07_DIR/results.jtl" \
    -Jthreads=2 -Jduration=5 -Jrampup=1 \
    -Jwebinsight.report.output="$N07_DIR" \
    -Jwebinsight.report.filename=N07-report.html \
    -Jwebinsight.report.title= \
    > "$N07_DIR/jmeter-stdout.log" 2>&1

N07_STATUS="PASS"
N07_NOTE=""

if ! file_exists "$N07_DIR/N07-report.html"; then
    N07_STATUS="FAIL"; N07_NOTE="HTML not generated with empty title"
elif ! file_size_gt "$N07_DIR/N07-report.html" 1000; then
    N07_STATUS="FAIL"; N07_NOTE="HTML suspiciously small"
fi

if ! file_exists "$N07_DIR/N07-report.json"; then
    N07_STATUS="FAIL"; N07_NOTE="$(append_note "$N07_NOTE" "JSON not generated")"
fi

if file_exists "$N07_DIR/N07-report.html"; then
    if assert_contains "$N07_DIR/N07-report.html" "JMeter Web Insight Report"; then
        N07_NOTE="$(append_note "$N07_NOTE" "Default title used (correct)")"
    else
        N07_NOTE="$(append_note "$N07_NOTE" "Default title not found")"
    fi
fi

record "N07" "Empty report title" "$N07_STATUS" "$N07_NOTE"

# =============================================================================
# N08: A well-formed annotations file whose RULES are broken
#
# N02 covers a file that will not parse. It cannot reach the evaluator at all —
# the loader gives up before a rule exists — so the whole family of "the JSON is
# fine, the rule inside it is not" was uncovered end to end.
#
# That is exactly where the code=regex:[ crash lived: the file parsed, the rule
# parsed, and the pattern blew up inside the per-error matching loop, out through
# AssertionEvaluator, taking report generation with it. Every unit and parity test
# in the repo passed. Nothing here ran, because nothing here declares a bad rule.
#
# The docs promise "none of it is fatal — the report is still generated", and that
# is an end-to-end property by construction: it is a claim about a file existing
# after a run. So it is asserted where it can fail, on the artifacts.
#
# The one healthy rule is the other half. Five broken rules must be isolated to
# themselves; a loader that discarded the whole array on the first bad entry would
# still write the report and still look like a pass without it. Do not trim it as
# redundant — gate.passed == 1 is the only assertion here that proves the evaluator
# was reached at all.
#
# Proven to fail on the bug: reverting MetricRef to the pre-fix lazy compile and
# redeploying makes N08 fail with "HTML not generated despite unreadable rules;
# JSON not generated", while N01-N07 all still pass. That is what the scenario is
# worth — not that it passes today.
# =============================================================================
echo ""
echo "[N08] Well-formed annotations, broken rules ..."
N08_DIR="$RESULTS_BASE/N08"
fresh_dir "$N08_DIR"

# Valid JSON. Every rule in it is individually unreadable, one per rejection path
# the code has — the taxonomy is the point, not the count:
#
#   1. a pattern that will not compile            MetricRef.parse
#   2. a selector key that is not a key           MetricRef.parse
#   3. a class= token outside the closed set      MetricRef.classToken
#   4. a metric that does not exist               MetricRef.parse, before any selector
#   5. a target pattern that will not compile     AssertionEvaluator, at resolution
#
# 5 is deliberately not a parse-time rejection. It is caught at a different site,
# around target resolution, which is the catch that existed and was assumed to cover
# the metric lookup two lines below it — that assumption is what the crash was. A
# scenario that exercised only parse-time paths would have gone green on the bug.
write_n08_rules() {
    cat > "$1" <<'JSON'
{
  "assertions": [
    {
      "id": "n08-bad-regex",
      "description": "Uncompilable pattern — INVALID at parse, never an exception",
      "target": { "scope": "overall" },
      "metric": "errorRate:code=regex:[",
      "fail": { "gt": 0 }
    },
    {
      "id": "n08-bad-selector",
      "description": "Selector key that is not class= or code=",
      "target": { "scope": "overall" },
      "metric": "errorRate:clss=nonProtocol",
      "fail": { "gt": 0 }
    },
    {
      "id": "n08-bad-class",
      "description": "class= is a closed vocabulary and this is not in it",
      "target": { "scope": "overall" },
      "metric": "errorRate:class=funcional",
      "fail": { "gt": 0 }
    },
    {
      "id": "n08-bad-metric",
      "description": "No such metric",
      "target": { "scope": "overall" },
      "metric": "responseTimeish",
      "fail": { "gt": 0 }
    },
    {
      "id": "n08-bad-target",
      "description": "Unreadable TARGET rather than metric — a different catch site",
      "target": { "match": "regex:[" },
      "metric": "p95",
      "fail": { "gt": 0 }
    },
    {
      "id": "n08-healthy",
      "description": "Must still be judged, alongside five that cannot be",
      "target": { "scope": "overall" },
      "metric": "throughput",
      "fail": { "lt": 0.001 }
    }
  ]
}
JSON
}

write_n08_rules "$N08_DIR/report-annotations.json"

# WIR_RUNTIME_LOG puts JMeter's own log4j2 output in this directory. Without it,
# -j is not passed and log4j2 writes to jmeter.log in the runner's CWD — so the
# "caught and logged" half of the check below would grep a file the exception was
# never written to. jmeter-runtime.log rather than jmeter.log, because the stdout
# capture already owns that name and one would truncate the other.
#
# Set in a subshell rather than as a `VAR=value cmd` prefix. run_jmeter is a shell FUNCTION,
# and an assignment prefix on a function persists in the calling shell after it returns —
# unlike on an external command. Nothing is wrong today because N09 overwrites it, but add an
# N10 and it would silently inherit N09's path and grep the wrong file for a stack trace.
# (`env VAR=… run_jmeter` is not the fix either: env execs a binary and cannot see a function.)
(
  export WIR_RUNTIME_LOG="$N08_DIR/jmeter-runtime.log"
  run_jmeter -n -t "$SINGLE" -l "$N08_DIR/results.jtl" \
      -Jthreads=2 -Jduration=5 -Jrampup=1 \
      -Jwebinsight.report.output="$N08_DIR" \
      -Jwebinsight.report.filename=N08-report.html \
      -Jwebinsight.report.title=N08-BrokenRules \
      > "$N08_DIR/jmeter-stdout.log" 2>&1
)
N08_RC=$?

N08_STATUS="PASS"
N08_NOTE=""

# Five rules the FILE got wrong means the gate was not built from what the file declared, so
# the run fails -- the same category as an unwritable output directory, under the same flag.
# The artifacts below are still asserted: failing a build must not cost the report that
# explains why.
if [[ "$N08_RC" -eq 0 ]]; then
    N08_STATUS="FAIL"
    N08_NOTE="$(append_note "$N08_NOTE" "five unrunnable rules left the exit code at 0")"
fi

if ! file_exists "$N08_DIR/N08-report.html"; then
    N08_STATUS="FAIL"; N08_NOTE="HTML not generated despite unreadable rules"
elif ! file_size_gt "$N08_DIR/N08-report.html" 1000; then
    N08_STATUS="FAIL"; N08_NOTE="HTML suspiciously small"
fi

if ! file_exists "$N08_DIR/N08-report.json"; then
    N08_STATUS="FAIL"; N08_NOTE="$(append_note "$N08_NOTE" "JSON not generated")"
else
    # The verdict, not just the file. Five rules unreadable and the sixth judged:
    # a loader that dropped the array on the first bad entry, or an evaluator that
    # counted a rule it could not read as a pass, writes a report either way.
    # gate.notJudged.invalidRule, not gate.invalid. INVALID was a STATUS that outranked FAIL,
    # so a rule the file got wrong failed a build with no measured number having exceeded
    # anything. It is a reason now and it gates nothing -- but it is still counted on its own,
    # precisely so a pipeline that wants a broken rule to stop the build can ask for it.
    if ! grep -qE '"invalidRule"[[:space:]]*:[[:space:]]*5([,[:space:]}]|$)' "$N08_DIR/N08-report.json"; then
        N08_STATUS="FAIL"
        N08_NOTE="$(append_note "$N08_NOTE" "gate.notJudged.invalidRule is not 5 -- broken rules were not counted")"
    fi
    if ! grep -qE '"passed"[[:space:]]*:[[:space:]]*1([,[:space:]}]|$)' "$N08_DIR/N08-report.json"; then
        N08_STATUS="FAIL"
        N08_NOTE="$(append_note "$N08_NOTE" "the healthy rule was not evaluated alongside the broken ones")"
    fi
fi

# The crash surfaced two ways at once — a stack trace and a missing file — and the
# artifact checks above already cover the second. This covers the variant they
# cannot see: an exception caught and logged, leaving a complete set of artifacts
# and a rule silently unjudged. That lands in JMeter's own log via log4j2, which is
# why the run above sets WIR_RUNTIME_LOG; grepping only the stdout capture would
# have made this check a duplicate of the file assertions rather than an addition.
for N08_LOG in "$N08_DIR/jmeter-stdout.log" "$N08_DIR/jmeter-runtime.log"; do
    if [[ -f "$N08_LOG" ]] && log_has_stacktrace "$N08_LOG"; then
        N08_STATUS="FAIL"
        N08_NOTE="$(append_note "$N08_NOTE" "Stack trace in $(basename "$N08_LOG")")"
    fi
done

record "N08" "Broken rules in valid annotations" "$N08_STATUS" "$N08_NOTE"

# =============================================================================
# N09: the same broken rules, through the -g -o exporter
#
# Not symmetry for its own sake — the exporter fails differently in three ways:
#
#   * An uncaught exception PROPAGATES here and JMeter exits 1, where the listener
#     swallows it and leaves no report. N08 asserts on files, which is the
#     listener's failure mode; the exit code is this path's, and is asserted below.
#
#     Note what that does NOT explain: the non-zero exit this stage asserts. A
#     broken rule is not an uncaught exception — it goes through NonZeroExit on
#     both paths, deliberately, because throwing renders a typo'd metric name as
#     twelve frames of JMeter internals and the stack-trace check at the bottom of
#     this stage could then never fire again. So the exit code here is arranged the
#     same way the listener arranges it, and the two paths differ in nothing.
#   * failonerror now defaults to true, so a partial write is a non-zero exit
#     rather than a quiet omission.
#   * ReportData is rebuilt by re-parsing the JTL instead of coming from
#     LiveStatisticsEngine. "Same evaluator" is true of the evaluator and not of
#     what it is handed.
#
# The docs make the not-fatal promise for both paths without qualification, so it
# is asserted on both.
#
# Two traps, both encoded below. The exporter skips when a listener report is
# already present, so the output directory must be its own; and -g -o demands an
# EMPTY directory, which is why the annotations file lives outside it and is named
# explicitly rather than auto-detected. Use `rm -rf dir`, never `dir/*` — the
# publish lock is a dotfile and the glob misses it, leaving a directory that looks
# empty and is refused.
# =============================================================================
echo ""
echo "[N09] Broken rules through the -g -o exporter ..."
N09_DIR="$RESULTS_BASE/N09"
fresh_dir "$N09_DIR"
N09_OUT="$N09_DIR/report"     # deliberately not created — -g -o wants it absent or empty

# The run must HAVE errors: the crash was inside the per-error matching loop, so a
# clean JTL never enters it and would pass against the bug. One of each class.
cat > "$N09_DIR/with-errors.jtl" << 'JTLEOF'
timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success,failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect
1700000000000,100,Checkout,200,OK,Thread-1,text,true,,512,64,1,1,http://x/checkout,50,0,10
1700000000100,150,Checkout,503,Service Unavailable,Thread-1,text,false,,1024,128,1,1,http://x/checkout,60,0,12
1700000000200,200,Checkout,200,OK,Thread-1,text,false,Assertion failed,768,96,1,1,http://x/checkout,55,0,11
1700000000300,120,Checkout,Non HTTP response code: java.net.ConnectException,Non HTTP response message,Thread-1,text,false,,0,0,1,1,http://x/checkout,0,0,0
1700000000400,180,Browse,200,OK,Thread-1,text,true,,640,80,1,1,http://x/browse,52,0,9
JTLEOF

# Outside the output directory: -g -o refuses a non-empty one, so it cannot be
# auto-detected from there and must be named.
write_n08_rules "$N09_DIR/rules.json"

# Subshell for the same reason as N08 — see the note there.
(
  export WIR_RUNTIME_LOG="$N09_DIR/jmeter-runtime.log"
  run_jmeter -g "$N09_DIR/with-errors.jtl" -o "$N09_OUT" \
      -Jwebinsight.report.annotations="$N09_DIR/rules.json" \
      -Jwebinsight.report.title=N09-BrokenRulesExporter \
      -Jwebinsight.report.filename=N09-report.html \
      > "$N09_DIR/jmeter-stdout.log" 2>&1
)
N09_EXIT=$?

N09_STATUS="PASS"
N09_NOTE=""

# This path's own failure mode, and the reasoning here has been INVERTED deliberately.
#
# It read: every artifact was writable and no rule failed to WRITE, only to be READ, so
# failonerror has nothing to fire on and the run must exit 0. That is exactly the gap this
# stage now guards. A rule that could not be read means the gate was not built from what the
# file declared, which is the plugin failing to do what it was told -- and that is what
# failonerror has always meant. Five unreadable rules and a green exit is a gate that stopped
# gating and told nobody.
#
# N08 is the same claim on the listener path. Both exist so the two ingestion paths cannot
# answer differently for the same annotations file, which is the way a change like this gets
# half-done.
if [[ "$N09_EXIT" -eq 0 ]]; then
    N09_STATUS="FAIL"
    N09_NOTE="exporter exited 0 with five rules it could not read"
fi

# -g -o may not honour the -J filename override; the default name is equally valid.
N09_HTML="$N09_OUT/N09-report.html"
N09_JSON="$N09_OUT/N09-report.json"
if ! file_exists "$N09_HTML"; then
    N09_HTML="$N09_OUT/web-insight-report.html"
    N09_JSON="$N09_OUT/web-insight-report.json"
fi

if ! file_exists "$N09_HTML"; then
    N09_STATUS="FAIL"
    N09_NOTE="$(append_note "$N09_NOTE" "HTML not generated despite unreadable rules")"
elif ! file_size_gt "$N09_HTML" 1000; then
    N09_STATUS="FAIL"; N09_NOTE="$(append_note "$N09_NOTE" "HTML suspiciously small")"
fi

if ! file_exists "$N09_JSON"; then
    N09_STATUS="FAIL"; N09_NOTE="$(append_note "$N09_NOTE" "JSON not generated")"
else
    if ! grep -qE '"invalidRule"[[:space:]]*:[[:space:]]*5([,[:space:]}]|$)' "$N09_JSON"; then
        N09_STATUS="FAIL"
        N09_NOTE="$(append_note "$N09_NOTE" "gate.notJudged.invalidRule is not 5 on the exporter path")"
    fi
    if ! grep -qE '"passed"[[:space:]]*:[[:space:]]*1([,[:space:]}]|$)' "$N09_JSON"; then
        N09_STATUS="FAIL"
        N09_NOTE="$(append_note "$N09_NOTE" "the healthy rule was not evaluated on the exporter path")"
    fi
fi

for N09_LOG in "$N09_DIR/jmeter-stdout.log" "$N09_DIR/jmeter-runtime.log"; do
    if [[ -f "$N09_LOG" ]] && log_has_stacktrace "$N09_LOG"; then
        N09_STATUS="FAIL"
        N09_NOTE="$(append_note "$N09_NOTE" "Stack trace in $(basename "$N09_LOG")")"
    fi
done

record "N09" "Broken rules via -g -o exporter" "$N09_STATUS" "$N09_NOTE"

print_summary "ERROR HANDLING TEST SUMMARY"

exit $FAIL
