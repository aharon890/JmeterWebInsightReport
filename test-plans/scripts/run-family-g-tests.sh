#!/usr/bin/env bash
# =============================================================================
#  Family G — Docker, shared host mounts (G01-G05)
#
#  Every other suite runs JMeter as a HOST process. This one runs it inside a
#  container writing into a bind-mounted host directory, which changes two things
#  the rest of the matrix cannot reach:
#
#    * the duplicate-write guard is per-JVM (F6, WRITTEN_THIS_JVM is static), so
#      two CONTAINERS sharing one mount are invisible to each other by construction
#      — there is no process, let alone a JVM, in common;
#    * AtomicFileWriter stages at a DETERMINISTIC sibling ".<name>.tmp" (F10), so
#      two writers targeting the same report name write the SAME staging file, on a
#      filesystem neither of them owns. Whether the final move is even atomic is a
#      property of the bind-mount driver, not of the code — probed in G02.
#
#  Preconditions are part of each row, not hygiene (see SCENARIO-MATRIX.md):
#    G01  carry               — run 2 deliberately inherits run 1's directory
#    G02  clean per iteration — a fresh mount for EVERY repetition, or iteration N
#                               asserts against iteration N-1's artifacts
#    G03  clean per iteration
#    G04  clean
#    G05  seed: HOST-written report titled HOST-SEED
#
#  Provenance is the report TITLE, never an mtime: it reaches the HTML <title>, the
#  JSON "testName" and the JUnit <testsuites name> (F16), so "who won" is one string
#  comparison. Two runs a second apart are indistinguishable by timestamp, and an
#  overwrite by an identical report is indistinguishable from a skip.
#
#  G02 is looped because a single clean run proves nothing about a race. The report
#  write here is ~440ms wide (1.5 MB HTML), so the containers are released from a
#  shared wall-clock barrier rather than merely started back to back — otherwise the
#  measurement is dominated by docker/JVM startup jitter and the window is missed.
#
#  Requires: Docker, the built plugin JAR, and (for G05's host seed) a local JMeter
#  with the plugin deployed.
# =============================================================================
set -uo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../../scripts/lib/common.sh"

# The helpers below print artifact titles back to the shell. On Windows Python's stdout
# defaults to the console codepage, so a non-ASCII title raises UnicodeEncodeError, the
# helper dies, and the caller silently compares against an empty string.
export PYTHONIOENCODING=utf-8

require_python || exit 1

JMETER_VERSION="${JMETER_VERSION:-5.6.3}"
IMAGE_NAME="${IMAGE_NAME:-jmeter-webinsight-test:$JMETER_VERSION}"
CONTAINER_EXT="/opt/apache-jmeter-$JMETER_VERSION/lib/ext"
G_RESULTS="$RESULTS_DIR/family-g"

JMX_NAME="docker-test.jmx"
JMX_HOST="$TEST_PLANS/$JMX_NAME"

# Short runs — long enough to build a report big enough (~1.5-3 MB) that generating it
# takes ~500ms and two containers are plausibly inside the generator together, short
# enough that 25+ container starts stay inside a few minutes.
JM_LOAD=(-Jthreads=10 -Jduration=6 -Jrampup=1)

# Seconds between launching a pair of containers and releasing them. Must cover
# `docker run` startup on the slowest of the two, or the barrier has already expired
# for one of them and the synchronisation it exists for never happens.
BARRIER_LEAD="${BARRIER_LEAD:-8}"

G02_ITERATIONS="${G02_ITERATIONS:-12}"
G03_ITERATIONS="${G03_ITERATIONS:-3}"

DEFAULT_HTML="web-insight-report.html"
DEFAULT_JSON="web-insight-report.json"

# Docker wants HOST paths in native form and CONTAINER paths in POSIX form. On Git Bash
# (MSYS) any argument that looks like a POSIX path is rewritten before it reaches docker,
# so "-v /d/x:/opt/y" arrives as "-v D:\x;C:\Program Files\Git\opt\y" and the mount is
# silently wrong. Disable that rewriting and convert the host side ourselves. Scoped per
# docker invocation rather than exported, because MSYS2_ARG_CONV_EXCL='*' breaks Maven's
# Git Bash wrapper — identical reasoning to run-docker-tests.sh, which is where this
# convention comes from and where it must stay in step.
DOCKER_USER=()
DOCKER_ENV=()
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*)
        DOCKER_ENV=(MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*')
        host_path() { cygpath -m "$1"; }
        ;;
    Darwin*)
        host_path() { printf '%s\n' "$1"; }
        ;;
    *)
        host_path() { printf '%s\n' "$1"; }
        if command -v id >/dev/null 2>&1; then
            DOCKER_USER=(--user "$(id -u):$(id -g)")
        fi
        ;;
esac

docker_cmd() { env ${DOCKER_ENV[@]+"${DOCKER_ENV[@]}"} docker "$@"; }

print_banner "Family G — Docker / shared host mount (G01-G05)"

if ! docker_cmd info >/dev/null 2>&1; then
    echo "ERROR: Docker daemon is not running. Start Docker Desktop and retry." >&2
    exit 1
fi

if [[ -z "${SKIP_BUILD:-}" ]]; then
    build_plugin || exit 1
fi
require_plugin_jar

echo "[DOCKER] Building JMeter image $IMAGE_NAME ..."
if ! docker_cmd build -q -t "$IMAGE_NAME" --build-arg "JMETER_VERSION=$JMETER_VERSION" \
        -f "$(host_path "$PROJECT_DIR/docker/Dockerfile.jmeter")" \
        "$(host_path "$PROJECT_DIR/docker")" >/dev/null; then
    if docker_cmd image inspect "$IMAGE_NAME" >/dev/null 2>&1; then
        echo "[DOCKER] Image build failed but $IMAGE_NAME exists — continuing with it." >&2
    else
        echo "[DOCKER] Image build FAILED and no usable image exists" >&2
        exit 1
    fi
fi

rm -rf "$G_RESULTS"
mkdir -p "$G_RESULTS"

# =============================================================================
#  Helpers
# =============================================================================

# The in-container wrapper. Three jobs the bare `jmeter` ENTRYPOINT cannot do:
#   1. hold at a shared wall-clock epoch so a PAIR of containers starts JMeter together
#      rather than however far apart docker happened to launch them;
#   2. capture the console to the mounted log dir — `docker run --rm` discards it, and
#      JMeter's own log4j2 has no console appender, so the two halves of the output live
#      in different files and both are needed;
#   3. stamp completion, so the actual skew between two runs is measured rather than
#      assumed. "No collision observed" means nothing without knowing how far apart the
#      two writes landed.
BARRIER_SH='T="$1"; P="$2"; shift 2;
if [ "$T" != "-" ]; then while [ "$(date +%s)" -lt "$T" ]; do sleep 0.05; done; fi;
date +%s.%N > "/logs/$P-start.stamp";
jmeter "$@" > "/logs/$P-console.log" 2>&1;
rc=$?;
date +%s.%N > "/logs/$P-done.stamp";
exit $rc'

# gc_clean_dir <dir> — remove, recreate, and PROVE it is empty.
#
# Asserted rather than assumed: an aborted iteration leaves debris exactly when it is
# least expected, and for the `clean per iteration` rows a leftover report from the
# previous iteration satisfies assertions this iteration never earned.
gc_clean_dir() {
    local d="$1"
    rm -rf "$d" 2>/dev/null
    mkdir -p "$d"
    if [[ -n "$(ls -A "$d" 2>/dev/null)" ]]; then
        echo "  [PRECONDITION] $d is not empty at start — results would be unsound" >&2
        return 1
    fi
}

# gc_run <mount> <logs> <prefix> <barrier|-> <title> [extra jmeter args...]
#
# One containerised JMeter run. The JTL and both logs go to a SEPARATE mount (/logs) so
# the shared /results directory holds nothing but what the report generator put there —
# otherwise a stranded ".<name>.tmp" has to be picked out of a directory that also
# contains two JTLs, and "the mount is clean" stops being checkable at a glance.
#
# GC_USER overrides the container uid for the row that needs to write as root.
gc_run() {
    local mount="$1" logs="$2" prefix="$3" barrier="$4" title="$5"
    shift 5
    mkdir -p "$mount" "$logs"

    local user_args=()
    if [[ -n "${GC_USER:-}" ]]; then
        user_args=(--user "$GC_USER")
    else
        user_args=(${DOCKER_USER[@]+"${DOCKER_USER[@]}"})
    fi

    docker_cmd run --rm ${user_args[@]+"${user_args[@]}"} --entrypoint /bin/sh \
        -v "$(host_path "$PLUGIN_JAR"):$CONTAINER_EXT/$(basename "$PLUGIN_JAR")" \
        -v "$(host_path "$JMX_HOST"):/test/$JMX_NAME" \
        -v "$(host_path "$mount"):/results" \
        -v "$(host_path "$logs"):/logs" \
        "$IMAGE_NAME" -c "$BARRIER_SH" sh "$barrier" "$prefix" \
        -n -t "/test/$JMX_NAME" \
        -l "/logs/$prefix-results.jtl" \
        -j "/logs/$prefix-runtime.log" \
        "${JM_LOAD[@]}" \
        -Jwebinsight.report.output=/results \
        -Jwebinsight.report.title="$title" \
        "$@"
}

json_title() { "$PYTHON" -c "import json,sys;print(json.load(open(sys.argv[1],encoding='utf-8'))['testName'])" "$1" 2>/dev/null; }
json_valid() { "$PYTHON" -c "import json,sys;json.load(open(sys.argv[1],encoding='utf-8'))" "$1" >/dev/null 2>&1; }

html_title() {
    "$PYTHON" - "$1" <<'PY' 2>/dev/null
import re, sys, html
src = open(sys.argv[1], encoding='utf-8', errors='replace').read()
m = re.search(r'<title>(.*?)</title>', src, re.S)
print(html.unescape(m.group(1)).strip() if m else '')
PY
}

# Staging debris left in the mount. Direct evidence for F10: the temp sibling is
# ".<targetname>.tmp" for every writer, so two of them stage into one file and a
# survivor means one writer's finally-block never ran or lost the race to delete.
gc_tmp_files() { ( cd "$1" 2>/dev/null && ls -A 2>/dev/null | grep -E '\.tmp$' | tr '\n' ' ' ); }

gc_count_glob() { count_glob "$@"; }

# A report torn by interleaved staging writes loses its tail. Cheap, decisive.
gc_html_complete() { [[ -f "$1" ]] && tail -c 400 "$1" 2>/dev/null | grep -qF '</html>'; }

# Both provenance markers inside ONE artifact is proof of interleaving rather than of
# ordinary last-writer-wins — the strongest signal this suite can produce.
gc_contains() { grep -qF -- "$2" "$1" 2>/dev/null; }

gc_stamp() { [[ -f "$1" ]] && tr -d '\r\n' < "$1"; }

# Absolute difference of two epoch stamps, in milliseconds.
gc_delta_ms() {
    local a="$1" b="$2"
    [[ -n "$a" && -n "$b" ]] || { echo ""; return 0; }
    awk -v a="$a" -v b="$b" 'BEGIN { d = (a > b) ? a - b : b - a; printf "%d", d * 1000 }'
}

# Milliseconds-into-the-day of the first log line containing <marker>. log4j2's default
# layout here is "yyyy-MM-dd HH:mm:ss,SSS", so field 2 carries the whole clock.
#
# Container exit stamps only bound the race from outside; these bound it from inside. A
# run that never collided is worth very different amounts depending on whether the two
# generators overlapped at all, and only the plugin's own log can answer that.
gc_log_ms() {
    [[ -f "$1" ]] || { echo ""; return 0; }
    grep -F -- "$2" "$1" 2>/dev/null | head -1 \
        | awk '{ split($2, t, "[:,]"); if (t[1] != "") printf "%d", (t[1]*3600 + t[2]*60 + t[3]) * 1000 + t[4] }'
}

GEN_START_MARK="Web Insight Report: outputDir="
GEN_END_MARK="Report written to:"
GEN_FAIL_MARK="Failed to generate Web Insight Report"

# End of a generator's span — success line, or the failure line when it did not get one.
#
# Without the fallback a LOST report reads as "the generators never overlapped", because
# the span has no end: the one measurement that most needs explaining reports the least
# alarming value. Observed exactly once in a 12-iteration run before this existed.
gc_gen_end_ms() {
    local v
    v="$(gc_log_ms "$1" "$GEN_END_MARK")"
    [[ -n "$v" ]] || v="$(gc_log_ms "$1" "$GEN_FAIL_MARK")"
    printf '%s' "$v"
}

# True when two generators were running at the same instant.
gc_spans_overlap() {
    local a1="$1" a2="$2" b1="$3" b2="$4"
    [[ -n "$a1" && -n "$a2" && -n "$b1" && -n "$b2" ]] || return 1
    [[ "$a1" -lt "$b2" && "$b1" -lt "$a2" ]]
}

# =============================================================================
#  G01 — carry: one container, one mount, run twice
# =============================================================================
echo ""
echo "[G01] Single container writing to a mounted host dir; run, wait, repeat (Pre=carry)..."

G01_DIR="$G_RESULTS/G01"
G01_MOUNT="$G01_DIR/mount"
G01_LOGS="$G01_DIR/logs"
gc_clean_dir "$G01_MOUNT" && gc_clean_dir "$G01_LOGS"

gc_run "$G01_MOUNT" "$G01_LOGS" run1 - "RUN-1" >/dev/null 2>&1
G01_RC1=$?
G01_PAIR_AFTER_1="$(count_glob "$G01_MOUNT"/*.html)"

# Deliberately NOT cleaned — the carried-over report IS the scenario.
gc_run "$G01_MOUNT" "$G01_LOGS" run2 - "RUN-2" >/dev/null 2>&1
G01_RC2=$?

G01_NOTES=""
[[ "$G01_RC1" == 0 ]] || G01_NOTES="$(append_note "$G01_NOTES" "run 1 exited $G01_RC1")"
[[ "$G01_RC2" == 0 ]] || G01_NOTES="$(append_note "$G01_NOTES" "run 2 exited $G01_RC2")"
[[ "$G01_PAIR_AFTER_1" == 1 ]] \
    || G01_NOTES="$(append_note "$G01_NOTES" "run 1 left $G01_PAIR_AFTER_1 .html files")"

G01_HTML_N="$(count_glob "$G01_MOUNT"/*.html)"
G01_JSON_N="$(count_glob "$G01_MOUNT"/*.json)"
[[ "$G01_HTML_N" == 1 && "$G01_JSON_N" == 1 ]] \
    || G01_NOTES="$(append_note "$G01_NOTES" "expected exactly one .html/.json pair, found ${G01_HTML_N}/${G01_JSON_N}")"

G01_TMP="$(gc_tmp_files "$G01_MOUNT")"
[[ -z "$G01_TMP" ]] || G01_NOTES="$(append_note "$G01_NOTES" "stranded staging file(s): $G01_TMP")"

if file_exists "$G01_MOUNT/$DEFAULT_JSON"; then
    G01_JT="$(json_title "$G01_MOUNT/$DEFAULT_JSON")"
    G01_HT="$(html_title "$G01_MOUNT/$DEFAULT_HTML")"
    [[ "$G01_JT" == "RUN-2" ]] \
        || G01_NOTES="$(append_note "$G01_NOTES" "JSON testName was '$G01_JT', expected RUN-2")"
    [[ "$G01_HT" == "RUN-2" ]] \
        || G01_NOTES="$(append_note "$G01_NOTES" "HTML title was '$G01_HT', expected RUN-2")"
else
    G01_NOTES="$(append_note "$G01_NOTES" "no .json in the mount")"
fi

# F2 says the overwrite is intentional AND logged. The WARN is the only signal a user
# ever gets, so its presence is worth recording even though its absence is not fatal.
G01_WARN="no"
grep -qiF "Overwriting an existing report" "$G01_LOGS/run2-runtime.log" 2>/dev/null && G01_WARN="yes"

if [[ -z "$G01_NOTES" ]]; then
    record G01 "carry: repeated container runs into one mount" PASS \
        "run 2 overwrote in place — one pair, testName=RUN-2; F2 overwrite WARN logged: $G01_WARN"
else
    record G01 "carry: repeated container runs into one mount" FAIL "$G01_NOTES"
fi

# =============================================================================
#  G02 — two containers, ONE mount, the SAME default filename, concurrent
# =============================================================================
echo ""
echo "[G02] Two containers, same mount, same default filename, concurrent (Pre=clean per iteration)..."
echo "      $G02_ITERATIONS iterations — one clean run proves nothing about a race."

G02_DIR="$G_RESULTS/G02"
mkdir -p "$G02_DIR"

# Is the final move even atomic here? AtomicFileWriter falls back to a non-atomic replace
# on AtomicMoveNotSupportedException, SILENTLY (AtomicFileWriter.java:101-106), and that
# fallback widens the window it exists to close. Java is not available in the image, so
# probe the underlying capability instead: rename(2) preserves the inode, a copy+unlink
# fallback does not.
G02_ATOMIC="unknown"
G02_PROBE_MOUNT="$G02_DIR/atomic-probe"
gc_clean_dir "$G02_PROBE_MOUNT"
G02_PROBE_OUT="$(docker_cmd run --rm --entrypoint /bin/sh \
    -v "$(host_path "$G02_PROBE_MOUNT"):/results" "$IMAGE_NAME" \
    -c 'cd /results && printf x > .probe.tmp && i1=$(stat -c %i .probe.tmp) && mv .probe.tmp probe.out && i2=$(stat -c %i probe.out) && rm -f probe.out && echo "$i1/$i2"' 2>/dev/null)"
G02_PROBE_OUT="$(printf '%s' "$G02_PROBE_OUT" | tr -d '\r\n')"
if [[ "$G02_PROBE_OUT" == */* ]]; then
    if [[ "${G02_PROBE_OUT%%/*}" == "${G02_PROBE_OUT##*/}" ]]; then
        G02_ATOMIC="yes (inode preserved across rename, $G02_PROBE_OUT)"
    else
        G02_ATOMIC="NO (inode changed, $G02_PROBE_OUT) — the non-atomic fallback widens the window"
    fi
fi
echo "      bind-mount rename(2)/ATOMIC_MOVE support: $G02_ATOMIC"

G02_CLEAN=0
G02_MISMATCH=0
G02_TORN=0
G02_MISSING=0
G02_WRITE_ERR=0
G02_EXPECTED_ERR=0
G02_TMP_SEEN=0
G02_BOTH_TITLES=0
G02_CROSS_WARN=0
G02_SPAN_OVERLAP=0
G02_LOST=0
G02_SILENT=0
G02_TMP_RACE=0
G02_EVIDENCE=""
G02_DELTAS=""
G02_DETAIL=""

for ((i = 1; i <= G02_ITERATIONS; i++)); do
    ITER="$G02_DIR/iter-$i"
    MOUNT="$ITER/mount"
    LOGS="$ITER/logs"
    # Pre = clean PER ITERATION. Reusing the directory would make iteration N a `carry`
    # row wearing G02's name, and last-writer-wins would look identical either way.
    gc_clean_dir "$MOUNT" || { G02_DETAIL="$(append_note "$G02_DETAIL" "iter $i: precondition failed")"; continue; }
    gc_clean_dir "$LOGS"  || { G02_DETAIL="$(append_note "$G02_DETAIL" "iter $i: precondition failed")"; continue; }

    BARRIER=$(( $(date +%s) + BARRIER_LEAD ))
    gc_run "$MOUNT" "$LOGS" a "$BARRIER" "CONTAINER-A" >/dev/null 2>&1 &
    PID_A=$!
    gc_run "$MOUNT" "$LOGS" b "$BARRIER" "CONTAINER-B" >/dev/null 2>&1 &
    PID_B=$!
    wait "$PID_A"; RC_A=$?
    wait "$PID_B"; RC_B=$?

    DELTA="$(gc_delta_ms "$(gc_stamp "$LOGS/a-done.stamp")" "$(gc_stamp "$LOGS/b-done.stamp")")"
    [[ -n "$DELTA" ]] && G02_DELTAS="$G02_DELTAS $DELTA"

    A1="$(gc_log_ms "$LOGS/a-runtime.log" "$GEN_START_MARK")"
    A2="$(gc_gen_end_ms "$LOGS/a-runtime.log")"
    B1="$(gc_log_ms "$LOGS/b-runtime.log" "$GEN_START_MARK")"
    B2="$(gc_gen_end_ms "$LOGS/b-runtime.log")"
    OVERLAP="no"
    if gc_spans_overlap "$A1" "$A2" "$B1" "$B2"; then
        G02_SPAN_OVERLAP=$((G02_SPAN_OVERLAP + 1))
        OVERLAP="yes"
    fi

    HTML="$MOUNT/$DEFAULT_HTML"
    JSON="$MOUNT/$DEFAULT_JSON"
    TMP="$(gc_tmp_files "$MOUNT")"
    OUTCOME=""
    # What this iteration actually lost, so the exit status can be judged against it
    # further down rather than counted on its own.
    ITER_LOST=no
    ITER_INCOMPLETE=no

    if [[ -n "$TMP" ]]; then
        G02_TMP_SEEN=$((G02_TMP_SEEN + 1))
        OUTCOME="stranded-tmp:$TMP"
    fi

    # A surviving artifact says nothing about how many runs produced one. Both containers
    # target the same name, so the winner's report is present either way — the question is
    # whether the LOSER got a report at all, and only its own log can answer that.
    A_WROTE="no"; grep -qF "$GEN_END_MARK" "$LOGS/a-runtime.log" 2>/dev/null && A_WROTE="yes"
    B_WROTE="no"; grep -qF "$GEN_END_MARK" "$LOGS/b-runtime.log" 2>/dev/null && B_WROTE="yes"
    if [[ "$A_WROTE" == "no" || "$B_WROTE" == "no" ]]; then
        G02_LOST=$((G02_LOST + 1))
        ITER_LOST=yes
        OUTCOME="${OUTCOME:+$OUTCOME,}report-lost(a-wrote=$A_WROTE,b-wrote=$B_WROTE)"
        # This used to be the part that made it dangerous rather than merely wrong:
        # JMeter's exit status did not carry the plugin's failure, so a CI job saw
        # success and no report, and "both exited 0" was the evidence the hazard was
        # SILENT. failonerror now defaults to true, so that meaning is inverted — a lost
        # report is supposed to fail its run. Exit 0 here is no longer the documented
        # silence being confirmed; it is the new default failing to fire, which is a
        # defect in its own right. Same counter, opposite reading.
        if [[ "$RC_A" == 0 && "$RC_B" == 0 ]]; then
            G02_SILENT=$((G02_SILENT + 1))
            OUTCOME="$OUTCOME,silent-loss(both exited 0 despite a lost report)"
        fi
    fi

    # F10's exact signature: the loser's staging sibling was renamed out from under it by
    # the winner, because BOTH derive the same ".<targetname>.tmp" path. Captured verbatim
    # rather than as a boolean — the path in the message IS the evidence.
    EVID="$(grep -hoE 'NoSuchFileException: [^ ]*\.tmp' "$LOGS/a-runtime.log" "$LOGS/b-runtime.log" 2>/dev/null | head -1)"
    if [[ -n "$EVID" ]]; then
        G02_TMP_RACE=$((G02_TMP_RACE + 1))
        G02_EVIDENCE="$EVID"
        OUTCOME="${OUTCOME:+$OUTCOME,}$EVID"
    fi

    if ! file_exists "$HTML" || ! file_exists "$JSON" || ! json_valid "$JSON"; then
        G02_MISSING=$((G02_MISSING + 1))
        ITER_INCOMPLETE=yes
        OUTCOME="${OUTCOME:+$OUTCOME,}missing-or-invalid-artifact"
    else
        HT="$(html_title "$HTML")"
        JT="$(json_title "$JSON")"
        if ! gc_html_complete "$HTML"; then
            G02_TORN=$((G02_TORN + 1))
            OUTCOME="${OUTCOME:+$OUTCOME,}truncated-html"
        fi
        # Both markers in one file cannot be last-writer-wins; it is interleaving.
        if gc_contains "$HTML" "CONTAINER-A" && gc_contains "$HTML" "CONTAINER-B"; then
            G02_BOTH_TITLES=$((G02_BOTH_TITLES + 1))
            OUTCOME="${OUTCOME:+$OUTCOME,}both-titles-in-one-html"
        fi
        if [[ "$HT" != "$JT" ]]; then
            G02_MISMATCH=$((G02_MISMATCH + 1))
            OUTCOME="${OUTCOME:+$OUTCOME,}mismatch(html=$HT,json=$JT)"
        fi
    fi

    # The exit status is judged LAST, against what this iteration actually produced.
    # A non-zero exit is now the CORRECT answer to a lost or incomplete report —
    # failonerror defaults to true and covers a partial set — so counting every non-zero
    # as a write error would score a correctly detected loss twice, once as report-lost
    # and again as damage, and the row would read the fix as the hazard. Only a non-zero
    # exit with a complete, present artifact set is unexplained, and only that stays in
    # G02_DAMAGED below.
    if [[ "$RC_A" != 0 || "$RC_B" != 0 ]]; then
        if [[ "$ITER_LOST" == "yes" || "$ITER_INCOMPLETE" == "yes" ]]; then
            G02_EXPECTED_ERR=$((G02_EXPECTED_ERR + 1))
            OUTCOME="${OUTCOME:+$OUTCOME,}reported-nonzero-exit(a=$RC_A,b=$RC_B)"
        else
            G02_WRITE_ERR=$((G02_WRITE_ERR + 1))
            OUTCOME="${OUTCOME:+$OUTCOME,}unexplained-nonzero-exit(a=$RC_A,b=$RC_B)"
        fi
    fi

    # F6 is per-JVM, so neither container can see the other's write coming. If one of
    # them nevertheless logs the overwrite WARN, it found the other's finished report —
    # which dates the two writes relative to each other far better than the stamps do.
    if grep -qiF "Overwriting an existing report" "$LOGS/a-runtime.log" "$LOGS/b-runtime.log" 2>/dev/null; then
        G02_CROSS_WARN=$((G02_CROSS_WARN + 1))
    fi

    if [[ -z "$OUTCOME" ]]; then
        G02_CLEAN=$((G02_CLEAN + 1))
        OUTCOME="clean-last-wins(${HT})"
    fi
    echo "      iter $i: $OUTCOME  [exit skew ${DELTA:-?}ms, generators overlapped: $OVERLAP]"
    G02_DETAIL="$(append_note "$G02_DETAIL" "$i:$OUTCOME")"
done

printf '%s\n' "$G02_DETAIL" > "$G02_DIR/outcomes.txt" 2>/dev/null
G02_SKEW_SUMMARY="$(printf '%s\n' $G02_DELTAS | awk 'NF { n++; s += $1; if (min == "" || $1 < min) min = $1; if ($1 > max) max = $1 } END { if (n) printf "min=%dms mean~%dms max=%dms", min, s / n, max; else printf "unmeasured" }')"
# G02_EXPECTED_ERR is deliberately NOT in this sum: a run that lost or truncated its
# artifact set and said so with a non-zero status is the contract working. The loss
# itself is already counted, once, as G02_LOST or G02_MISSING.
G02_DAMAGED=$((G02_MISMATCH + G02_TORN + G02_MISSING + G02_WRITE_ERR + G02_TMP_SEEN + G02_BOTH_TITLES + G02_LOST + G02_TMP_RACE))

G02_SUMMARY="clean-last-wins=$G02_CLEAN/$G02_ITERATIONS, report-lost=$G02_LOST (of which exited 0 anyway, so the failonerror default never fired: $G02_SILENT), tmp-collision=$G02_TMP_RACE, mismatched=$G02_MISMATCH, truncated=$G02_TORN, missing=$G02_MISSING, unexplained-nonzero-exit=$G02_WRITE_ERR, correctly-reported-nonzero-exit=$G02_EXPECTED_ERR, stranded-tmp=$G02_TMP_SEEN, both-titles=$G02_BOTH_TITLES; generators overlapped in $G02_SPAN_OVERLAP/$G02_ITERATIONS; overwrite-WARN seen in $G02_CROSS_WARN; exit skew $G02_SKEW_SUMMARY; ATOMIC_MOVE=$G02_ATOMIC${G02_EVIDENCE:+; evidence: $G02_EVIDENCE}"

if [[ "$G02_DAMAGED" -gt 0 ]]; then
    record G02 "concurrent containers, one mount, same filename" FAIL \
        "hazard REPRODUCED — $G02_SUMMARY. This is the F6/F10 defect surfacing, not a suite error."
else
    record G02 "concurrent containers, one mount, same filename" PASS \
        "no corruption observed — $G02_SUMMARY. Unproven, not disproven: the writes never landed inside each other's window."
fi

# =============================================================================
#  G03 — two containers, one mount, DISTINCT filenames
# =============================================================================
echo ""
echo "[G03] Two containers, same mount, distinct filenames (Pre=clean per iteration)..."

G03_DIR="$G_RESULTS/G03"
mkdir -p "$G03_DIR"
G03_BAD=0
G03_NOTES=""

for ((i = 1; i <= G03_ITERATIONS; i++)); do
    ITER="$G03_DIR/iter-$i"
    MOUNT="$ITER/mount"
    LOGS="$ITER/logs"
    gc_clean_dir "$MOUNT" || { G03_BAD=$((G03_BAD + 1)); continue; }
    gc_clean_dir "$LOGS"  || { G03_BAD=$((G03_BAD + 1)); continue; }

    BARRIER=$(( $(date +%s) + BARRIER_LEAD ))
    gc_run "$MOUNT" "$LOGS" a "$BARRIER" "CONTAINER-A" -Jwebinsight.report.filename=report-a.html >/dev/null 2>&1 &
    PID_A=$!
    gc_run "$MOUNT" "$LOGS" b "$BARRIER" "CONTAINER-B" -Jwebinsight.report.filename=report-b.html >/dev/null 2>&1 &
    PID_B=$!
    wait "$PID_A"; RC_A=$?
    wait "$PID_B"; RC_B=$?

    ITER_NOTES=""
    [[ "$RC_A" == 0 && "$RC_B" == 0 ]] || ITER_NOTES="$(append_note "$ITER_NOTES" "exit a=$RC_A b=$RC_B")"
    for pair in "report-a:CONTAINER-A" "report-b:CONTAINER-B"; do
        base="${pair%%:*}"; want="${pair##*:}"
        if ! file_exists "$MOUNT/$base.html" || ! file_exists "$MOUNT/$base.json"; then
            ITER_NOTES="$(append_note "$ITER_NOTES" "$base.{html,json} incomplete")"
            continue
        fi
        got="$(json_title "$MOUNT/$base.json")"
        [[ "$got" == "$want" ]] || ITER_NOTES="$(append_note "$ITER_NOTES" "$base.json testName='$got' want '$want'")"
        gc_html_complete "$MOUNT/$base.html" || ITER_NOTES="$(append_note "$ITER_NOTES" "$base.html truncated")"
    done
    TMP="$(gc_tmp_files "$MOUNT")"
    [[ -z "$TMP" ]] || ITER_NOTES="$(append_note "$ITER_NOTES" "stranded staging file(s): $TMP")"

    if [[ -n "$ITER_NOTES" ]]; then
        G03_BAD=$((G03_BAD + 1))
        G03_NOTES="$(append_note "$G03_NOTES" "iter $i: $ITER_NOTES")"
        echo "      iter $i: $ITER_NOTES"
    else
        echo "      iter $i: both pairs correct, no staging debris"
    fi
done

if [[ "$G03_BAD" == 0 ]]; then
    record G03 "concurrent containers, one mount, distinct filenames" PASS \
        "$G03_ITERATIONS/$G03_ITERATIONS iterations produced two correct, complete pairs — distinct names give distinct staging siblings (F10), so the mount is not a shared resource"
else
    record G03 "concurrent containers, one mount, distinct filenames" FAIL \
        "$G03_BAD/$G03_ITERATIONS iterations damaged: $G03_NOTES"
fi

# =============================================================================
#  G04 — ownership and permissions of the artifacts, seen from the host
# =============================================================================
echo ""
echo "[G04] Ownership and permissions of artifacts on the host after a container run (Pre=clean)..."

G04_DIR="$G_RESULTS/G04"
G04_MOUNT="$G04_DIR/mount"
G04_LOGS="$G04_DIR/logs"
gc_clean_dir "$G04_MOUNT" && gc_clean_dir "$G04_LOGS"

gc_run "$G04_MOUNT" "$G04_LOGS" default - "DEFAULT-USER" >/dev/null 2>&1
G04_RC=$?

G04_NOTES=""
if ! file_exists "$G04_MOUNT/$DEFAULT_HTML"; then
    record G04 "artifact ownership on the host" FAIL "no report produced (exit $G04_RC) — nothing to inspect"
else
    G04_HOST_STAT="$(stat -c '%U:%G uid=%u gid=%g mode=%a' "$G04_MOUNT/$DEFAULT_HTML" 2>/dev/null)"
    [[ -n "$G04_HOST_STAT" ]] || G04_HOST_STAT="$(stat -f '%Su:%Sg uid=%u gid=%g mode=%Lp' "$G04_MOUNT/$DEFAULT_HTML" 2>/dev/null)"

    # What the host shell reports is a mapping on Docker Desktop; what a CONTAINER sees is
    # the number that actually decides whether the next run can overwrite. Ask for both.
    G04_CONT_STAT="$(docker_cmd run --rm --entrypoint /bin/sh \
        -v "$(host_path "$G04_MOUNT"):/results" "$IMAGE_NAME" \
        -c "stat -c 'uid=%u gid=%g mode=%a' /results/$DEFAULT_HTML" 2>/dev/null | tr -d '\r\n')"

    [[ -r "$G04_MOUNT/$DEFAULT_HTML" ]] || G04_NOTES="$(append_note "$G04_NOTES" "HTML is NOT readable by the host user")"
    [[ -r "$G04_MOUNT/$DEFAULT_JSON" ]] || G04_NOTES="$(append_note "$G04_NOTES" "JSON is NOT readable by the host user")"

    # Readable is half the promise. The row's real hazard is the NEXT run being unable to
    # replace the file, so prove the host can rewrite it — non-destructively.
    if cp "$G04_MOUNT/$DEFAULT_JSON" "$G04_LOGS/json.bak" 2>/dev/null \
       && printf 'host-write-probe' >> "$G04_MOUNT/$DEFAULT_JSON" 2>/dev/null; then
        cp -f "$G04_LOGS/json.bak" "$G04_MOUNT/$DEFAULT_JSON" 2>/dev/null
        G04_HOST_WRITE="yes"
    else
        G04_HOST_WRITE="NO"
        G04_NOTES="$(append_note "$G04_NOTES" "host user cannot rewrite the artifacts")"
    fi

    # The rider on the Expected column: does a ROOT-written report block a later
    # non-root container from overwriting it? Own directory, so it cannot disturb the
    # measurement above.
    G04_ROOT_MOUNT="$G04_DIR/root-mount"
    G04_ROOT_LOGS="$G04_DIR/root-logs"
    gc_clean_dir "$G04_ROOT_MOUNT" && gc_clean_dir "$G04_ROOT_LOGS"
    GC_USER="0:0" gc_run "$G04_ROOT_MOUNT" "$G04_ROOT_LOGS" root - "ROOT-RUN" >/dev/null 2>&1
    G04_ROOT_RC=$?
    G04_ROOT_CONT_STAT="$(docker_cmd run --rm --entrypoint /bin/sh \
        -v "$(host_path "$G04_ROOT_MOUNT"):/results" "$IMAGE_NAME" \
        -c "stat -c 'uid=%u gid=%g mode=%a' /results/$DEFAULT_HTML" 2>/dev/null | tr -d '\r\n')"
    gc_run "$G04_ROOT_MOUNT" "$G04_ROOT_LOGS" after - "NONROOT-AFTER-ROOT" >/dev/null 2>&1
    G04_AFTER_RC=$?
    G04_AFTER_TITLE="$(json_title "$G04_ROOT_MOUNT/$DEFAULT_JSON")"
    if [[ "$G04_AFTER_TITLE" == "NONROOT-AFTER-ROOT" ]]; then
        G04_ROOT_BLOCK="no — the non-root run overwrote the root-owned report"
    else
        G04_ROOT_BLOCK="YES — root-owned report survived a later non-root run (testName still '$G04_AFTER_TITLE', exit $G04_AFTER_RC)"
    fi

    G04_SUMMARY="host: ${G04_HOST_STAT:-unavailable}; container sees: ${G04_CONT_STAT:-unavailable}; host-rewritable: $G04_HOST_WRITE; root-written artifacts (container sees ${G04_ROOT_CONT_STAT:-unavailable}, exit $G04_ROOT_RC) block a later non-root run: $G04_ROOT_BLOCK"

    if [[ -z "$G04_NOTES" ]]; then
        record G04 "artifact ownership on the host" PASS "$G04_SUMMARY"
    else
        record G04 "artifact ownership on the host" FAIL "$G04_NOTES -- $G04_SUMMARY"
    fi
fi

# =============================================================================
#  G05 — seed: container writes into a mount already holding a HOST-written report
# =============================================================================
echo ""
echo "[G05] Container writes into a mount already holding a host-written report (Pre=seed HOST-SEED)..."

G05_DIR="$G_RESULTS/G05"
G05_MOUNT="$G05_DIR/mount"
G05_LOGS="$G05_DIR/logs"
gc_clean_dir "$G05_MOUNT" && gc_clean_dir "$G05_LOGS"

# The seed must come from the HOST — that is the whole point of the row: the mount, not
# the writer, is the variable under test. A container-written seed would still be a valid
# "existing report" precondition but would prove nothing about the host/container boundary,
# so if the host cannot seed, say so in the note rather than silently substituting.
G05_SEED_SOURCE="none"
if JMETER_HOME="$(detect_jmeter_home 2>/dev/null)" \
   && [[ -f "$JMETER_HOME/lib/ext/$(basename "$PLUGIN_JAR")" ]]; then
    export JMETER_HOME
    JMETER="$JMETER_HOME/bin/jmeter"
    WIR_RUNTIME_LOG="$G05_LOGS/host-seed-runtime.log" \
    run_jmeter -n -t "$JMX_HOST" \
        -l "$G05_LOGS/host-seed.jtl" \
        -Jthreads=2 -Jduration=4 -Jrampup=1 \
        -Jwebinsight.report.output="$G05_MOUNT" \
        -Jwebinsight.report.title=HOST-SEED \
        > "$G05_LOGS/host-seed.log" 2>&1
    file_exists "$G05_MOUNT/$DEFAULT_JSON" && G05_SEED_SOURCE="host"
fi

if [[ "$G05_SEED_SOURCE" != "host" ]]; then
    # Fall back to a container-written seed so the row still runs, and label it: the
    # precondition is genuine, the host/container provenance is not.
    gc_run "$G05_MOUNT" "$G05_LOGS" seed - "HOST-SEED" >/dev/null 2>&1
    file_exists "$G05_MOUNT/$DEFAULT_JSON" && G05_SEED_SOURCE="container (host JMeter unavailable or plugin not deployed)"
fi

G05_NOTES=""
if [[ "$G05_SEED_SOURCE" == "none" ]]; then
    record G05 "container run over a host-written report" BLOCKED "could not seed the mount at all"
else
    G05_SEED_TITLE="$(json_title "$G05_MOUNT/$DEFAULT_JSON")"
    [[ "$G05_SEED_TITLE" == "HOST-SEED" ]] \
        || G05_NOTES="$(append_note "$G05_NOTES" "seed testName was '$G05_SEED_TITLE', not HOST-SEED — precondition unsound")"

    gc_run "$G05_MOUNT" "$G05_LOGS" container - "CONTAINER-RUN" >/dev/null 2>&1
    G05_RC=$?
    [[ "$G05_RC" == 0 ]] || G05_NOTES="$(append_note "$G05_NOTES" "container run exited $G05_RC")"

    G05_JT="$(json_title "$G05_MOUNT/$DEFAULT_JSON")"
    G05_HT="$(html_title "$G05_MOUNT/$DEFAULT_HTML")"
    [[ "$G05_JT" == "CONTAINER-RUN" ]] \
        || G05_NOTES="$(append_note "$G05_NOTES" "JSON testName was '$G05_JT' — the listener did not overwrite (F2)")"
    [[ "$G05_HT" == "CONTAINER-RUN" ]] \
        || G05_NOTES="$(append_note "$G05_NOTES" "HTML title was '$G05_HT'")"
    G05_HTML_N="$(count_glob "$G05_MOUNT"/*.html)"
    [[ "$G05_HTML_N" == 1 ]] \
        || G05_NOTES="$(append_note "$G05_NOTES" "expected one .html after the overwrite, found $G05_HTML_N")"

    G05_WARN="no"
    grep -qiF "Overwriting an existing report" "$G05_LOGS/container-runtime.log" 2>/dev/null && G05_WARN="yes"

    # The other half of the row: the -g exporter SKIPS an existing report (F8), and the
    # mount should not change that either. Its own directory, seeded with a copy of the
    # artifacts just produced, so a failure here cannot disturb the listener assertion
    # above. The seed marker is CONTAINER-RUN, unambiguously different from EXPORTER-RUN.
    #
    # -o is pointed at the SEEDED directory on purpose. The exporter resolves its output
    # from `jmeter.reportgenerator.outputdir` FIRST (WebInsightDataExporter:174-178), so a
    # `-o` elsewhere plus `-Jwebinsight.report.output` at the mount writes to the -o dir and
    # tests nothing — the first version of this probe did exactly that and reported a
    # confident "ran but left the seed" while writing into /logs/dashboard.
    #
    # The exporter is registered via -J rather than reportgenerator.properties: the image
    # is stock JMeter and register_cli_exporter only ever edits the HOST install. That the
    # -J route works at all is worth knowing — verified in the runtime log.
    G05_EXP_MOUNT="$G05_DIR/exporter-mount"
    G05_EXP_LOGS="$G05_DIR/exporter-logs"
    gc_clean_dir "$G05_EXP_MOUNT" && gc_clean_dir "$G05_EXP_LOGS"
    cp -f "$G05_LOGS/host-seed.jtl" "$G05_EXP_LOGS/seed.jtl" 2>/dev/null \
        || cp -f "$G05_LOGS/seed-results.jtl" "$G05_EXP_LOGS/seed.jtl" 2>/dev/null
    G05_EXP_VERDICT="not run"
    if [[ -f "$G05_EXP_LOGS/seed.jtl" ]]; then
        cp -f "$G05_MOUNT/$DEFAULT_HTML" "$G05_EXP_MOUNT/$DEFAULT_HTML" 2>/dev/null
        cp -f "$G05_MOUNT/$DEFAULT_JSON" "$G05_EXP_MOUNT/$DEFAULT_JSON" 2>/dev/null
        docker_cmd run --rm ${DOCKER_USER[@]+"${DOCKER_USER[@]}"} --entrypoint /bin/sh \
            -v "$(host_path "$PLUGIN_JAR"):$CONTAINER_EXT/$(basename "$PLUGIN_JAR")" \
            -v "$(host_path "$G05_EXP_MOUNT"):/results" \
            -v "$(host_path "$G05_EXP_LOGS"):/logs" \
            "$IMAGE_NAME" -c 'jmeter "$@" > /logs/exporter-console.log 2>&1' sh \
            -g /logs/seed.jtl -o /results \
            -j /logs/exporter-runtime.log \
            -Jjmeter.reportgenerator.exporter.webinsight.classname=com.jmeterwebinsightreport.report.exporter.WebInsightDataExporter \
            -Jwebinsight.report.title=EXPORTER-RUN >/dev/null 2>&1
        G05_EXP_RC=$?
        G05_EXP_TITLE="$(json_title "$G05_EXP_MOUNT/$DEFAULT_JSON")"
        if grep -qiF "skipping CLI generation" "$G05_EXP_LOGS/exporter-runtime.log" 2>/dev/null; then
            G05_EXP_VERDICT="skipped via F8's collision check, testName still '$G05_EXP_TITLE'"
        elif [[ "$G05_EXP_TITLE" == "EXPORTER-RUN" ]]; then
            G05_EXP_VERDICT="DID NOT SKIP — the exporter overwrote the existing report (testName=EXPORTER-RUN)"
        elif grep -qF "as folder is not empty" "$G05_EXP_LOGS/exporter-console.log" 2>/dev/null; then
            G05_EXP_VERDICT="seed survived (testName still '$G05_EXP_TITLE') but JMeter itself refused the non-empty -o directory and exited $G05_EXP_RC — the exporter never ran, so F8's skip is UNREACHABLE from -g when the existing report sits in the output dir; the mount is irrelevant to that"
        else
            G05_EXP_VERDICT="inconclusive — exit $G05_EXP_RC, testName '$G05_EXP_TITLE'"
        fi
    fi

    if [[ -z "$G05_NOTES" ]]; then
        record G05 "container run over a host-written report" PASS \
            "seed=$G05_SEED_SOURCE; listener overwrote in place, testName=CONTAINER-RUN, one pair; F2 WARN: $G05_WARN; -g exporter half: $G05_EXP_VERDICT"
    else
        record G05 "container run over a host-written report" FAIL \
            "$G05_NOTES (seed=$G05_SEED_SOURCE; -g exporter half: $G05_EXP_VERDICT)"
    fi
fi

print_summary "FAMILY G — DOCKER / SHARED MOUNT SUMMARY"
echo ""
echo "  Results: $G_RESULTS"

exit $FAIL
