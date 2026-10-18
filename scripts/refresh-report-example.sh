#!/usr/bin/env bash
# =============================================================================
#  Regenerate reportExample/ from the current build.
#
#  reportExample/ is the report people look at before they install anything. It
#  was checked in once, by hand, and then went stale: by the time it was deleted
#  it carried CSS classes nothing rendered any more and a config block the
#  product no longer understood. An example that lies about the product is worse
#  than no example, and one that can only be refreshed by hand will go stale
#  again — so this is the recipe.
#
#  It is the documented three steps, end to end, so the example's Server tab is
#  populated rather than empty:
#
#    1. the run, with the listener — its report is the interim one (client
#       rules only, server asserts NOT COLLECTED); it is not what gets published
#    2. server data for the run's window, then `wir-collect --csv` over it. The
#       data is DEMO data — scripts/lib/example-server-metrics.js writes it from
#       the run's JTL, shaped by the run's own load — and the components in
#       test-plans/annotations/example.json say so in their descriptions
#    3. `jmeter -g results.jtl -o <fresh dir>` with the annotations and the
#       payload: the report that judges both sides, and the one copied into
#       reportExample/
#
#  Run it AFTER a green regression, never before. It bakes in whatever the
#  current build does; if the build is wrong, the example is confidently wrong.
#
#  Usage:
#      ./scripts/refresh-report-example.sh
#      EXAMPLE_JMX=many-samplers-test.jmx ./scripts/refresh-report-example.sh
#      SKIP_BUILD=1 ./scripts/refresh-report-example.sh    # orchestrator already built
# =============================================================================
set -uo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/common.sh"

# Many samplers, so the tables and chart legends show their real behaviour under
# the width pressure a one-sampler plan never applies — which is what the old
# hand-built example was demonstrating and the reason to keep demonstrating it.
JMX="${EXAMPLE_JMX:-many-samplers-test.jmx}"
ANNOTATIONS="${EXAMPLE_ANNOTATIONS:-example.json}"
LOAD_ARGS=(-Jthreads=5 -Jduration=30 -Jrampup=5)

# One title for steps 1-3: it is the run's test name on the -g path, and RunIdentity hashes it,
# so wir-collect's --test-name must be the same text or step 3 refuses the payload as another
# run's (FOREIGN_RUN).
TITLE="JMeter Web Insight Report — Example"
# The demo data's interval and the collector's --step: the same number, or wir-collect warns
# that the rows are not at the step it counts coverage in.
STEP_SECONDS=5

EXAMPLE_DIR="$PROJECT_DIR/reportExample"
WORK_DIR="$RESULTS_DIR/EXAMPLE"
JUDGED_DIR="$WORK_DIR/judged"
SERVER_CSV="$WORK_DIR/server-metrics.csv"
PAYLOAD="$WORK_DIR/server-metrics.json"
BASE="web-insight-report"

# unchanged <message...> — the refusal every failed step ends in, naming the log to read.
unchanged() {
    echo "" >&2
    local line
    for line in "$@"; do
        echo "[EXAMPLE] $line" >&2
    done
    echo "[EXAMPLE] reportExample/ is UNCHANGED." >&2
    exit 1
}

print_banner "Report Example — regenerate from the current build"

require_jmeter
# SKIP_BUILD=1 when an orchestrator has already built and deployed this same JAR.
if [[ "${SKIP_BUILD:-0}" == "1" ]]; then
    echo "[EXAMPLE] SKIP_BUILD=1 — using the already-deployed plugin ${PLUGIN_VERSION}"
    require_deployed_plugin || exit 1
else
    build_plugin  || { echo "[EXAMPLE] build failed — reportExample/ is UNCHANGED." >&2; exit 1; }
    deploy_plugin || { echo "[EXAMPLE] deploy failed — reportExample/ is UNCHANGED." >&2; exit 1; }
fi
command -v node >/dev/null 2>&1 \
    || unchanged "ERROR: node is not on PATH — step 2 writes the demo server data with it."

# rm -rf the directory, not its contents: `jmeter -g -o` refuses a non-empty
# directory, and `rm -rf dir/*` does not match the dotfiles the publish lock
# leaves behind. This is the same trap documented in CLAUDE.md.
rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR"

ANNOTATIONS_SRC="$TEST_PLANS/annotations/$ANNOTATIONS"
if [[ ! -f "$ANNOTATIONS_SRC" ]]; then
    echo "[EXAMPLE] ERROR: no annotations file at $ANNOTATIONS_SRC" >&2
    exit 1
fi

echo "[EXAMPLE] plan:        $JMX"
echo "[EXAMPLE] annotations: $ANNOTATIONS"

# ---- 1. the run --------------------------------------------------------------------------------
echo "[EXAMPLE] 1/3 running the plan..."
# Its status, not a look at the directory afterwards: run_test_jmeter returns 1 when this run wrote
# no report, including when it refused to run over a previous one it could not remove, and a look
# at the directory would then find that previous run's report and JTL and carry on with them.
run_test_jmeter "$WORK_DIR" "$JMX" "$BASE" "$TITLE" \
    "${LOAD_ARGS[@]}" \
    -Jwebinsight.report.annotations="$ANNOTATIONS_SRC" \
    || unchanged "ERROR: the run wrote no report." "See $WORK_DIR/jmeter.log"

if [[ ! -s "$WORK_DIR/results.jtl" ]]; then
    unchanged "ERROR: the run wrote no results.jtl." "See $WORK_DIR/jmeter.log"
fi

# ---- 2. demo server data, collected ------------------------------------------------------------
echo "[EXAMPLE] 2/3 writing demo server data and collecting it..."
node "$PROJECT_DIR/scripts/lib/example-server-metrics.js" \
        "$WORK_DIR/results.jtl" "$SERVER_CSV" "$STEP_SECONDS" \
    || unchanged "ERROR: the demo server data could not be written (above)."

# wir-collect.sh finds JMeter from JMETER_HOME, which require_jmeter exported: the same install,
# and so the same plugin JAR, as steps 1 and 3. It exits 0 whenever it wrote the payload, so a
# series it could not judge is caught by step 3's verdict check below, not here.
#
# From the work directory, with the CSV by its bare name: wir-collect records --csv as written as
# the source's resolvedUrl, and the published Server tab shows it. So the page reads
# "csv · server-metrics.csv", as the committed E26 and golden payloads carry a relative path, and
# never this machine's directory (report_local_path below refuses that).
if ! ( cd "$WORK_DIR" && "$PROJECT_DIR/scripts/wir-collect.sh" \
        --jtl "$WORK_DIR/results.jtl" \
        --annotations "$ANNOTATIONS_SRC" \
        --csv "$(basename "$SERVER_CSV")" \
        --step "${STEP_SECONDS}s" \
        --test-name "$TITLE" \
        --out "$PAYLOAD" \
        --no-wait ) > "$WORK_DIR/wir-collect.log" 2>&1 \
   || [[ ! -s "$PAYLOAD" ]]; then
    cat "$WORK_DIR/wir-collect.log" >&2
    unchanged "ERROR: wir-collect wrote no payload (its output is above)."
fi

# ---- 3. the report that judges both sides --------------------------------------------------------
echo "[EXAMPLE] 3/3 judging the run with jmeter -g -o..."
WIR_RUNTIME_LOG="$WORK_DIR/jmeter-g-runtime.log" \
run_jmeter -g "$WORK_DIR/results.jtl" -o "$JUDGED_DIR" \
    -Jwebinsight.report.filename="$BASE.html" \
    -Jwebinsight.report.title="$TITLE" \
    -Jwebinsight.report.annotations="$ANNOTATIONS_SRC" \
    -Jwebinsight.report.servermetrics="$PAYLOAD" \
    > "$WORK_DIR/jmeter-g.log" 2>&1
g_rc=$?

HTML="$JUDGED_DIR/$BASE.html"
if [[ ! -f "$HTML" ]]; then
    unchanged "ERROR: jmeter -g -o produced no report." \
              "See $WORK_DIR/jmeter-g.log"
fi
# failonerror defaults to true, and a FAIL verdict exits 0 (failonassert is off), so a non-zero
# exit here is a CONFIG ERROR: a refused payload, a series that could not be judged, a rule the
# file made unrunnable. Never something to publish as the example.
if (( g_rc != 0 )); then
    unchanged "ERROR: jmeter -g -o exited $g_rc — a configuration error, not a verdict." \
              "See $WORK_DIR/jmeter-g.log"
fi

# The gate must have actually run. An example whose Asserts tab says NOT EVALUATED
# is exactly the wrong thing to show a first-time reader, and it is the failure mode
# a mistyped annotations path produces silently.
#
# The verdict is the run's CI/CD JSON's (report_gate_verdict), never a grep of the page:
# every report inlines report.css, and since 5874e10 a comment in it reads "ASSERTS NOT
# EVALUATED", so a grep for ASSERTS matched every report, gate or none, and the verdict
# line below printed "ASSERTS NOT" whatever the gate said (S5).
if ! verdict="$(report_gate_verdict "$HTML")"; then
    unchanged "ERROR: the run evaluated no assert gate (no PASS, WARN or FAIL in" \
              "       $BASE.json) — the annotations file did not reach the run, or" \
              "       every rule in it was refused."
fi

# The server side, from the same JSON (report_server_verdicts, selftested beside
# report_gate_verdict): a verdict for serverStatus, for every component the file declares and for
# every server assert. Anything else — the payload refused, a component that judged nothing, an
# assert NOT_JUDGED — is a Server tab that is not showing the feature.
if ! server="$(report_server_verdicts "$HTML" "$ANNOTATIONS_SRC")"; then
    unchanged "ERROR: the Server tab would not show a verdict for every component and assert" \
              "       (above). See $WORK_DIR/wir-collect.log and $WORK_DIR/jmeter-g.log"
fi

# The published page names no directory of this machine. wir-collect records its --csv argument as
# the source's resolvedUrl and the Server tab's Collection panel shows it, which is why step 2 runs
# from the work directory with a bare file name; an absolute path put this checkout's D:/... into
# reportExample/, and made the HTML differ on every machine that regenerated it.
if leaked="$(report_local_path "$HTML" "$PROJECT_DIR" "$RESULTS_DIR" "${JMETER_HOME:-}" "${HOME:-}")"; then
    unchanged "ERROR: the report names a local directory ('$leaked'), and would publish it."
fi

mkdir -p "$EXAMPLE_DIR"
cp "$HTML" "$EXAMPLE_DIR/$BASE.html"
cp "$ANNOTATIONS_SRC" "$EXAMPLE_DIR/report-annotations.json"

size_kb=$(( $(wc -c < "$EXAMPLE_DIR/$BASE.html") / 1024 ))

echo ""
echo "[EXAMPLE] reportExample/$BASE.html          ${size_kb} KB   asserts $verdict"
echo "[EXAMPLE] reportExample/report-annotations.json"
printf '%s\n' "$server" | sed 's/^/[EXAMPLE]   /'
echo ""
echo "  Regenerated from plugin ${PLUGIN_VERSION}. Commit both files together —"
echo "  the annotations file is what produced the report beside it. The server"
echo "  data behind its Server tab is demo data, generated by this script."
echo ""
