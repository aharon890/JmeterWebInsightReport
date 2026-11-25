#!/usr/bin/env bash
# =============================================================================
#  Web Insight Report — the release tier in two phases (about 15 minutes)
#
#  Every release runner. One after another they took about 75 minutes (2026-11-24,
#  a 24-thread PC); they wait on JMeter's timers and JVM start-ups far more than
#  they use the CPU, so phase 1 runs them in lanes side by side. The one exception
#  is the browser tests (the regression's R05): they use most of the CPU on their
#  own (83-90%, measured 2026-11-25), and beside the other runners 5 to 25 of 1222
#  timed out in three runs. So phase 2 runs them alone, once phase 1 has finished.
#
#  The JAR is built and deployed ONCE, first, and nothing rebuilds it while the
#  lanes run: every runner gets SKIP_BUILD=1.
#  The lanes are safe together because each runner writes only its own folder
#  under test-results/. The one runner that shared folders with the regression,
#  run-all-tests.sh, runs --suite release here: the scenarios the regression does
#  not produce, with its own CSV and markers.
#
#  One release run at a time: a second one, started while the first is alive,
#  refuses (exit 2), because the two would share test-results/ and spoil both.
#
#  Logs: test-results/release/<runner>.log, one per runner.
#  Exit code: the number of failed runners.
# =============================================================================
set -uo pipefail

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$SCRIPTS_DIR/../../scripts/lib/common.sh"

case "${1:-}" in
    -h|--help) sed -n '2,24p' "$0"; exit 0 ;;
    "") ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
esac

# Phase 1, one lane per line. A lane's runners, separated by "|", run one after another; the
# lanes run side by side. Balanced by each runner's time in phase 1 (2026-11-25, run 4) so that no
# lane outlasts the longest single runner, family G (about 7½ minutes); the settings tests, 13
# minutes on their own, run as two halves. Phase 1 used 54-77% of the CPU with eight lanes.
# verify_release_tier holds this list to the runners in test-plans/scripts.
LANES=(
    "run-regression.sh --no-browser-tests"
    "run-config-tests.sh --stages C01-C23"
    "run-config-tests.sh --stages C24-C44"
    "run-family-g-tests.sh"
    "run-family-ab-tests.sh"
    "run-family-e-tests.sh|run-error-tests.sh"
    "run-title-junit-tests.sh|run-family-ij-extra-tests.sh"
    "run-docker-tests.sh|run-family-fh-tests.sh"
    "run-all-tests.sh --suite release|run-family-cd-tests.sh"
)

# Phase 2: the regression's browser tests, alone, over the fixtures phase 1 left current.
BROWSER_PHASE="run-regression.sh --browser-tests-only"

LOG_DIR="$RESULTS_DIR/release"

print_banner "Release tier — Web Insight Report: ${#LANES[@]} lanes side by side, then the browser tests alone"
require_jmeter

# --- 0. One release run at a time --------------------------------------------------------
# Taken before the build, so a refused run never redeploys the JAR under the other one. A lock
# whose PID is gone (a run killed with -9, or a closed terminal) is taken over.
LOCK_DIR="$RESULTS_DIR/.release-running"
mkdir -p "$RESULTS_DIR"
if ! mkdir "$LOCK_DIR" 2>/dev/null; then
    other="$(cat "$LOCK_DIR/pid" 2>/dev/null)"
    if [[ -n "$other" ]] && kill -0 "$other" 2>/dev/null; then
        echo "[RELEASE] another release run (PID $other) is still running — stop it first:" >&2
        echo "          two runs share test-results/ and spoil each other's results." >&2
        exit 2
    fi
    echo "[RELEASE] a lock left by a run that is gone (PID ${other:-unknown}) — taking it over"
fi
echo $$ > "$LOCK_DIR/pid"
trap 'rm -rf "$LOCK_DIR"' EXIT
RELEASE_START=$(date +%s)

# --- 1. Build and deploy, once -------------------------------------------------------------
if [[ "${SKIP_BUILD:-0}" == "1" ]]; then
    echo "[BUILD] SKIP_BUILD=1 — using the already-deployed plugin ${PLUGIN_VERSION}"
    require_deployed_plugin
elif ! { build_plugin && deploy_plugin; }; then
    echo "[BUILD] FAILED — no runner started" >&2
    exit 1
fi

rm -rf "$LOG_DIR"
mkdir -p "$LOG_DIR"

minutes() { printf '%dm%02ds' $(( $1 / 60 )) $(( $1 % 60 )); }

# entry_name <entry> -- the runner and its arguments as one file name, so the two halves of a
# runner get a log each: "run-config-tests.sh --stages C01-C23" is run-config-tests_stages_C01-C23.
entry_name() {
    local script="${1%% *}"
    local name="${script%.sh}${1#"$script"}"
    name="${name// --/_}"
    printf '%s\n' "${name// /_}"
}

# run_entry <entry> -- run one runner into its own log, and leave "<exit code> <seconds>" beside
# the log for the summary.
run_entry() {
    local entry="$1" script args name start rc
    script="${entry%% *}"
    args="${entry#"$script"}"
    name="$(entry_name "$entry")"
    start=$(date +%s)
    echo "[LANE] $(date +%H:%M:%S) start  $entry"
    # $args unquoted on purpose: it is a fixed word list from LANES above.
    # shellcheck disable=SC2086
    SKIP_BUILD=1 bash "$SCRIPTS_DIR/$script" $args > "$LOG_DIR/$name.log" 2>&1
    rc=$?
    echo "$rc $(( $(date +%s) - start ))" > "$LOG_DIR/$name.result"
    echo "[LANE] $(date +%H:%M:%S) $( [[ $rc -eq 0 ]] && echo 'pass ' || echo 'FAIL ') $entry ($(minutes $(( $(date +%s) - start ))))"
}

# run_lane <lane> -- a lane's runners, one after another.
run_lane() {
    local entries entry
    IFS='|' read -r -a entries <<< "$1"
    for entry in "${entries[@]}"; do
        run_entry "$entry"
    done
}

# --- 2. Phase 1: the lanes, side by side ---------------------------------------------------
LANE_PIDS=()
PHASE2_PID=""

# own_process_group -- does this shell lead its own process group? It does when a terminal started
# it as a job. Under a non-interactive caller (CI, a script, a tool) it shares the caller's group.
own_process_group() {
    local pgid
    pgid="$(cat "/proc/$$/pgid" 2>/dev/null || ps -o pgid= -p $$ 2>/dev/null)"
    pgid="${pgid//[!0-9]/}"
    [[ -n "$pgid" && "$pgid" == "$$" ]]
}

# Ctrl-C or TERM stops the run, then this shell (the EXIT trap removes the lock). The handler ignores
# INT and TERM first: armed, it ran itself again from inside itself until bash crashed (Git Bash,
# 2026-11-25), or, where it only returned, went on to start phase 2. `kill 0` stops this run's whole
# job, runners and JMeter included, but only when that job is this run alone: in a caller's group
# (a CI step, a script) it stops the caller too, so there only the lanes go.
stop_everything() {
    trap '' INT TERM
    echo "[RELEASE] interrupted — stopping every lane" >&2
    if own_process_group; then
        kill 0 2>/dev/null
    else
        # shellcheck disable=SC2086
        kill ${LANE_PIDS[@]+"${LANE_PIDS[@]}"} $PHASE2_PID 2>/dev/null
        echo "[RELEASE] not started from a terminal, so only the lanes were stopped: a runner already" >&2
        echo "          running may go on to its end. Ctrl-C in a terminal stops everything." >&2
    fi
    exit 130
}
trap stop_everything INT TERM
PHASE1_START=$(date +%s)
for lane in "${LANES[@]}"; do
    run_lane "$lane" &
    LANE_PIDS+=($!)
done
for pid in "${LANE_PIDS[@]}"; do
    wait "$pid"
done
PHASE1_SECS=$(( $(date +%s) - PHASE1_START ))

# --- 3. Phase 2: the browser tests, alone ----------------------------------------------------
PHASE2_START=$(date +%s)
run_entry "$BROWSER_PHASE" &
PHASE2_PID=$!
wait "$PHASE2_PID"
PHASE2_SECS=$(( $(date +%s) - PHASE2_START ))
trap - INT TERM

# --- 4. One line per runner ----------------------------------------------------------------
ALL_ENTRIES=()
for lane in "${LANES[@]}"; do
    IFS='|' read -r -a entries <<< "$lane"
    ALL_ENTRIES+=("${entries[@]}")
done
ALL_ENTRIES+=("$BROWSER_PHASE")
for entry in "${ALL_ENTRIES[@]}"; do
    name="$(entry_name "$entry")"
    log="$LOG_DIR/$name.log"
    if [[ ! -f "$LOG_DIR/$name.result" ]]; then
        record "$name" "$entry" FAIL "did not finish — see $log"
        continue
    fi
    read -r rc secs < "$LOG_DIR/$name.result"
    total="$(grep -E 'TOTAL:' "$log" 2>/dev/null | tail -1 | sed 's/^ *//')"
    browser="$(grep -E '^ +[0-9]+ (passed|failed|flaky)' "$log" 2>/dev/null | sed 's/^ *//' | tr '\n' ' ')"
    note="${total:-no TOTAL line}${browser:+; Playwright $browser}; $(minutes "$secs")"
    if [[ "$rc" == "0" ]]; then
        record "$name" "$entry" PASS "$note"
    else
        record "$name" "$entry" FAIL "$note — see $log"
    fi
done

print_summary "RELEASE TIER SUMMARY"
echo "  Phase 1 (lanes): $(minutes "$PHASE1_SECS")   Phase 2 (browser tests alone): $(minutes "$PHASE2_SECS")"
echo "  Wall time: $(minutes $(( $(date +%s) - RELEASE_START ))) (build included)"
echo "  Logs: $LOG_DIR"

exit $FAIL
