#!/usr/bin/env bash
# After a JMeter run: fetch the server data for the run's window, then build the report with it.
#
#   hooks/after-run.sh <first-report.json> <results.jtl> <new-output-folder>
#
# <first-report.json> is the .json the run's own report wrote (web-insight-report.json): its
# "window" is the span to export. EXPORT_CMD is your tool's export, called as
#   $EXPORT_CMD <startMillis> <endMillis> server.csv
# (default: this example's stand-in, "node fake-export.js"). JMETER is the jmeter command
# (default: jmeter). Run it from the folder holding run.properties. Exits with jmeter's code, and
# server data never changes that code (unless run.properties sets server.failonerror).
set -euo pipefail

if [[ $# -ne 3 ]]; then
    echo "usage: $0 <first-report.json> <results.jtl> <new-output-folder>" >&2
    exit 1
fi
json=$1 jtl=$2 out=$3
export_cmd=${EXPORT_CMD:-node fake-export.js}
jmeter_cmd=${JMETER:-jmeter}

millis() {   # the number after "<key>" : in the report's JSON (no jq needed)
    sed -n 's/.*"'"$1"'" *: *\([0-9][0-9]*\).*/\1/p' "$json" | head -n 1
}
start=$(millis startMillis)
end=$(millis endMillis)
if [[ -z "$start" || -z "$end" ]]; then
    echo "after-run: $json has no window - is it the report's .json, from version 4.1 or later?" >&2
    exit 1
fi

# A minute either side: rows outside the run are counted and ignored.
$export_cmd $((start - 60000)) $((end + 60000)) server.csv
$jmeter_cmd -g "$jtl" -o "$out" -q run.properties
