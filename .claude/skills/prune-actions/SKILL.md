---
name: prune-actions
description: Delete this repository's old GitHub Actions workflow runs, keeping only the newest 10, and optionally clear its Actions caches. Use when Actions history has piled up, when a GitHub storage or quota warning appears, or when asked to clean up CI runs, logs, or caches.
---

# Pruning Actions history

```
./.claude/skills/prune-actions/prune-actions.sh                   dry run — show what would go
./.claude/skills/prune-actions/prune-actions.sh --yes             delete all but the newest 10 runs
./.claude/skills/prune-actions/prune-actions.sh --yes --caches    also drop every Actions cache
./.claude/skills/prune-actions/prune-actions.sh --keep 25 --yes   keep 25 instead of 10
./.claude/skills/prune-actions/prune-actions.sh --repo owner/name target a repo with no local clone
```

Needs Git Bash on Windows, and `gh` authenticated with the `repo` scope.

**Dry run is the default and that is deliberate.** Deleting a run destroys its logs, its
artifacts and the status badge on the commit it ran against, and nothing brings them back.
Read the summary line first, then pass `--yes`.

The repo is derived from the script's own checkout via `origin`, not from the current
directory, so it cannot be pointed at the wrong repository by accident.

## Read the summary before saying yes

```
runs: 143 total | 10 kept | 133 to delete | 0 skipped (not completed)
```

Runs that are not `completed` are never touched — the API refuses to delete them, and an
in-progress run is usually one someone is still watching.

## What this does and does not free

**It does not free billed storage.** GitHub bills Actions storage on *artifacts*, not on run
logs, so pruning runs is history hygiene, not a quota fix. It only reclaims storage when the
runs being deleted still hold unexpired artifacts.

If a storage warning is the reason you are here, find the real consumer first — the monthly
rollup misattributes the repository name, so use the daily view:

```bash
gh api "users/OWNER/settings/billing/usage?year=YYYY&month=M" \
  --jq '.usageItems[] | select(.sku|test("storage")) | "\(.repositoryName)\t\(.quantity)"' \
  | awk -F'\t' '{q[$1]+=$2} END{for(i in q) printf "%-38s %10.2f GB-h\n", i, q[i]}' | sort -k2 -rn
```

Needs the `user` scope: `gh auth refresh -h github.com -s user`.

**Actions minutes are a separate quota** and deleting runs does not refund them.

**Caches are free** under a 10 GB per-repo limit and evict themselves after 7 days unused, so
`--caches` is for reclaiming space now, not for fixing a bill.

## No counts are written down here, deliberately

How many runs this repo has is what the script prints. Any number pinned in this file would be
wrong within a week.

## When to reach for something else

This skill is on-demand and needs a machine with a clone. For *recurring* cleanup, a scheduled
workflow in `.github/workflows/` is strictly better: it runs server-side on cron, needs neither
this laptop nor Claude, and works for repos that were never cloned. Prefer it if you find
yourself running this by hand more than occasionally.

Lowering the retention window beats deleting after the fact — Settings → Actions → General →
artifact and log retention, default 90 days, and `retention-days:` on any `upload-artifact` step.
