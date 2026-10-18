#!/usr/bin/env bash
# =============================================================================
#  Web Insight Report — open the JMeter GUI for a manual smoke test
#
#  Launches the JMeter GUI with the plugin deployed and a test plan loaded, so
#  the listener can be checked under "Add > Listener > Web Insight Report".
#
#  Usage:
#    ./scripts/open-jmeter-gui.sh                     # quick-smoke-test.jmx
#    ./scripts/open-jmeter-gui.sh full-load-test.jmx  # any plan in test-plans/
#    ./scripts/open-jmeter-gui.sh /path/to/plan.jmx   # or an explicit path
#    ./scripts/open-jmeter-gui.sh --no-plan           # empty GUI
#
#  Everything is resolved through scripts/lib/common.sh — no hardcoded paths.
# =============================================================================
set -uo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/common.sh"

require_jmeter

PLAN_ARG="${1:-quick-smoke-test.jmx}"

resolve_plan() {
    local plan="$1"
    if [[ -f "$plan" ]]; then
        printf '%s\n' "$(cd "$(dirname "$plan")" && pwd)/$(basename "$plan")"
    elif [[ -f "$TEST_PLANS/$plan" ]]; then
        printf '%s\n' "$TEST_PLANS/$plan"
    else
        return 1
    fi
}

PLAN_PATH=""
if [[ "$PLAN_ARG" != "--no-plan" ]]; then
    if ! PLAN_PATH="$(resolve_plan "$PLAN_ARG")"; then
        echo "ERROR: test plan not found: $PLAN_ARG" >&2
        echo "       Tried '$PLAN_ARG' and '$TEST_PLANS/$PLAN_ARG'." >&2
        echo "       Available plans:" >&2
        ls "$TEST_PLANS"/*.jmx 2>/dev/null | sed 's|.*/|         |' >&2
        exit 1
    fi
fi

# The whole point of opening the GUI is to exercise the plugin, so make sure this
# JMeter actually has it installed. Launching a GUI without the listener is the
# failure this script used to have — it pointed at a different checkout entirely.
if [[ ! -f "$JMETER_EXT/$(basename "$PLUGIN_JAR")" ]]; then
    echo "[GUI] $(basename "$PLUGIN_JAR") is not in $JMETER_EXT — building and deploying first."
    build_plugin || exit 1
    deploy_plugin || exit 1
fi

echo "JMeter:    $JMETER_HOME"
echo "Plugin:    $PLUGIN_VERSION"
echo "Test plan: ${PLAN_PATH:-<none>}"
echo ""
echo "[GUI] Launching JMeter..."

if [[ -n "$PLAN_PATH" ]]; then
    exec "$JMETER" -t "$PLAN_PATH"
else
    exec "$JMETER"
fi
