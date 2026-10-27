#!/usr/bin/env bash
#
# Golden artifacts: the same fixed input must keep producing the same .json and .xml.
#
# WHAT THIS EXISTS FOR. Snapshot fixtures were deleted, and CLAUDE.md has said ever since that
# nothing replaced them: "a change that silently moves a verdict on an unedited annotations file
# now has nothing standing in its way." The documented substitute -- regenerate every fixture and
# diff the .json with runId stripped -- CANNOT WORK, and that is why it was never run: the
# fixtures are live, timer-driven JMeter runs, so duration, totalSamples and every measured
# number move on every execution. The diff is never empty and nobody reads it twice.
#
# The contract "same JTL => same runId" names the fix. Build the goldens from a COMMITTED, fixed
# JTL through the `-g -o` exporter, never from a live listener run. Then every number is a
# constant, and a diff means something changed in the code.
#
# WHAT IT CATCHES: any change that moves a status, a count, an attainment number, a finding
# message, a JUnit failure message, a wire name or a key order on an unedited input.
# WHAT IT DOES NOT: anything that only manifests through the live listener, because that path is
# not reproducible. That gap is real and is written down rather than papered over.
#
# ACCEPTING A CHANGE: ./run-goldens.sh --accept regenerates the baselines. The diff then appears
# in the pull request that caused it, which is the review mechanism the old fixtures had.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../../scripts/lib/common.sh
source "$SCRIPT_DIR/../../scripts/lib/common.sh"

GOLDEN_DIR="$PROJECT_DIR/test-plans/goldens"
EXPECTED_DIR="$GOLDEN_DIR/expected"
WORK_DIR="$PROJECT_DIR/test-results/_goldens"
CANON="$SCRIPT_DIR/canonicalise-artifact.py"

ACCEPT=0
[[ "${1:-}" == "--accept" ]] && ACCEPT=1

require_jmeter
require_deployed_plugin

# require_python, not `command -v python3`: on Windows that name is the Microsoft Store stub,
# which IS on PATH and runs nothing, so the naive lookup finds a python that cannot execute.
# Loud either way, never a silent skip -- a golden check that cannot run is indistinguishable
# from one that passes, which is the failure mode this whole stage exists to remove.
require_python || { echo "[GOLDEN] FAIL: the goldens could not be compared" >&2; exit 1; }
PY="$PYTHON"

# id | annotations file (or -) | baseline jtl (or -) | server-metrics payload or CSV (or -) | extra -J (or none)
CASES=(
    "G1|-|-|-"
    "G2|gate-mixed.json|-|-"
    "G3|gate-broken.json|-|-"
    "G4|gate-server.json|-|server-metrics.json"
    "G5|gate-server-fail.json|-|server-metrics.json"
    "G7|gate-server-coverage.json|-|server-metrics.json"
    "G8|gate-server-fail.json|-|server-metrics-that-does-not-exist.json"
    "G9|gate-digits.json|-|-"
    "G10|gate-server.json|-|server-metrics.csv|-Jwebinsight.report.servermetrics.step=1s"
    "G11|gate-catalogue.json|-|../server-catalogue/server-metrics.json"
)
# G11 is the only golden with CATALOGUE components: a postgres and a kafka component over E27's
# committed payload, each assert naming a catalogue metric, one overriding the catalogue's reducer.
# Every other server golden is custom, so how a catalogue assert reaches the wire - serverAsserts[]
# .metric, the catalogue's query, unit, reduce and across, the derived id that names its JUnit
# testcase, and the failure body's `metric:` line (events FAILs for it) - was pinned by nothing
# (Stage 6, the audit).
# G10 is G4 with the CSV given straight to -g (P4): the exporter collects it in-process with the
# collector's own code, at the step G4's payload was collected at. Its server results must read as
# G4's - only the collection's identity (collectionId, collectedAt) may differ.
# The first G4 -- gate-mixed against a baseline JTL -- was removed after its first generation,
# because its artifacts differed from G2's only by the output path. The CI/CD JSON and the JUnit
# XML carry no comparison block at all; the comparison lives in the HTML, which this stage
# deliberately does not compare. A case that cannot fail for the reason it was added is worse
# than no case, so test-plans/goldens/baseline.jtl stays for whoever adds an HTML-aware golden
# later.
#
# This G4 is the server gates. Its client half is three rules, not gate-mixed's set: two load
# rules -- one that PASSes and one deliberately unreachable, so the server asserts naming it through
# requiresLoad land on LOAD_NOT_ACHIEVED -- plus gate-mixed's own checkout-p95-fails for a real
# measured breach. Its server half is a serverMetrics block over a
# committed CSV (server-metrics.csv, the --csv backend) and a committed payload collected against
# plain.jtl under the default title. Both are constants -- the payload's runId is plain.jtl's,
# its collectionId is whatever wir-collect stamped the day it was committed -- so the .json's
# serverGates[]/serverAsserts[] and the .xml's per-gate suites are constants too, and a diff means
# the evaluator, a reducer, a reason word or a wire name moved on an unedited file.
#
# G5-G8 declare server asserts and NO client rule, over the same recording and payload, because
# in G4 the client gate already FAILs and so decides every jq idiom on its own: the server half
# of test-plans/goldens/jq-gate.txt was run over no document where it mattered, and a wrong key,
# a deleted clause or an inverted failsBuild test all passed verify_jq_idioms (review of
# 961f11b). Each is a shape a server-only pipeline meets, and each is where one clause decides:
#   G5  a building gate FAILs                      serverStatus FAIL, no gate block
#   G7  a building gate PASSes with LOW_COVERAGE    a config error under a PASS word
#   G8  G5's file, payload absent                   refused where a gate builds: one ERROR finding
# G8's payload path does not exist ON PURPOSE: a missing file is the refusal with the least
# machinery, and its published sentence names only the file, so the baseline is a constant.
#
# G9 is client rules only, each band pinned within a hundredth (or less) of plain.jtl's measured
# value, on it or just across it. Every other golden's nearest client actual is 24 from its band,
# so none of them could see a number written ON the band it breached (`"actual": 119.36` beside
# `"threshold": 119.36`) or a PASS printed on its fail band -- the cases where two decimals mislead.

mkdir -p "$EXPECTED_DIR"
rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR"

fail=0
accepted=0

for case in "${CASES[@]}"; do
    IFS='|' read -r id ann base payload extra <<< "$case"
    out="$WORK_DIR/$id"
    # NOT created here, and nothing is written into it before JMeter runs: `-g -o` refuses a
    # directory that is not empty, so even the log has to live outside it. CLAUDE.md documents
    # the same trap for the publish lock file, which is why `rm -rf dir` beats `rm -rf dir/*`.
    log="$WORK_DIR/$id.jmeter.log"

    args=(-g "$GOLDEN_DIR/plain.jtl" -o "$out"
          -Jwebinsight.report.junit=true
          -Jwebinsight.report.failonerror=false
          -Jwebinsight.report.filename="$id-report.html")
    [[ "$ann"  != "-" ]] && args+=(-Jwebinsight.report.annotations="$GOLDEN_DIR/$ann")
    [[ "$base" != "-" ]] && args+=(-Jwebinsight.report.baseline="$GOLDEN_DIR/$base")
    [[ "$payload" != "-" ]] && args+=(-Jwebinsight.report.servermetrics="$GOLDEN_DIR/$payload")
    [[ -n "$extra" ]] && args+=("$extra")

    echo "[GOLDEN] $id ..."
    "$JMETER" "${args[@]}" > "$log" 2>&1
    rc=$?

    for ext in json xml; do
        produced="$out/$id-report.$ext"
        if [[ ! -f "$produced" ]]; then
            echo "[GOLDEN] $id: no .$ext was written (jmeter rc=$rc) — see $log" >&2
            fail=1
            continue
        fi
        expected="$EXPECTED_DIR/$id.$ext"
        actual="$WORK_DIR/$id.canonical.$ext"
        volatile=()
        [[ "$payload" == *.csv ]] && volatile=(--volatile-collection)
        "$PY" "$CANON" "$produced" "$actual" "${volatile[@]}" || { echo "[GOLDEN] $id: canonicalise failed" >&2; fail=1; continue; }

        if [[ $ACCEPT -eq 1 ]]; then
            cp "$actual" "$expected"
            accepted=$((accepted + 1))
            continue
        fi
        if [[ ! -f "$expected" ]]; then
            echo "[GOLDEN] $id.$ext has no baseline. Run: $0 --accept" >&2
            fail=1
            continue
        fi
        if ! diff -u "$expected" "$actual" > "$WORK_DIR/$id.$ext.diff"; then
            echo "[GOLDEN] $id.$ext CHANGED:" >&2
            head -40 "$WORK_DIR/$id.$ext.diff" >&2
            echo "[GOLDEN] full diff: $WORK_DIR/$id.$ext.diff" >&2
            echo "[GOLDEN] if this change is intended: $0 --accept" >&2
            fail=1
        fi
    done
done

if [[ $ACCEPT -eq 1 ]]; then
    echo "[GOLDEN] accepted $accepted baseline(s) into test-plans/goldens/expected/"
    echo "[GOLDEN] commit them WITH the change that caused them, and say which in the message."
    # exit $fail, not 0: a case that crashed above set fail=1 and contributed nothing to
    # $accepted, so an unconditional 0 here reported a PARTIAL baseline set as a clean
    # accept — the stderr said otherwise, but nothing reading $? could tell.
    [[ $fail -eq 0 ]] || echo "[GOLDEN] FAIL: some artifacts did not regenerate — the accepted set is partial." >&2
    exit $fail
fi

# Accepting must be a deliberate act, never a side effect of a green run. A dirty baseline
# beside a passing stage means someone regenerated and did not look.
# `git status --porcelain`, not `git diff`: diff cannot see an untracked file by definition, so
# a baseline accepted for a case that never had one passed green while uncommitted -- and a new
# case is exactly when a reviewer most needs to be told the baseline is unreviewed. M4's G4 was
# the first time that path was ever taken.
if git -C "$PROJECT_DIR" rev-parse --git-dir >/dev/null 2>&1; then
    if [[ $fail -eq 0 && -n "$(git -C "$PROJECT_DIR" status --porcelain -- test-plans/goldens/expected)" ]]; then
        echo "[GOLDEN] FAIL: the stage passes but expected/ is dirty in the working tree." >&2
        echo "         That means the baselines were regenerated rather than the code fixed." >&2
        git -C "$PROJECT_DIR" status --porcelain -- test-plans/goldens/expected >&2
        fail=1
    fi
fi

if [[ $fail -eq 0 ]]; then
    echo "[GOLDEN] PASS — ${#CASES[@]} artifact sets unchanged"
else
    echo "[GOLDEN] FAIL" >&2
fi
exit $fail
