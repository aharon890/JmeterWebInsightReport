#!/usr/bin/env bash
# =============================================================================
#  Build all modules and deploy the plugin JAR to the local JMeter install.
# =============================================================================
set -euo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/common.sh"

require_jmeter

echo "Project:  $PROJECT_DIR"
echo "Version:  $PLUGIN_VERSION"
echo "JMeter:   $JMETER_HOME"
echo ""

build_plugin
deploy_plugin

echo ""
echo "Plugin ${PLUGIN_VERSION} deployed to ${JMETER_EXT}"
