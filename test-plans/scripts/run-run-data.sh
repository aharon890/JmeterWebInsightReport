#!/usr/bin/env bash
# =============================================================================
#  R08: run data conformance (spec 001). Every producer of run data is held to JMeter's Aggregate Report.
#
#  - The k6 fixtures (test-plans/run-data/K*) through build-report --from k6, with no jmeter -g: R07's referee
#    over each fixture's JMeter-format copy (results.jtl for every row, in-test-plan.jtl for the whole run), the
#    page's Samplers rows against the Aggregate Report table's text, and the CI/CD JSON against expected/.
#  - Another tool's run data, written by hand from the docs alone (test-plans/run-data/D*, US4): build-report
#    --run-data, held the same way through its own JMeter-format copy.
#  - The JMeter plugin as a producer (finding C1): E02 and E31 through jmeter -g with webinsight.report.rundata,
#    then build-report over the run data it wrote. The rebuilt report is held to the referee too, and its CI/CD
#    JSON must equal the -g report's.
#
#  Needs the plugin deployed, Python 3 and a JDK, and the E02/E31 fixtures the regression's R04 regenerates.
#  --accept rewrites test-plans/run-data/<id>/expected/; commit them with the change that moved them.
# =============================================================================
set -uo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../../scripts/lib/common.sh"

ACCEPT=0
[[ "${1:-}" == "--accept" ]] && ACCEPT=1

require_jmeter
require_python || { echo "[RUN-DATA] FAIL: this check needs Python 3" >&2; exit 1; }
OUT="$RESULTS_DIR/R08"
RD="$TEST_PLANS/run-data"
CANON="$TEST_PLANS/scripts/canonicalise-artifact.py"
rm -rf "${OUT:?}"
mkdir -p "$OUT"
referee_compile "$OUT/referee" || exit 1
JAR="$(ls "$JMETER_EXT"/jmeter-web-insight-report-*.jar 2>/dev/null | head -1)"
if [[ -z "$JAR" ]]; then
    echo "[RUN-DATA] FAIL: no plugin JAR in $JMETER_EXT - run ./scripts/build-and-deploy.sh" >&2
    exit 1
fi
fail=0

echo "[RUN-DATA] k6 runs, through the report's k6 adapter (no jmeter -g)"
for dir in "$RD"/K*/; do
    id="$(basename "$dir")"
    if [[ ! -f "$dir/results.csv.gz" ]]; then
        echo "  [$id] not recorded yet"
        continue
    fi
    out="$OUT/$id"
    if [[ -f "$dir/summary.json" ]]; then
        java -jar "$JAR" build-report --from k6 --k6-results "$dir/results.csv.gz" --k6-summary "$dir/summary.json" \
            --out "$out" --write-run-data --property webinsight.report.title="$id" \
            --property webinsight.report.filename="$id-report.html" > "$out.log" 2>&1
    else
        java -jar "$JAR" build-report --from k6 --k6-results "$dir/results.csv.gz" \
            --out "$out" --write-run-data --property webinsight.report.title="$id" \
            --property webinsight.report.filename="$id-report.html" > "$out.log" 2>&1
    fi
    if [[ ! -f "$out/$id-report.json" ]]; then
        echo "  [$id] FAIL: build-report wrote no report - see $out.log"
        fail=1
        continue
    fi
    referee_check "$id" "$out/$id-report.json" "$dir/results.jtl" "$dir/in-test-plan.jtl" "" \
        "$out/$id-report-run-data.jsonl.gz" || fail=1

    # The CI/CD JSON against the fixture's expected one: counts, verdicts, the tool (SC-005)
    "$PYTHON" "$CANON" "$out/$id-report.json" "$out/$id.canonical.json" || { fail=1; continue; }
    expected="$dir/expected/$id.json"
    if [[ $ACCEPT -eq 1 ]]; then
        mkdir -p "$dir/expected"
        cp "$out/$id.canonical.json" "$expected"
        echo "  [$id] accepted $expected"
    elif [[ ! -f "$expected" ]]; then
        echo "  [$id] FAIL: no expected/$id.json - run $0 --accept, read it, commit it"
        fail=1
    elif ! diff -u "$expected" "$out/$id.canonical.json" > "$out/$id.json.diff"; then
        echo "  [$id] FAIL: the CI/CD JSON changed:"
        head -30 "$out/$id.json.diff"
        fail=1
    fi
done

echo "[RUN-DATA] Another tool's run data, written by hand from the docs (US4)"
for dir in "$RD"/D*/; do
    id="$(basename "$dir")"
    out="$OUT/$id"
    java -jar "$JAR" build-report --run-data "$dir/run-data.jsonl" --out "$out" \
        --property webinsight.report.filename="$id-report.html" > "$out.log" 2>&1
    if [[ ! -f "$out/$id-report.json" ]]; then
        echo "  [$id] FAIL: build-report wrote no report - see $out.log"
        fail=1
        continue
    fi
    referee_check "$id" "$out/$id-report.json" "$dir/results.jtl" "$dir/in-test-plan.jtl" "" "$dir/run-data.jsonl" \
        || fail=1
    "$PYTHON" "$CANON" "$out/$id-report.json" "$out/$id.canonical.json" || { fail=1; continue; }
    expected="$dir/expected/$id.json"
    if [[ $ACCEPT -eq 1 ]]; then
        mkdir -p "$dir/expected"
        cp "$out/$id.canonical.json" "$expected"
        echo "  [$id] accepted $expected"
    elif [[ ! -f "$expected" ]]; then
        echo "  [$id] FAIL: no expected/$id.json - run $0 --accept, read it, commit it"
        fail=1
    elif ! diff -u "$expected" "$out/$id.canonical.json" > "$out/$id.json.diff"; then
        echo "  [$id] FAIL: the CI/CD JSON changed:"
        head -30 "$out/$id.json.diff"
        fail=1
    fi
done

echo "[RUN-DATA] JMeter runs written as run data and built again (C1)"
for id in E02 E31; do
    jtl="$RESULTS_DIR/$id/results.jtl"
    if [[ ! -f "$jtl" || ! -f "$RESULTS_DIR/$id/$id-report.json" ]]; then
        echo "  [$id] FAIL: the $id fixture is missing - the regression's R04 generates it"
        fail=1
        continue
    fi
    # The run report's title, which runId hashes: without it -g names the run otherwise
    title="$("$PYTHON" -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["testName"])' \
        "$RESULTS_DIR/$id/$id-report.json")"
    run_jmeter -g "$jtl" -o "$OUT/$id-g" -Jwebinsight.report.rundata=true -Jwebinsight.report.title="$title" \
        -Jwebinsight.report.filename="$id-g.html" -Jwebinsight.report.failonerror=false > "$OUT/$id-g.log" 2>&1
    run_data="$OUT/$id-g/$id-g-run-data.jsonl.gz"
    if [[ ! -f "$run_data" ]]; then
        echo "  [$id] FAIL: jmeter -g wrote no run data - see $OUT/$id-g.log"
        fail=1
        continue
    fi
    java -jar "$JAR" build-report --run-data "$run_data" --out "$OUT/$id-rd" \
        --property webinsight.report.filename="$id-rd.html" > "$OUT/$id-rd.log" 2>&1
    if [[ ! -f "$OUT/$id-rd/$id-rd.json" ]]; then
        echo "  [$id] FAIL: build-report wrote no report from the run data - see $OUT/$id-rd.log"
        fail=1
        continue
    fi
    referee_check "$id-run-data" "$OUT/$id-rd/$id-rd.json" "$jtl" "$RESULTS_DIR/$id/in-test-plan.jtl" || fail=1
    "$PYTHON" "$CANON" "$OUT/$id-g/$id-g.json" "$OUT/$id-g.canonical.json" || fail=1
    "$PYTHON" "$CANON" "$OUT/$id-rd/$id-rd.json" "$OUT/$id-rd.canonical.json" || fail=1
    sed "s/$id-g/ID/g" "$OUT/$id-g.canonical.json" > "$OUT/$id-g.named.json"
    sed "s/$id-rd/ID/g" "$OUT/$id-rd.canonical.json" > "$OUT/$id-rd.named.json"
    if ! diff -u "$OUT/$id-g.named.json" "$OUT/$id-rd.named.json" > "$OUT/$id.json.diff"; then
        echo "  [$id] FAIL: the report built from its run data is not the -g report:"
        head -30 "$OUT/$id.json.diff"
        fail=1
    else
        echo "  [$id] the run data rebuilds the -g report's CI/CD JSON exactly"
    fi
done

if [[ $ACCEPT -eq 0 && -n "$(git -C "$PROJECT_DIR" status --porcelain -- test-plans/run-data 2>/dev/null | grep expected/)" ]]; then
    echo "[RUN-DATA] FAIL: expected/ is dirty in the working tree: accepted and not committed" >&2
    fail=1
fi
if [[ $fail -eq 0 ]]; then
    echo "[RUN-DATA] PASS"
else
    echo "[RUN-DATA] FAIL" >&2
fi
exit $fail
