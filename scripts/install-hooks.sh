#!/usr/bin/env bash
# =============================================================================
#  Install the repository's git hooks.
#
#  Points core.hooksPath at .githooks/ rather than copying files into
#  .git/hooks/. Copies go stale the moment the hook changes and nothing tells
#  you; a pointer means every clone runs the version that is committed.
#
#  Undo with: git config --unset core.hooksPath
# =============================================================================
set -uo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_DIR" || exit 1

if ! git rev-parse --git-dir >/dev/null 2>&1; then
    echo "ERROR: not a git repository: $PROJECT_DIR" >&2
    exit 1
fi

git config core.hooksPath .githooks

# The pre-push hook compares each pushed ref against the repository's primary
# branch, which it reads from this local pointer. Clones do not always have it —
# and without it the hook has to ask the remote over the network on every push.
# Non-fatal: offline, or with no remote, the hook still works and says which
# fallback it took.
if git remote get-url origin >/dev/null 2>&1; then
    if ! git remote set-head origin -a >/dev/null 2>&1; then
        echo "  NOTE: could not set origin/HEAD (offline?). The hook still works — it"
        echo "        falls back to asking the remote, then to 'main', and says which."
        echo ""
    fi
fi

# A hook without the executable bit is SILENTLY SKIPPED on Linux and macOS — no
# warning, the push just goes through untested. Windows sets core.filemode=false,
# so the bit is not picked up from the filesystem and has to be written into the
# index explicitly, or a machine that cares receives a hook it will ignore.
#
# update-index only works on files git already tracks, so an untracked hook is
# reported rather than silently left half-installed.
untracked=()
for hook in .githooks/*; do
    [[ -f "$hook" ]] || continue
    chmod +x "$hook" 2>/dev/null || true
    if git ls-files --error-unmatch "$hook" >/dev/null 2>&1; then
        git update-index --chmod=+x "$hook" 2>/dev/null || true
    else
        untracked+=("$hook")
    fi
done

echo "Hooks installed — core.hooksPath = .githooks"
echo ""

if [[ ${#untracked[@]} -gt 0 ]]; then
    echo "  NOTE: not yet tracked by git:"
    printf '        %s\n' "${untracked[@]}"
    echo "        They work here now, but the executable bit cannot be written to the"
    echo "        index until they are committed — and a hook without it is silently"
    echo "        skipped on Linux/macOS. Re-run this script after committing them."
    echo ""
fi

echo "  pre-commit refuses a commit ON the primary branch. Catches the mistake while"
echo "             the work is still only staged, so the fix is one 'git switch -c'"
echo "             rather than moving commits off a branch they should not be on."
echo ""
echo "  pre-push   refuses a direct push TO the primary branch. Neither hook runs"
echo "             tests — the full regression runs BEFORE a push, via the run-tests"
echo "             skill or ./test-plans/scripts/run-regression.sh."
echo ""
echo "  Bypass:    WIR_ALLOW_MAIN_COMMIT=1 git commit  (permit a primary-branch commit)"
echo "             WIR_ALLOW_MAIN_PUSH=1   git push    (permit a direct primary push)"
echo "             git commit/push --no-verify         (skip the hook entirely)"
echo ""
echo "  Remove:    git config --unset core.hooksPath"
