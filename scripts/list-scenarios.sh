#!/usr/bin/env bash
# =============================================================================
#  What scenarios does this project actually run?
#
#  Read from the runners themselves, every time. There is no stored list.
#
#  There used to be one — a spreadsheet of 127 rows kept outside version control,
#  with Expected and Verdict columns filled in by hand. It drifted, silently and
#  badly: 43 rows claimed scenarios no runner executed, and 24 scenarios the
#  runners execute appeared nowhere in it. That is the same failure the fixture
#  lists have a verifier for, and the same reason this repo refuses to write test
#  counts into its documentation — a hand-maintained copy of something executable
#  is wrong within a month and looks authoritative the whole time.
#
#  So this generates rather than stores. It cannot drift, because there is
#  nothing for it to drift from.
#
#  The scenarios that were designed and never automated are the one thing that
#  could not be derived this way; they live in
#  docs/TEST-COVERAGE-GAPS.md § "Specified but never automated".
#
#  Usage:
#    ./scripts/list-scenarios.sh            # grouped by runner, for reading
#    ./scripts/list-scenarios.sh --csv      # ID,Runner,Description, for a spreadsheet
#    ./scripts/list-scenarios.sh --count    # just the number
# =============================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNNERS="$ROOT/test-plans/scripts"

MODE="${1:---text}"

# One row per `record <ID> "<name>" ...` call. The ID and the human name are both
# arguments to record(), so the runner is already carrying everything this needs —
# which is the whole argument for reading them instead of keeping a second list.
emit_rows() {
    local file base
    for file in "$RUNNERS"/run-*.sh; do
        [[ -f "$file" ]] || continue
        base="$(basename "$file")"
        # record E24 "Minimal Data" PASS   |   record "C19" "Output dir is a file" ...
        grep -oE '^[[:space:]]*record[[:space:]]+"?[A-Z][0-9]{2}[a-z]?"?[[:space:]]+"[^"]*"' "$file" \
            | sed -E 's/^[[:space:]]*record[[:space:]]+"?([A-Z][0-9]{2}[a-z]?)"?[[:space:]]+"(.*)"$/\1\t\2/' \
            | while IFS=$'\t' read -r id name; do
                  printf '%s\t%s\t%s\n' "$id" "$base" "$name"
              done
    done
}

# Sorted and de-duplicated: a scenario recorded twice in one runner (a second
# assertion on the same ID) is one scenario, not two.
ROWS="$(emit_rows | sort -u -t$'\t' -k1,1)"

case "$MODE" in
    --count)
        printf '%s\n' "$ROWS" | grep -c . || true
        ;;
    --csv)
        echo 'ID,Runner,Description'
        printf '%s\n' "$ROWS" | while IFS=$'\t' read -r id runner name; do
            [[ -z "$id" ]] && continue
            printf '"%s","%s","%s"\n' "$id" "$runner" "${name//\"/\"\"}"
        done
        ;;
    *)
        printf '%s\n' "$ROWS" | awk -F'\t' '
            $2 != last { if (last != "") print ""; print "  " $2; last = $2 }
            { printf "    %-6s %s\n", $1, $3 }
        '
        echo ""
        echo "  $(printf '%s\n' "$ROWS" | grep -c .) scenarios across $(printf '%s\n' "$ROWS" | awk -F'\t' '{print $2}' | sort -u | grep -c .) runners"
        echo ""
        echo "  Designed but not automated: see docs/TEST-COVERAGE-GAPS.md § 13."
        ;;
esac
