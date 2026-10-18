#!/usr/bin/env bash
# =============================================================================
#  Is the JAR in lib/ext actually the code in the working tree?
#
#  WARNS. NEVER BLOCKS. It is wired to a Stop hook, which fires at the end of
#  every turn, so it prints NOTHING unless it has something to say — a hook that
#  speaks on every turn is noise, and noise gets switched off, and then the check
#  that mattered is gone too.
#
#  What it catches, and why each one bites:
#
#    1. More than one plugin JAR in lib/ext. They land on JMeter's flat classpath
#       and which copy wins is non-deterministic. You can be testing either.
#
#    2. Source newer than the deployed JAR. Everything still runs; it just runs
#       the PREVIOUS build. This is the one that wastes an afternoon.
#
#    3. src/main changed against the primary branch with no <revision> bump.
#       CLAUDE.md: bump and redeploy for every change you want to see in a real
#       JMeter run.
#
#  Run standalone any time:  ./scripts/check-deploy-freshness.sh
# =============================================================================
set -uo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_DIR" 2>/dev/null || exit 0

warnings=()

# Silent no-op outside a git repo or without a JMeter to deploy into — this is a
# convenience check, not a gate, and it must never be the reason something fails.
git rev-parse --git-dir >/dev/null 2>&1 || exit 0

revision="$(sed -n 's|.*<revision>\(.*\)</revision>.*|\1|p' pom.xml 2>/dev/null | head -1)"
[[ -z "$revision" ]] && exit 0

# JMETER_HOME resolves the same way build-and-deploy.sh resolves it, minus the
# PATH fallback: if it is not obvious, stay quiet rather than guess wrong.
jmeter_home="${JMETER_HOME:-}"
if [[ -z "$jmeter_home" ]]; then
    jmeter_home="$(ls -d "$PROJECT_DIR"/apache-jmeter-* 2>/dev/null | head -1)"
fi
ext_dir="$jmeter_home/lib/ext"

if [[ -d "$ext_dir" ]]; then
    mapfile -t jars < <(ls "$ext_dir"/jmeter-web-insight-report-*.jar 2>/dev/null)

    if [[ ${#jars[@]} -gt 1 ]]; then
        warnings+=("lib/ext holds ${#jars[@]} plugin JARs. JMeter's classpath is flat, so which one wins is non-deterministic. Run ./scripts/build-and-deploy.sh — it purges the old ones.")
    elif [[ ${#jars[@]} -eq 1 ]]; then
        deployed="$(basename "${jars[0]}")"
        deployed_version="${deployed#jmeter-web-insight-report-}"
        deployed_version="${deployed_version%.jar}"

        if [[ "$deployed_version" != "$revision" ]]; then
            warnings+=("lib/ext has $deployed_version but pom.xml says $revision. A real JMeter run would use $deployed_version.")
        else
            # Content would be better than mtime, but the fixture stamps already
            # own that comparison and this check has to be cheap enough to run at
            # the end of every turn.
            newest_src="$(find jmeter-report-core/src/main jmeter-web-insight-report/src/main \
                            -type f -newer "${jars[0]}" -print -quit 2>/dev/null)"
            if [[ -n "$newest_src" ]]; then
                warnings+=("The deployed JAR predates your source changes (e.g. $newest_src). A real JMeter run would use the PREVIOUS build. Run ./scripts/build-and-deploy.sh.")
            fi
        fi
    fi
fi

# The bump check. Against the merge-base with the primary branch, so it asks
# "does this BRANCH change the plugin without bumping" rather than reacting to
# every uncommitted keystroke.
primary="$(git symbolic-ref --quiet --short refs/remotes/origin/HEAD 2>/dev/null)"
primary="${primary#origin/}"
[[ -z "$primary" ]] && primary="main"

if git rev-parse --verify --quiet "$primary" >/dev/null 2>&1; then
    base="$(git merge-base HEAD "$primary" 2>/dev/null)"
    if [[ -n "$base" ]]; then
        src_changed="$(git diff --name-only "$base" -- \
            jmeter-report-core/src/main jmeter-web-insight-report/src/main 2>/dev/null | head -1)"
        pom_changed="$(git diff "$base" -- pom.xml 2>/dev/null | grep -c '^[+-].*<revision>')"
        if [[ -n "$src_changed" && "$pom_changed" -eq 0 ]]; then
            warnings+=("This branch changes src/main but <revision> is still $revision. Bump it so the JAR in lib/ext is identifiably yours.")
        fi
    fi
fi

[[ ${#warnings[@]} -eq 0 ]] && exit 0

# --hook emits the JSON a Stop hook needs for the text to reach the user at all;
# plain stdout from a Stop hook is not displayed. Standalone runs get plain text.
if [[ "${1:-}" == "--hook" ]]; then
    msg="deploy freshness:"
    for w in "${warnings[@]}"; do
        msg="$msg"$'\n'"  - $w"
    done
    # Python, not jq: jq is not present in Git Bash on Windows, and the first
    # version of this used it — so the hook emitted nothing at all, silently.
    # That is precisely the failure this whole script exists to catch, and it was
    # sitting inside the script. Python is already a hard dependency of the
    # runners (require_python in common.sh), so it is the safe choice.
    #
    # Encoded by a JSON library rather than by hand: these strings carry
    # filenames and quotes, and a malformed hook payload is discarded without a
    # word.
    # Each candidate is PROVEN to run Python 3 before it is used. `command -v
    # python3` succeeds on Windows for the Microsoft Store stub, which is on PATH,
    # runs nothing, prints an advert to stderr and exits 0 — so a plain
    # existence check picked it, the hook produced no JSON, and the warning
    # vanished. common.sh's detect_python documents the same trap; this is that
    # test, inlined, because a Stop hook should not source a 700-line library.
    for py in python3 python py; do
        command -v "$py" >/dev/null 2>&1 || continue
        "$py" -c 'import sys; sys.exit(0 if sys.version_info[0] == 3 else 1)' >/dev/null 2>&1 || continue
        MSG="$msg" "$py" -c 'import json,os,sys; sys.stdout.write(json.dumps({"systemMessage": os.environ["MSG"], "suppressOutput": True}))'
        exit 0
    done
    # No Python: say it in plain text rather than vanish.
    echo "deploy freshness: $msg"
    exit 0
fi

echo ""
echo "  [deploy freshness]"
for w in "${warnings[@]}"; do
    echo "    - $w"
done
echo ""
exit 0
