#!/usr/bin/env bash
# Records fixture K2: petstore-checkout SMOKE with k6, as perf hub runs it (perf-hub-k6:2.4.0) plus the two settings perf
# hub gives every k6 run (perf hub spec 010, FR-002). A real run against the petstore_ms test bed, so only on the
# owner's yes, and at the runner's own smallest load: its K6_PROPERTIES block (SMOKE) is passed to the test as is.
#
#   PETSTORE_DIR=<petstore_ms checkout> test-plans/run-data/K2/record/record.sh [output folder]
#
# With no folder it writes the fixture itself (K2/). Give a folder to run the same smoke without touching it.
#
# The gateway is the test bed's dev port on this PC (9080); the container reaches it as host.docker.internal.
# Afterwards: python -I test-plans/run-data/tools/k6_to_jtl.py K2/results.csv.gz K2/results.jtl K2/in-test-plan.jtl
set -euo pipefail
: "${PETSTORE_DIR:?set PETSTORE_DIR to the petstore_ms checkout}"
runner="$PETSTORE_DIR/test/k6Scripts/DEV/Storefront/run_petstore-checkout_SMOKE_DEV.sh"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
out="${1:-$here/..}"
mkdir -p "$out"
out="$(cd "$out" && pwd)"
native() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }

# The runner's block, KEY=VALUE per line between K6_PROPERTIES: and K6_PROPERTIES_END, each passed as -e. The
# dashboard line is perf hub's and not the test's.
envs=()
inside=0
while IFS= read -r line; do
    case "$line" in
        "# K6_PROPERTIES:"*) inside=1; continue ;;
        "# K6_PROPERTIES_END"*) inside=0; continue ;;
    esac
    if [ "$inside" = 1 ]; then
        kv="${line#\# }"
        case "$kv" in K6_WEB_DASHBOARD=*|"") continue ;; esac
        envs+=(-e "${kv%%|*}")
    fi
done < "$runner"
[ ${#envs[@]} -gt 0 ] || { echo "no K6_PROPERTIES block in $runner" >&2; exit 1; }

rm -f "$out/results.csv.gz" "$out/summary.json" "$out/summary.txt"
MSYS_NO_PATHCONV=1 docker run --rm --name "k6-K2-$$" --add-host host.docker.internal:host-gateway \
    -v "$(native "$PETSTORE_DIR/test"):/test:ro" -v "$(native "$out"):/results" -w /results \
    -e K6_CSV_TIME_FORMAT=unix_milli \
    -e K6_SYSTEM_TAGS=proto,subproto,status,method,url,name,group,check,error,error_code,tls_version,scenario,service,expected_response,vu,iter \
    --entrypoint k6 perf-hub-k6:2.4.0 run --out csv=/results/results.csv.gz \
    -e protocol=http -e host=host.docker.internal -e port=9080 -e RESULTS_DIR=/results \
    "${envs[@]}" /test/k6/plans/petstore-checkout.js
ls -la "$out"
