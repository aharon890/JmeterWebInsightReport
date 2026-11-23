#!/usr/bin/env bash
# =============================================================================
#  The build-time, memory and draw-time figures of spec 001 (task T067; plan "Performance Goals", SC-006).
#
#  K2 (petstore-checkout SMOKE, 57 requests) repeated to 134,400 requests, 100 times perf hub's run #52, over about
#  an hour, then:
#    1. build-report --from k6 over it, under -Xmx512m (the plan's memory ceiling): its time, and whether it fit;
#    2. jmeter -g over its JMeter-format copy (the research's converter), the same samples as a JTL: the time the
#       build is held to (within it plus 20%);
#    3. with --baseline-jar FILE (a JAR from before spec 001, such as 4.4.1's), jmeter -g over the same copy with
#       that JAR, in a copy of JMeter made for it: today's figure;
#    4. the Summary tab's draw time over file://, in Playwright's Chromium, for each report (SC-006: within 5 s, and no
#       slower than the baseline's).
#
#  A measurement, not a gate: it prints the figures and writes them to test-results/measure-run-data/figures.json.
#  No test bed and no load tool is involved; it is local file processing only. Run it on a quiet machine.
#
#  ./test-plans/scripts/measure-run-data.sh [--copies N] [--baseline-jar FILE]
# =============================================================================
set -uo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../../scripts/lib/common.sh"

COPIES=2358
BASELINE_JAR=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --copies) COPIES="$2"; shift 2 ;;
        --baseline-jar) BASELINE_JAR="$2"; shift 2 ;;
        *) echo "usage: $0 [--copies N] [--baseline-jar FILE]" >&2; exit 2 ;;
    esac
done

require_jmeter
require_python || { echo "[MEASURE] this needs Python 3" >&2; exit 1; }
JAR="$(ls "$JMETER_EXT"/jmeter-web-insight-report-*.jar 2>/dev/null | head -1)"
[[ -n "$JAR" ]] || { echo "[MEASURE] no plugin JAR in $JMETER_EXT - run ./scripts/build-and-deploy.sh" >&2; exit 1; }
OUT="$RESULTS_DIR/measure-run-data"
FIG="$OUT/figures.json"
HELP="$TEST_PLANS/scripts/measure-run-data.py"
rm -rf "${OUT:?}"
mkdir -p "$OUT"

echo "[MEASURE] K2 repeated $COPIES times"
"$PYTHON" "$HELP" repeat "$TEST_PLANS/run-data/K2/results.csv.gz" "$COPIES" "$OUT/results.csv.gz" || exit 1
"$PYTHON" -I "$TEST_PLANS/run-data/tools/k6_to_jtl.py" "$OUT/results.csv.gz" "$OUT/results.jtl" "$OUT/in-test-plan.jtl" \
    > "$OUT/convert.log" 2>&1 || { echo "[MEASURE] the converter failed - see $OUT/convert.log" >&2; exit 1; }

echo "[MEASURE] 1. build-report --from k6, under -Xmx512m"
"$PYTHON" "$HELP" time "build-report --from k6 (-Xmx512m)" "$FIG" -- \
    java -Xmx512m -jar "$JAR" build-report --from k6 --k6-results "$OUT/results.csv.gz" \
    --k6-summary "$TEST_PLANS/run-data/K2/summary.json" --out "$OUT/k6" \
    --property webinsight.report.title=K2x --property webinsight.report.filename=k6.html

echo "[MEASURE] 2. jmeter -g over the same samples as a JTL"
"$PYTHON" "$HELP" time "jmeter -g, this build" "$FIG" -- \
    "$JMETER" -g "$OUT/results.jtl" -o "$OUT/g" -Jwebinsight.report.title=K2x \
    -Jwebinsight.report.filename=g.html -Jwebinsight.report.failonerror=false

if [[ -n "$BASELINE_JAR" ]]; then
    echo "[MEASURE] 3. jmeter -g with $(basename "$BASELINE_JAR"), in a copy of JMeter"
    BASE_HOME="$OUT/jmeter-baseline"
    cp -R "$JMETER_HOME" "$BASE_HOME"
    rm -f "$BASE_HOME"/lib/ext/jmeter-web-insight-report-*.jar
    cp "$BASELINE_JAR" "$BASE_HOME/lib/ext/"
    "$PYTHON" "$HELP" time "jmeter -g, $(basename "$BASELINE_JAR")" "$FIG" -- \
        "$BASE_HOME/bin/jmeter" -g "$OUT/results.jtl" -o "$OUT/baseline" \
        -Jwebinsight.report.title=K2x -Jwebinsight.report.filename=baseline.html \
        -Jwebinsight.report.failonerror=false
fi

echo "[MEASURE] 4. the Summary tab's draw time over file:// (SC-006)"
for page in "$OUT/k6/k6.html" "$OUT/g/g.html" "$OUT/baseline/baseline.html"; do
    [[ -f "$page" ]] || continue
    node "$TEST_PLANS/scripts/measure-summary-draw.js" "$TEST_PLANS/playwright" "$page" 3 | tee -a "$OUT/draw.jsonl"
done

echo "[MEASURE] figures: $FIG and $OUT/draw.jsonl"
cat "$FIG"
