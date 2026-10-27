#!/usr/bin/env bash
# =============================================================================
#  Web Insight Report — Test Execution Suite
#  Runs E2E scenarios and writes test-results/test-execution-report.csv
#
#  --suite selects how much to run:
#
#    --suite fixtures   the 11 scenarios Playwright opens         (~2.5 min)
#    --suite smoke      one 5s scenario, just proves JMeter loads (~20 s)
#    --suite full       all 19, incl. E20 external-data           (~4.5 min)  [default]
#
#  fixtures is FIXTURE_IDS minus CMP, which no scenario here produces —
#  generate-cmp-fixture.sh owns it. Do not "fix" the count by adding CMP.
#
#  --only E26,E27 narrows the suite to the ids listed, to regenerate just the
#  fixtures a targeted spec opens while developing. The others stay stale, so
#  global-setup refuses until WIR_SKIP_FIXTURE_CHECK=1 is set or they are
#  regenerated: the whole set is the gate's job (run-regression.sh). An id the
#  suite never ran fails the run.
#
#  The full set was ~15.5 minutes. Running scenarios concurrently (see below) and
#  dropping two stress scenarios that asserted only "a file appeared" took it to
#  ~4.5 without removing a single assertion.
#
#  Scenario durations are NOT shortened per suite. Several specs assert on
#  chart granularity and bucket counts, which are derived from test duration —
#  a "fast profile" that shortened E02 would change what the fixtures mean and
#  break those specs for reasons unrelated to the code under test.
#
#  Scenarios run CONCURRENTLY instead (WIR_JOBS, default 4), which shortens the
#  wall clock without touching a single duration. The fixture set is ~470s of
#  scenario time serial; run 4-up the critical path is E02 alone (120s + 20s
#  rampup), so the stage drops from ~8.6 min to ~3.
#
#  This is safe because of what these plans are: NO test plan contains an HTTP
#  sampler. They are timer-driven simulations, so there is no shared target to
#  contend for, each scenario owns its own test-results/<ID>/ directory, and the
#  JVMs spend most of their life asleep. The assert scenarios have ~100x headroom
#  either side of their thresholds (E10 passes at mean <= 3000ms, E11 fails at
#  mean <= 30ms), so CPU contention cannot flip a verdict.
#
#  WIR_JOBS=1 restores strict serial execution for when a result looks wrong and
#  you want concurrency off the table.
# =============================================================================
set -uo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../../scripts/lib/common.sh"

SUITE="${SUITE:-full}"
ONLY_IDS=""
ONLY_GIVEN=0
# A flag that takes a value refuses to be the last argument: `shift 2` with one positional left
# fails WITHOUT shifting, so a trailing --only (or --suite) spun this loop forever.
needs_value() {
    [[ $2 -ge 2 ]] || { echo "ERROR: $1 needs a value" >&2; exit 2; }
}
while [[ $# -gt 0 ]]; do
    case "$1" in
        --suite) needs_value "$1" $#; SUITE="$2"; shift 2 ;;
        --suite=*) SUITE="${1#*=}"; shift ;;
        --only) needs_value "$1" $#; ONLY_IDS="$2"; ONLY_GIVEN=1; shift 2 ;;
        --only=*) ONLY_IDS="${1#*=}"; ONLY_GIVEN=1; shift ;;
        -h|--help) sed -n '2,19p' "$0"; exit 0 ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done
# --only "" would read as no filter at all and run the whole suite: say so instead.
if [[ $ONLY_GIVEN -eq 1 && -z "${ONLY_IDS//[ ,]/}" ]]; then
    echo "ERROR: --only needs at least one scenario id (e.g. --only E26,E27)" >&2
    exit 2
fi

case "$SUITE" in
    fixtures|smoke|full) ;;
    *) echo "ERROR: --suite must be one of: fixtures, smoke, full (got '$SUITE')" >&2; exit 2 ;;
esac

# Which scenario IDs each suite runs. FIXTURE_IDS comes from common.sh and also
# contains CMP, which this script does not produce — generate-cmp-fixture.sh does.
SMOKE_IDS="E24 E30"

in_suite() {
    case "$SUITE" in
        full)     return 0 ;;
        fixtures) [[ " $FIXTURE_IDS " == *" $1 "* ]] ;;
        smoke)    [[ " $SMOKE_IDS "   == *" $1 "* ]] ;;
    esac
}

ONLY_IDS="${ONLY_IDS//,/ }"
if [[ " $ONLY_IDS " == *" CMP "* ]]; then
    echo "ERROR: --only CMP — no scenario here produces CMP; run generate-cmp-fixture.sh" >&2
    exit 2
fi
ONLY_RAN=""

should_run() {
    in_suite "$1" || return 1
    [[ -z "${ONLY_IDS// /}" ]] && return 0
    [[ " $ONLY_IDS " == *" $1 "* ]] || return 1
    ONLY_RAN="$ONLY_RAN $1"
}

require_jmeter

WIR_JOBS="${WIR_JOBS:-4}"
if ! [[ "$WIR_JOBS" =~ ^[1-9][0-9]*$ ]]; then
    echo "ERROR: WIR_JOBS must be a positive integer (got '$WIR_JOBS')" >&2
    exit 2
fi

CSV_REPORT="$RESULTS_DIR/test-execution-report.csv"

# One marker file per finished scenario (<id>.done). Used only to throttle launches — the
# results themselves are re-derived from disk by validate_results in the parent. Deriving
# them from disk is honest only because each scenario empties its directory before it runs
# (_execute_scenario): a report on disk afterwards is one this run wrote, never the previous
# cycle's. The one exception is a directory rm could not empty, and run_test_jmeter refuses
# to run over that; it leaves <id>.failed here, the only marker a verdict reads.
#
# Marker files rather than `kill -0` on tracked PIDs: a background child that has exited
# but not been reaped still answers `kill -0`, and `wait -n` (which would avoid the whole
# problem) is bash 4.3+, while this project still uses bash-3.2-safe idioms elsewhere —
# see the ${RESULTS[@]+"${RESULTS[@]}"} expansion in common.sh.
STATUS_DIR="$RESULTS_DIR/.scenario-status"

print_banner "Web Insight Report — Test Execution Suite (--suite $SUITE)"

# --- Build & deploy ---
# SKIP_BUILD lets an orchestrator that has already built and deployed (run-sanity.sh,
# run-regression.sh) avoid paying for it twice. Unset by default, so a direct run of
# this script still builds what it is about to test.
if [[ "${SKIP_BUILD:-0}" == "1" ]]; then
    echo "[BUILD] SKIP_BUILD=1 — using the already-deployed plugin ${PLUGIN_VERSION}"
    require_deployed_plugin
else
    build_plugin || exit 1
    deploy_plugin || exit 1
fi

mkdir -p "$RESULTS_DIR"
rm -rf "$STATUS_DIR"
mkdir -p "$STATUS_DIR"
echo "test_id,test_name,category,environment,jmx_file,status,report_generated,json_generated,notes,timestamp" > "$CSV_REPORT"

echo ""
echo "==============================================================="
echo "  RUNNING TESTS"
echo "==============================================================="
echo ""

# -----------------------------------------------------------------------------
#  validate_results <id> <name> <category> <jmx>
#  PASS requires both the HTML and JSON report to exist, and run_test_jmeter to
#  have called the HTML this run's.
# -----------------------------------------------------------------------------
validate_results() {
    local id="$1" name="$2" category="$3" jmx="$4"
    local dir="$RESULTS_DIR/$id"
    local status="PASS" notes="" html=false json=false

    if file_exists "$dir/$id-report.html"; then
        html=true
        # A report on disk that run_test_jmeter did not stamp is not this run's: rm could not
        # remove the previous cycle's (a Windows lock), and it refused to run over it, with the
        # reason in jmeter.log. Without this the files alone would read PASS on last cycle's.
        if [[ -e "$STATUS_DIR/$id.failed" ]]; then
            status="FAIL"; notes="the report on disk is not this run's — see $id/jmeter.log"
        fi
    else
        status="FAIL"; notes="HTML report not generated"
    fi

    if file_exists "$dir/$id-report.json"; then
        json=true
    else
        status="FAIL"; notes="$(append_note "$notes" "JSON not generated")"
    fi

    record "$id" "$name" "$status" "$notes"
    echo "$id,$name,$category,local,$jmx,$status,$html,$json,$notes,$(iso_timestamp)" >> "$CSV_REPORT"
}

# -----------------------------------------------------------------------------
#  _report_survived_rm <report> <log>
#  For E20, E26 and E27, which rm -rf their own directory rather than going through
#  run_test_jmeter: 0, with an ERROR in <log> and on stderr, when <report> is still
#  there after it. rm fails without a word under a Windows lock, `-g -o` then refuses
#  the non-empty directory, and the checks below would pass on the previous cycle's
#  report and stamp it as this JAR's (F3). Believe the directory, not rm: such a
#  scenario neither runs nor stamps, and records FAIL.
# -----------------------------------------------------------------------------
_report_survived_rm() {
    local report="$1" log="$2"
    [[ -e "$report" ]] || return 1
    echo "ERROR: could not remove the previous $report; not running over it" | tee "$log" >&2
    return 0
}

# -----------------------------------------------------------------------------
#  _execute_scenario — the JMeter half, run in a background subshell.
#
#  Deliberately does NOT call record or touch the CSV: both live in parent-shell
#  state (PASS/FAIL/RESULTS arrays) that a subshell cannot write back to, and
#  concurrent appends to one CSV would interleave. The parent re-derives every
#  verdict from disk afterwards via validate_results, which already worked that
#  way, so the summary and CSV come out byte-identical to the serial version.
# -----------------------------------------------------------------------------
_execute_scenario() {
    local id="$1" name="$2" jmx="$3" extra="$4" anno="$5"
    local dir="$RESULTS_DIR/$id"

    # The marker is written on EVERY exit path, not just the happy one. _await_slot
    # counts markers to decide when a slot frees up, so a scenario killed by a signal
    # or an OOM would otherwise never free its slot and the launcher would spin
    # forever — a runner that hangs is worse than one that fails, because a failure
    # at least tells you something. The parent judges pass/fail from the report files
    # on disk, and the directory is emptied before the run below, so a marker from a
    # dead scenario still produces an honest FAIL: its report is absent, not last
    # cycle's.
    trap ': > "$STATUS_DIR/$id.done"' EXIT

    # Every scenario starts from an EMPTY directory, as E20, E26, E27 and the family runners
    # already did. It used to remove results.jtl alone, so a scenario whose JMeter rendered
    # nothing left the previous cycle's <id>-report.html and .json in place, validate_results
    # (which only asks whether the files exist) recorded PASS on them, and run_test_jmeter
    # stamped them as rendered by the JAR now in lib/ext (F3). It also clears the inputs the
    # listener auto-detects in its output directory (report-annotations.json, baseline.jtl),
    # so a scenario never runs with an annotations file a previous one copied in.
    #
    # The JTL history stays true and is why results.jtl went first: JMeter APPENDS to an
    # existing -l file, so a rerun grew it with both runs' samples, `jmeter -g` and
    # verify-data-integrity.py disagreed with the HTML report (built from live samples,
    # not the JTL), and a data bug that was not there was reported. run_jmeter in common.sh
    # clears the -l file centrally too.
    #
    # rm can fail on Windows without a word. run_test_jmeter believes the directory over it
    # and refuses to run over a report still there; its non-zero status is left as a marker
    # for validate_results, which judges from disk and would otherwise see last cycle's files.
    rm -rf "$dir"
    mkdir -p "$dir"

    [[ -n "$anno" ]] && cp -f "$ANNOTATIONS_DIR/$anno" "$dir/report-annotations.json"

    # shellcheck disable=SC2086
    run_test_jmeter "$dir" "$jmx" "$id-report" "$name" $extra \
        || : > "$STATUS_DIR/$id.failed"
}

# Block until fewer than WIR_JOBS scenarios are in flight.
_await_slot() {
    if [[ $WIR_JOBS -le 1 ]]; then
        wait                     # serial: let the previous scenario finish entirely
        return 0
    fi
    local finished
    while :; do
        finished="$(find "$STATUS_DIR" -name '*.done' 2>/dev/null | wc -l)"
        [[ $(( LAUNCHED - finished )) -lt $WIR_JOBS ]] && return 0
        sleep 0.5
    done
}

# -----------------------------------------------------------------------------
#  run_test <id> <name> <category> <jmx> <extra_props> [annotations_file]
#  Registers the scenario and launches it; results are collected after the wait.
# -----------------------------------------------------------------------------
LAUNCHED=0
SPEC_IDS=(); SPEC_NAMES=(); SPEC_CATS=(); SPEC_JMX=()

run_test() {
    local id="$1" name="$2" category="$3" jmx="$4" extra="$5" anno="${6:-}"

    if ! should_run "$id"; then
        echo "[$id] $name — skipped (not in --suite $SUITE)"
        return 0
    fi

    SPEC_IDS+=("$id"); SPEC_NAMES+=("$name")
    SPEC_CATS+=("$category"); SPEC_JMX+=("$jmx")

    if [[ -n "$anno" ]]; then
        echo "[$id] $name (annotations: $anno) — started"
    else
        echo "[$id] $name — started"
    fi

    _await_slot
    _execute_scenario "$id" "$name" "$jmx" "$extra" "$anno" &
    LAUNCHED=$((LAUNCHED + 1))
}

# Called once, after every scenario has finished, in registration order — so the
# summary table and CSV read exactly as they did when this ran serially.
collect_results() {
    local i
    for (( i = 0; i < ${#SPEC_IDS[@]}; i++ )); do
        validate_results "${SPEC_IDS[$i]}" "${SPEC_NAMES[$i]}" \
                         "${SPEC_CATS[$i]}" "${SPEC_JMX[$i]}"
    done
}

run_test E01 "Happy Path Smoke"       functional    quick-smoke-test.jmx          "-Jthreads_browse=5 -Jthreads_purchase=5 -Jduration=30 -Jrampup=5"
run_test E02 "Full Load Test"         functional    full-load-test.jmx            "-Jthreads_browse=20 -Jthreads_shop=15 -Jthreads_api=10 -Jthreads_admin=5 -Jduration=120 -Jrampup=20"
run_test E03 "Error Grouping"         functional    error-grouping-test.jmx       "-Jthreads=20 -Jduration=30 -Jrampup=5"
run_test E04 "Single Sampler"         edge_case     single-sampler-test.jmx       "-Jthreads=5 -Jduration=30 -Jrampup=3"
run_test E05 "Many Samplers (20)"     edge_case     many-samplers-test.jmx        "-Jthreads=5 -Jduration=30 -Jrampup=5"
run_test E06 "Zero Errors"            edge_case     zero-errors-test.jmx          "-Jthreads=5 -Jduration=30 -Jrampup=3"
run_test E07 "100% Error Rate"        edge_case     all-errors-test.jmx           "-Jthreads=5 -Jduration=30 -Jrampup=3"
run_test E08 "Long Sampler Names"     edge_case     long-names-test.jmx           "-Jthreads=5 -Jduration=20 -Jrampup=3"
run_test E09 "CLI Properties"         configuration quick-smoke-test.jmx          "-Jthreads_browse=5 -Jthreads_purchase=5 -Jduration=30 -Jrampup=5 -Jwebinsight.report.title=CLI_Props_Test"
run_test E10 "Asserts Pass (Lenient)" configuration asserts-scenario-test.jmx         "-Jthreads=5 -Jduration=30 -Jrampup=3" asserts-pass.json
run_test E11 "Asserts Fail (Strict)"  configuration asserts-scenario-test.jmx         "-Jthreads=5 -Jduration=30 -Jrampup=3" asserts-strict.json
run_test E12 "Full Annotations"       configuration quick-smoke-test.jmx          "-Jthreads_browse=5 -Jthreads_purchase=5 -Jduration=30 -Jrampup=5" full-annotations.json
run_test E13 "Markers Only"           configuration quick-smoke-test.jmx          "-Jthreads_browse=5 -Jthreads_purchase=5 -Jduration=30 -Jrampup=5" markers-only.json
run_test E15 "Response Code Grouping" configuration response-code-grouping-test.jmx "-Jthreads=5 -Jduration=20 -Jrampup=2"
# E19 is the only Playwright fixture in external-data mode, and it exists so a browser can
# check the two things greppping the HTML cannot: that the report actually LOADS its payload
# and renders charts from it, and that the run-identity guard fires when the payload belongs
# to a different run. E20 below covers the same writer through the -g -o exporter but writes
# into report/, which is not the <ID>/<ID>-report.html shape the specs resolve.
run_test E19 "External Data (listener)" configuration single-sampler-test.jmx "-Jthreads=5 -Jduration=20 -Jrampup=2 -Jwebinsight.report.externaldata=true"
# E18 (100 threads / 120s) and E19 (20 threads / 60s) were removed. They cost ~3½
# minutes between them and asserted "an HTML and a JSON file appeared" — no Playwright
# spec opened either one. The thread counts bought nothing real: with no HTTP sampler
# in any test plan there is no load being generated, only more rows, and neither run
# came within reach of the 10 MB external-data threshold that would have made the volume
# meaningful. E20 below covers that path properly. E22 stays as the one scenario
# larger than a fixture.
run_test E22 "Report Stress"          regression    report-stress-test.jmx        "-Jthreads=8 -Jduration=60 -Jrampup=10"
run_test E24 "Minimal Data"           regression    single-sampler-test.jmx       "-Jthreads=1 -Jduration=5 -Jrampup=1"
# The only scenario carrying an "assertions" array. Its thresholds are extreme on purpose so
# that every rule state — PASS, WARN, FAIL, UNMATCHED, OBSERVED, DISABLED — renders the same
# way on any machine; the scenarios run 4-up, and a realistic limit would make a browser
# assertion depend on how loaded the runner was.
# E25 declares `unknown-metric` ON PURPOSE, so that one of every rule state renders in one run
# and the browser specs have something to assert against. Since a rule the file got wrong now
# fails the run under failonerror, this scenario would exit non-zero every time -- on a fixture
# that is correct. The opt-out is the fixture saying "the broken rule is the point", not a
# workaround: C33 is where the flag's real behaviour is proven, in both directions.
run_test E25 "Assertion Rules"         configuration asserts-scenario-test.jmx         "-Jthreads=5 -Jduration=30 -Jrampup=3 -Jwebinsight.report.failonerror=false" asserts.json

echo ""
echo "  Waiting for in-flight scenarios (WIR_JOBS=$WIR_JOBS) ..."
wait
collect_results
rm -rf "$STATUS_DIR"

# -----------------------------------------------------------------------------
#  E20: External data mode, via the -g -o exporter and a generated JTL.
#
#  This is what E18's 100 threads were gesturing at and never reached. In external
#  data mode the chart payload is written to web-insight-data.js and loaded with
#  <script src> instead of being inlined — a distinct code path, and one where a
#  failure is invisible, because a report that has silently lost its data still looks
#  like a report.
#
#  Uses FORCED mode (-Jwebinsight.report.externaldata=true) rather than trying to
#  exceed the external-data threshold. Measured: 210,000 samples (a 26 MB JTL) produce only
#  a 2.5 MB payload, so tripping the 10 MB default naturally would need roughly ten
#  times this input — a ~100 MB file to assert a branch. Forced mode reaches the same
#  writer deterministically in seconds. It is also the path with the interesting
#  behaviour: it streams to disk instead of materialising the payload as a String
#  inside the JMeter JVM.
#
#  NOT covered by this: the threshold comparison itself, which has no unit test
#  either. It is a numeric branch, so the exposure is small, but it is untested.
#
#  Also exercises the other ingestion path — the -g -o exporter re-parses the JTL
#  rather than working from live samples. generate-large-jtl.py seeds its RNG, so the
#  input is byte-identical every run.
#
#  Runs after the parallel block because it is not a `jmeter -n -t` scenario and does
#  not fit the launcher's shape.
# -----------------------------------------------------------------------------
if should_run E20; then
    E20_DIR="$RESULTS_DIR/E20"
    if ! require_python; then
        record E20 "External data mode (-g -o, generated JTL)" FAIL "no working Python 3"
        echo "E20,External data mode,external_data,local,generated.jtl,FAIL,false,false,no Python 3,$(iso_timestamp)" >> "$CSV_REPORT"
    else
        e20_status="PASS"; e20_note=""; e20_html=false; e20_json=false
        e20_report="$E20_DIR/report/web-insight-report.html"

        rm -rf "$E20_DIR"
        if _report_survived_rm "$e20_report" "$E20_DIR/jmeter.log"; then
            e20_status="FAIL"; e20_note="could not remove the previous report; not running over it"
        else
            mkdir -p "$E20_DIR"
            echo "[E20] External data mode — generating JTL ..."
            "$PYTHON" "$TEST_PLANS/scripts/generate-large-jtl.py" "$E20_DIR/large.jtl" \
                > "$E20_DIR/generate.log" 2>&1

            # -o demands a directory that does not already exist or is empty.
            echo "[E20] Running the -g -o exporter over it ..."
            run_jmeter -g "$E20_DIR/large.jtl" -o "$E20_DIR/report" \
                -Jwebinsight.report.title=E20-ExternalData \
                -Jwebinsight.report.externaldata=true \
                > "$E20_DIR/jmeter.log" 2>&1
        fi

        if file_exists "$e20_report"; then
            e20_html=true
        else
            e20_status="FAIL"; e20_note="HTML not generated by the -g -o exporter"
        fi
        if file_exists "$E20_DIR/report/web-insight-report.json"; then
            e20_json=true
        else
            e20_status="FAIL"; e20_note="$(append_note "$e20_note" "JSON not generated")"
        fi

        # The point of the scenario. Without this the run only proves the exporter
        # works on a big file, not that the external-data path was taken at all.
        if ! file_exists "$E20_DIR/report/web-insight-data.js"; then
            e20_status="FAIL"
            e20_note="$(append_note "$e20_note" "web-insight-data.js absent — forced external mode did nothing")"
        elif ! assert_contains "$e20_report" "web-insight-data.js"; then
            e20_status="FAIL"
            e20_note="$(append_note "$e20_note" "payload written but the report does not reference it")"
        fi

        if log_has_stacktrace "$E20_DIR/jmeter.log"; then
            e20_status="FAIL"; e20_note="$(append_note "$e20_note" "Stack trace in log")"
        fi

        record E20 "External data mode (-g -o, generated JTL)" "$e20_status" "$e20_note"
        echo "E20,External data mode,external_data,local,generated.jtl,$e20_status,$e20_html,$e20_json,$e20_note,$(iso_timestamp)" >> "$CSV_REPORT"
    fi
fi

# -----------------------------------------------------------------------------
#  E26 — Server metrics
#
#  The Server tab's fixture, and the only scenario built from wholly committed
#  inputs. That is forced, not a shortcut:
#
#    - a live run's runId is a fingerprint of testName|startTime|duration|counts,
#      so it is new on every execution, and ServerMetricsPayloadLoader REFUSES a
#      payload collected against a different one. A committed payload can never
#      be paired with a timer-driven run.
#    - collecting at run time does not rescue it either: a committed `file`
#      recording carries fixed epoch timestamps that share no milliseconds with a
#      live window, so coverage would be 0 and every KPI would land EMPTY.
#
#  So E26 is `-g -o` over the same committed JTL the goldens use, with its own
#  annotations, recording and payload under test-plans/server-metrics/. Every
#  number on the tab is therefore a constant a browser spec can assert on.
#
#  It does NOT pass -Jwebinsight.report.title: the report title IS the run's test
#  name on this path and is hashed into runId, so setting it would silently
#  withhold the server block from the report the spec then opens.
# -----------------------------------------------------------------------------
if should_run E26; then
    E26_DIR="$RESULTS_DIR/E26"
    E26_INPUTS="$TEST_PLANS/server-metrics"

    e26_status="PASS"; e26_note=""
    e26_report="$E26_DIR/E26-report.html"

    # The whole directory, not dir/* — the publish lock is a dotfile and `-g -o`
    # refuses a directory that is not empty.
    rm -rf "$E26_DIR"
    if _report_survived_rm "$e26_report" "$RESULTS_DIR/E26-jmeter.log"; then
        e26_status="FAIL"; e26_note="could not remove the previous report; not running over it"
    else
        echo "[E26] Server metrics — -g -o over the committed JTL and payload ..."
        run_jmeter -g "$TEST_PLANS/goldens/plain.jtl" -o "$E26_DIR" \
            -Jwebinsight.report.filename=E26-report.html \
            -Jwebinsight.report.annotations="$E26_INPUTS/e26-server.json" \
            -Jwebinsight.report.servermetrics="$E26_INPUTS/server-metrics.json" \
            -Jwebinsight.report.failonerror=false \
            > "$RESULTS_DIR/E26-jmeter.log" 2>&1
    fi

    if ! file_exists "$e26_report"; then
        e26_status="FAIL"; e26_note="HTML not generated"
    else
        # The payload reached the page. Without this the scenario would pass on a
        # report whose Server tab says "declared, never collected" — which is a
        # legitimate state, just not the one this fixture exists to render.
        assert_contains "$e26_report" 'serverGates' \
            || { e26_status="FAIL"; e26_note="$(append_note "$e26_note" "no serverGates in the payload — the collected file was refused")"; }
        assert_contains "$e26_report" 'id="tab-server"' \
            || { e26_status="FAIL"; e26_note="$(append_note "$e26_note" "no server tab pane in the report")"; }
    fi
    if ! file_exists "$E26_DIR/E26-report.json"; then
        e26_status="FAIL"; e26_note="$(append_note "$e26_note" "JSON not generated")"
    fi
    if log_has_stacktrace "$RESULTS_DIR/E26-jmeter.log"; then
        e26_status="FAIL"; e26_note="$(append_note "$e26_note" "Stack trace in log")"
    fi

    [[ "$e26_status" == "PASS" ]] && write_fixture_stamp "$E26_DIR"

    record E26 "Server metrics" "$e26_status" "$e26_note"
    echo "E26,Server metrics,server_metrics,local,plain.jtl,$e26_status,true,true,$e26_note,$(iso_timestamp)" >> "$CSV_REPORT"
fi

# -----------------------------------------------------------------------------
#  E27 — Server metrics: the catalogue
#
#  E26's twin for CATALOGUE components (postgres, kafka): two components, one
#  assert each, and every other catalogue metric the committed CSV carries
#  collected beside them (D1) — what the metric picker, the thresholds step and
#  Generate read. Built from committed inputs for E26's reasons, under
#  test-plans/server-catalogue/; the same rule about -Jwebinsight.report.title.
# -----------------------------------------------------------------------------
if should_run E27; then
    E27_DIR="$RESULTS_DIR/E27"
    E27_INPUTS="$TEST_PLANS/server-catalogue"
    e27_status="PASS"; e27_note=""
    e27_report="$E27_DIR/E27-report.html"

    rm -rf "$E27_DIR"
    if _report_survived_rm "$e27_report" "$RESULTS_DIR/E27-jmeter.log"; then
        e27_status="FAIL"; e27_note="could not remove the previous report; not running over it"
    else
        echo "[E27] Server metrics, catalogue — -g -o over the committed JTL and payload ..."
        run_jmeter -g "$TEST_PLANS/goldens/plain.jtl" -o "$E27_DIR" \
            -Jwebinsight.report.filename=E27-report.html \
            -Jwebinsight.report.annotations="$E27_INPUTS/e27-server.json" \
            -Jwebinsight.report.servermetrics="$E27_INPUTS/server-metrics.json" \
            -Jwebinsight.report.failonerror=false \
            > "$RESULTS_DIR/E27-jmeter.log" 2>&1
    fi

    if ! file_exists "$e27_report"; then
        e27_status="FAIL"; e27_note="HTML not generated"
    else
        assert_contains "$e27_report" 'serverCatalogue' \
            || { e27_status="FAIL"; e27_note="$(append_note "$e27_note" "no serverCatalogue in the page")"; }
        assert_contains "$e27_report" 'cache.hit.pct' \
            || { e27_status="FAIL"; e27_note="$(append_note "$e27_note" "the unasserted catalogue metrics (D1) did not reach the page")"; }
    fi
    if log_has_stacktrace "$RESULTS_DIR/E27-jmeter.log"; then
        e27_status="FAIL"; e27_note="$(append_note "$e27_note" "Stack trace in log")"
    fi

    [[ "$e27_status" == "PASS" ]] && write_fixture_stamp "$E27_DIR"

    record E27 "Server metrics, catalogue" "$e27_status" "$e27_note"
    echo "E27,Server metrics catalogue,server_metrics,local,plain.jtl,$e27_status,true,true,$e27_note,$(iso_timestamp)" >> "$CSV_REPORT"
fi

# -----------------------------------------------------------------------------
#  E28 — Server data that does not line up with the run
#
#  `jmeter -g` reading a committed CSV itself (P4), over the goldens' plain.jtl:
#  one assert per way a real export goes wrong (a 3 h shift, a near-miss name,
#  blank values, 4 s rows under sustainedFor 30s collected at an explicit 15 s
#  step - measured, this file's step would be 1 s - an
#  unquoted "90,9") beside one that lines up (test-plans/server-fixtures/,
#  gen-e28.js says which is which). Beside it, under interim/ and refused/, the
#  two pages a reader meets before the data is right: the step-1 report of the
#  same file (NOT COLLECTED, "Add the server data") and a payload of another
#  run (REFUSED, FOREIGN_RUN - the title is set, so the runId differs).
# -----------------------------------------------------------------------------
if should_run E28; then
    E28_DIR="$RESULTS_DIR/E28"
    E28_INPUTS="$TEST_PLANS/server-fixtures"
    e28_status="PASS"; e28_note=""
    e28_report="$E28_DIR/E28-report.html"

    rm -rf "$E28_DIR"
    if _report_survived_rm "$e28_report" "$RESULTS_DIR/E28-jmeter.log"; then
        e28_status="FAIL"; e28_note="could not remove the previous report; not running over it"
    else
        echo "[E28] Server data that does not line up — -g -o reading the CSV itself ..."
        run_jmeter -g "$TEST_PLANS/goldens/plain.jtl" -o "$E28_DIR" \
            -Jwebinsight.report.filename=E28-report.html \
            -Jwebinsight.report.annotations="$E28_INPUTS/e28-server.json" \
            -Jwebinsight.report.servermetrics="$E28_INPUTS/e28-metrics.csv" \
            -Jwebinsight.report.servermetrics.step=15s \
            > "$RESULTS_DIR/E28-jmeter.log" 2>&1
        run_jmeter -g "$TEST_PLANS/goldens/plain.jtl" -o "$E28_DIR/interim" \
            -Jwebinsight.report.filename=E28-interim.html \
            -Jwebinsight.report.annotations="$E28_INPUTS/e28-server.json" \
            >> "$RESULTS_DIR/E28-jmeter.log" 2>&1
        run_jmeter -g "$TEST_PLANS/goldens/plain.jtl" -o "$E28_DIR/refused" \
            -Jwebinsight.report.filename=E28-refused.html \
            -Jwebinsight.report.title=E28-another-run \
            -Jwebinsight.report.annotations="$E28_INPUTS/e28-server.json" \
            -Jwebinsight.report.servermetrics="$TEST_PLANS/server-metrics/server-metrics.json" \
            >> "$RESULTS_DIR/E28-jmeter.log" 2>&1
    fi

    for _f in "$e28_report" "$E28_DIR/interim/E28-interim.html" "$E28_DIR/refused/E28-refused.html"; do
        file_exists "$_f" || { e28_status="FAIL"; e28_note="$(append_note "$e28_note" "$(basename "$_f") not generated")"; }
    done
    if file_exists "$E28_DIR/E28-report.json"; then
        assert_contains "$E28_DIR/E28-report.json" 'measured 4s spacing' \
            || { e28_status="FAIL"; e28_note="$(append_note "$e28_note" "the spaced assert was not refused for its spacing")"; }
    else
        e28_status="FAIL"; e28_note="$(append_note "$e28_note" "JSON not generated")"
    fi
    if log_has_stacktrace "$RESULTS_DIR/E28-jmeter.log"; then
        e28_status="FAIL"; e28_note="$(append_note "$e28_note" "Stack trace in log")"
    fi

    [[ "$e28_status" == "PASS" ]] && write_fixture_stamp "$E28_DIR"

    record E28 "Server data that does not line up" "$e28_status" "$e28_note"
    echo "E28,Server data misaligned,server_metrics,local,plain.jtl,$e28_status,true,true,$e28_note,$(iso_timestamp)" >> "$CSV_REPORT"
fi

# -----------------------------------------------------------------------------
#  E29 — Server data: a four-hour soak
#
#  Generated, not committed (the CSV is ~25 MB): gen-soak.js writes a seeded
#  four-hour JTL and 24 instances of 1 s server data, so the runId and every
#  number are the same on every machine. It exercises what no committed fixture
#  can: the instance cap (20 of 24 drawn), thinning (14,401 points an instance),
#  an hours-long axis, a sidecar payload, a 3-second spike the build judged
#  that the thinned line must still reach, and a capped series with a half-hour
#  outage (judgedGap on a real page; a thinned line that breaks there only).
# -----------------------------------------------------------------------------
if should_run E29; then
    E29_DIR="$RESULTS_DIR/E29"
    E29_INPUTS="$RESULTS_DIR/E29-inputs"
    e29_status="PASS"; e29_note=""
    e29_report="$E29_DIR/E29-report.html"

    rm -rf "$E29_DIR" "$E29_INPUTS"
    if _report_survived_rm "$e29_report" "$RESULTS_DIR/E29-jmeter.log"; then
        e29_status="FAIL"; e29_note="could not remove the previous report; not running over it"
    elif ! node "$TEST_PLANS/server-fixtures/gen-soak.js" "$E29_INPUTS" > "$RESULTS_DIR/E29-jmeter.log" 2>&1; then
        e29_status="FAIL"; e29_note="gen-soak.js failed"
    else
        echo "[E29] Server data, a four-hour soak — generated, then -g -o ..."
        run_jmeter -g "$E29_INPUTS/soak.jtl" -o "$E29_DIR" \
            -Jwebinsight.report.filename=E29-report.html \
            -Jwebinsight.report.annotations="$TEST_PLANS/server-fixtures/e29-server.json" \
            -Jwebinsight.report.servermetrics="$E29_INPUTS/soak.csv" \
            -Jwebinsight.report.servermetrics.step=1s \
            -Jwebinsight.report.externaldata=true \
            >> "$RESULTS_DIR/E29-jmeter.log" 2>&1
    fi

    if ! file_exists "$e29_report"; then
        e29_status="FAIL"; e29_note="$(append_note "$e29_note" "HTML not generated")"
    else
        file_exists "$E29_DIR/E29-report-data.js" \
            || { e29_status="FAIL"; e29_note="$(append_note "$e29_note" "no sidecar payload")"; }
        assert_contains "$E29_DIR/E29-report-data.js" 'serverKpis' \
            || { e29_status="FAIL"; e29_note="$(append_note "$e29_note" "the server data did not reach the sidecar")"; }
    fi
    if log_has_stacktrace "$RESULTS_DIR/E29-jmeter.log"; then
        e29_status="FAIL"; e29_note="$(append_note "$e29_note" "Stack trace in log")"
    fi

    [[ "$e29_status" == "PASS" ]] && write_fixture_stamp "$E29_DIR"

    record E29 "Server data, a four-hour soak" "$e29_status" "$e29_note"
    echo "E29,Server data soak,server_metrics,local,soak.jtl,$e29_status,true,true,$e29_note,$(iso_timestamp)" >> "$CSV_REPORT"
fi

# -----------------------------------------------------------------------------
#  E30 — Server data, live, end to end
#
#  The operator's two commands on a live run, which no committed fixture can be: a
#  real `jmeter -n` with the listener (its interim report must say NOT COLLECTED
#  and print the NEXT line), demo server data written for that run's own window
#  by scripts/lib/example-server-metrics.js (the reportExample/ generator), and
#  `jmeter -g` reading that CSV itself. Every declared component must be judged,
#  from data whose times exist only for this run. In the smoke suite, so the
#  regression runs it even when the fixtures are current.
# -----------------------------------------------------------------------------
if should_run E30; then
    E30_DIR="$RESULTS_DIR/E30"
    e30_status="PASS"; e30_note=""
    e30_title="E30 live server data"
    rm -rf "$E30_DIR"
    if [[ -e "$E30_DIR" ]]; then
        e30_status="FAIL"; e30_note="could not remove the previous run; not running over it"
    elif ! run_test_jmeter "$E30_DIR" many-samplers-test.jmx E30-report "$e30_title" \
            -Jthreads=2 -Jduration=12 -Jrampup=1 \
            -Jwebinsight.report.annotations="$TEST_PLANS/annotations/example.json"; then
        e30_status="FAIL"; e30_note="the live run wrote no report"
    else
        grep -a -q "WEB-INSIGHT-REPORT: NEXT" "$E30_DIR/jmeter.log" \
            || e30_note="$(append_note "$e30_note" "the live run printed no NEXT line")"
        grep -a -q '"state" : "NOT_COLLECTED"' "$E30_DIR/E30-report.json" \
            || e30_note="$(append_note "$e30_note" "the interim report's serverData is not NOT_COLLECTED")"
        if ! node "$PROJECT_DIR/scripts/lib/example-server-metrics.js" \
                "$E30_DIR/results.jtl" "$E30_DIR/server.csv" 5 > "$E30_DIR/server-data.log" 2>&1; then
            e30_note="$(append_note "$e30_note" "example-server-metrics.js failed")"
        else
            WIR_RUNTIME_LOG="$E30_DIR/judged-runtime.log" \
            run_jmeter -g "$E30_DIR/results.jtl" -o "$E30_DIR/judged" \
                -Jwebinsight.report.filename=E30-report.html \
                -Jwebinsight.report.title="$e30_title" \
                -Jwebinsight.report.annotations="$TEST_PLANS/annotations/example.json" \
                -Jwebinsight.report.servermetrics="$E30_DIR/server.csv" \
                -Jwebinsight.report.servermetrics.step=5s \
                > "$E30_DIR/judged.log" 2>&1
            e30_rc=$?
            [[ $e30_rc -eq 0 ]] || e30_note="$(append_note "$e30_note" "jmeter -g exited $e30_rc")"
            e30_json="$E30_DIR/judged/E30-report.json"
            command -v cygpath >/dev/null 2>&1 && e30_json="$(cygpath -w "$e30_json")"
            e30_judged=$(node -e "
                const j = require(process.argv[1]);
                const c = j.serverComponents || [];
                const bad = c.filter(x => !['PASS', 'WARN', 'FAIL'].includes(x.status)).map(x => x.id + '=' + x.status);
                console.log(c.length && !bad.length && j.serverData && j.serverData.state === 'COLLECTED'
                    ? 'ok ' + c.map(x => x.id + '=' + x.status).join(' ') : 'no ' + bad.join(' '));" "$e30_json" 2>&1)
            echo "[E30] $e30_judged"
            [[ "$e30_judged" == ok* ]] \
                || e30_note="$(append_note "$e30_note" "not every component was judged from the live CSV: $e30_judged")"
        fi
        [[ -z "$e30_note" ]] || e30_status="FAIL"
    fi
    if log_has_stacktrace "$E30_DIR/judged.log" 2>/dev/null; then
        e30_status="FAIL"; e30_note="$(append_note "$e30_note" "Stack trace in log")"
    fi
    record E30 "Server data, live, end to end" "$e30_status" "$e30_note"
    echo "E30,Server data live,server_metrics,live,many-samplers-test.jmx,$e30_status,true,true,$e30_note,$(iso_timestamp)" >> "$CSV_REPORT"
fi

for _id in $ONLY_IDS; do
    if [[ " $ONLY_RAN " != *" $_id "* ]]; then
        echo "ERROR: --only $_id was not run: it is not a scenario of --suite $SUITE" >&2
        FAIL=$((FAIL + 1))
    fi
done

print_summary "TEST EXECUTION SUMMARY"
echo ""
echo "  Results: $CSV_REPORT"

exit $FAIL
