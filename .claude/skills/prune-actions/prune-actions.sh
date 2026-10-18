#!/usr/bin/env bash
# Prune GitHub Actions workflow runs for this repository, keeping the newest N.
# Dry-run unless --yes is passed. Deletion is irreversible.
set -uo pipefail

KEEP=10
APPLY=0
CACHES=0
REPO=""

usage() {
  cat <<'USAGE'
prune-actions.sh — keep the newest N workflow runs, delete the rest.

  ./prune-actions.sh                    dry run: show what would be deleted
  ./prune-actions.sh --yes              delete runs older than the newest 10
  ./prune-actions.sh --yes --caches     also delete every Actions cache
  ./prune-actions.sh --keep 25 --yes    keep 25 instead of 10
  ./prune-actions.sh --repo owner/name  target another repo (no clone needed)

Runs that are not "completed" are never deleted; the API refuses, and an
in-progress run is one you are probably still watching.
USAGE
}

while [ $# -gt 0 ]; do
  case "$1" in
    --keep)    KEEP="${2:-}"; shift 2 ;;
    --repo)    REPO="${2:-}"; shift 2 ;;
    --yes|-y)  APPLY=1; shift ;;
    --caches)  CACHES=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

case "$KEEP" in
  ''|*[!0-9]*) echo "--keep needs a non-negative integer, got '$KEEP'" >&2; exit 2 ;;
esac

command -v gh >/dev/null 2>&1 || { echo "gh CLI not found on PATH" >&2; exit 1; }
gh auth status >/dev/null 2>&1 || { echo "gh is not authenticated — run: gh auth login" >&2; exit 1; }

# Resolve the repo from this script's own git checkout, so it works no matter
# what the current directory is.
if [ -z "$REPO" ]; then
  here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
  root=$(git -C "$here" rev-parse --show-toplevel 2>/dev/null)
  [ -n "$root" ] || { echo "not inside a git repo and no --repo given" >&2; exit 1; }
  url=$(git -C "$root" remote get-url origin 2>/dev/null)
  [ -n "$url" ] || { echo "no 'origin' remote and no --repo given" >&2; exit 1; }
  case "$url" in
    *github.com*) REPO=$(printf '%s' "$url" | sed -E 's#^.*github\.com[:/]+##; s#\.git$##; s#/+$##') ;;
    *) echo "origin is not a github.com remote: $url" >&2; exit 1 ;;
  esac
fi

case "$REPO" in
  */*/*|*/) echo "bad repo '$REPO' — expected owner/name" >&2; exit 2 ;;
  */*) : ;;
  *) echo "bad repo '$REPO' — expected owner/name" >&2; exit 2 ;;
esac

# gh occasionally drops a connection on this network; one retry costs nothing.
api() {
  local out rc
  out=$(gh api "$@" 2>&1); rc=$?
  if [ $rc -ne 0 ] && printf '%s' "$out" | grep -qi 'dial tcp\|timeout\|connection'; then
    sleep 2
    out=$(gh api "$@" 2>&1); rc=$?
  fi
  printf '%s' "$out"
  return $rc
}

tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT

echo "repo:  $REPO"
echo "keep:  $KEEP newest runs"
echo "mode:  $([ $APPLY -eq 1 ] && echo 'APPLY — deletions are permanent' || echo 'dry run')"
echo

api --paginate "repos/$REPO/actions/runs?per_page=100" \
    --jq '.workflow_runs[] | [.id, .created_at, .status, .conclusion // "-", .name] | @tsv' \
    > "$tmp/all" || { echo "failed to list runs:"; cat "$tmp/all"; exit 1; }

total=$(awk "END{print NR}" "$tmp/all")
if [ "$total" -eq 0 ]; then
  echo "no workflow runs — nothing to prune"
else
  sort -t"$(printf '\t')" -k2,2r "$tmp/all" > "$tmp/sorted"
  head -n "$KEEP" "$tmp/sorted" > "$tmp/keep"
  tail -n +$((KEEP + 1)) "$tmp/sorted" > "$tmp/cand"

  awk -F'\t' '$3=="completed"' "$tmp/cand" > "$tmp/del"
  awk -F'\t' '$3!="completed"' "$tmp/cand" > "$tmp/skip"

  ndel=$(awk "END{print NR}" "$tmp/del")
  nskip=$(awk "END{print NR}" "$tmp/skip")
  nkeep=$(awk "END{print NR}" "$tmp/keep")

  echo "runs: $total total | $nkeep kept | $ndel to delete | $nskip skipped (not completed)"

  if [ "$ndel" -gt 0 ]; then
    echo
    echo "oldest to delete:"
    tail -n 3 "$tmp/del" | awk -F'\t' '{printf "  %s  %s  %s\n", $2, $4, $5}'
    echo "newest to delete:"
    head -n 3 "$tmp/del" | awk -F'\t' '{printf "  %s  %s  %s\n", $2, $4, $5}'
    echo

    if [ $APPLY -eq 1 ]; then
      ok=0; fail=0; n=0
      while IFS=$'\t' read -r id created status conclusion name || [ -n "$id" ]; do
        [ -n "$id" ] || continue
        n=$((n + 1))
        if api -X DELETE "repos/$REPO/actions/runs/$id" >/dev/null 2>&1; then
          ok=$((ok + 1))
        else
          fail=$((fail + 1))
          echo "  ! failed to delete run $id ($created)" >&2
        fi
        [ $((n % 20)) -eq 0 ] && echo "  ... $n/$ndel"
      done < "$tmp/del"
      echo "deleted $ok run(s); $fail failure(s)"
    else
      echo "dry run — re-run with --yes to delete these $ndel run(s)"
    fi
  fi
fi

if [ $CACHES -eq 1 ]; then
  echo
  api "repos/$REPO/actions/caches?per_page=100" \
      --jq '.actions_caches[] | [.id, .size_in_bytes, .key] | @tsv' > "$tmp/caches" 2>/dev/null
  ncache=$(awk "END{print NR}" "$tmp/caches")
  if [ "$ncache" -eq 0 ]; then
    echo "caches: none"
  else
    mb=$(awk -F'\t' '{s+=$2} END{printf "%d", s/1048576}' "$tmp/caches")
    echo "caches: $ncache entries, ${mb} MB"
    if [ $APPLY -eq 1 ]; then
      cok=0; cfail=0; freed=0
      while IFS=$'\t' read -r id size key || [ -n "$id" ]; do
        [ -n "$id" ] || continue
        if api -X DELETE "repos/$REPO/actions/caches/$id" >/dev/null 2>&1; then
          cok=$((cok + 1)); freed=$((freed + size))
        else
          cfail=$((cfail + 1))
          echo "  ! failed to delete cache $id ($key)" >&2
        fi
      done < "$tmp/caches"
      echo "deleted $cok of $ncache cache(s), freed $((freed / 1048576)) MB; $cfail failure(s)"
    else
      echo "dry run — re-run with --yes --caches to delete them"
    fi
  fi
fi
