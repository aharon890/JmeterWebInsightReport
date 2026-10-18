#!/usr/bin/env bash
# =============================================================================
#  Web Insight Report — Docker Test Runner
#  Builds the JMeter image and runs the plugin inside a container (E16, E17).
# =============================================================================
set -uo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../../scripts/lib/common.sh"

# Keep the JMeter version in one place: it names the image tag AND the in-container
# install path. Overriding IMAGE_NAME alone used to leave CONTAINER_EXT pointing at
# 5.6.3, so the plugin was mounted somewhere the image never scans and every test
# failed with "report not generated".
JMETER_VERSION="${JMETER_VERSION:-5.6.3}"
IMAGE_NAME="${IMAGE_NAME:-jmeter-webinsight-test:$JMETER_VERSION}"
DOCKER_RESULTS="$RESULTS_DIR/docker"
# Path the plugin is mounted to inside the container.
CONTAINER_EXT="/opt/apache-jmeter-$JMETER_VERSION/lib/ext"

# Docker wants HOST paths in native form and CONTAINER paths in POSIX form. On Git Bash
# (MSYS) any argument that looks like a POSIX path is rewritten before it reaches docker,
# so "-v /d/x:/opt/y" arrives as "-v D:\x;C:\Program Files\Git\opt\y" and the mount is
# silently wrong. Disable that rewriting and convert the host side ourselves.
#
# The suppression is applied per docker invocation via `docker_cmd`, NOT exported for the
# whole script: MSYS2_ARG_CONV_EXCL='*' turns off argument conversion for every command,
# and Maven's Git Bash wrapper needs it — with the variable exported, `mvn` dies with
# "Could not find or load main class org.codehaus.plexus.classworlds.launcher.Launcher"
# and build_plugin fails before a single container starts.
#
# DOCKER_USER exists for a related reason: a bind mount shadows the image's ownership, so on a
# Linux host the container's uid must match the invoking user or /results is not writable and
# the report cannot be written. On Docker Desktop (Windows/macOS) the mount is translated and
# a host uid would be meaningless, so it stays empty there.
DOCKER_USER=()
DOCKER_ENV=()
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*)
        DOCKER_ENV=(MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*')
        host_path() { cygpath -m "$1"; }
        ;;
    Darwin*)
        host_path() { printf '%s\n' "$1"; }
        ;;
    *)
        host_path() { printf '%s\n' "$1"; }
        if command -v id >/dev/null 2>&1; then
            DOCKER_USER=(--user "$(id -u):$(id -g)")
        fi
        ;;
esac

# Run docker with the path-conversion suppression scoped to this one command.
docker_cmd() { env ${DOCKER_ENV[@]+"${DOCKER_ENV[@]}"} docker "$@"; }

print_banner "Docker Test Runner — Web Insight Report"

if ! docker_cmd info >/dev/null 2>&1; then
    echo "ERROR: Docker daemon is not running. Start Docker Desktop and retry." >&2
    exit 1
fi

# --- Build plugin (host-side; the container only runs it) ---
build_plugin || exit 1
require_plugin_jar

# --- Build image ---
echo "[DOCKER] Building JMeter image $IMAGE_NAME ..."
if ! docker_cmd build -t "$IMAGE_NAME" --build-arg "JMETER_VERSION=$JMETER_VERSION" \
        -f "$(host_path "$PROJECT_DIR/docker/Dockerfile.jmeter")" "$(host_path "$PROJECT_DIR/docker")"; then
    echo "[DOCKER] Image build FAILED" >&2
    exit 1
fi

# -----------------------------------------------------------------------------
#  run_docker_test <id> <name> <jmx> <extra args...>
#
#  Only the shaded fat JAR is mounted — it already contains jmeter-report-core,
#  so mounting core separately would risk duplicate classes on the classpath.
# -----------------------------------------------------------------------------
run_docker_test() {
    local id="$1" name="$2" jmx="$3"
    shift 3
    local out_dir="$DOCKER_RESULTS/$id"
    mkdir -p "$out_dir"

    echo ""
    echo "--- $id: $name ---"
    docker_cmd run --rm ${DOCKER_USER[@]+"${DOCKER_USER[@]}"} \
        -v "$(host_path "$PLUGIN_JAR"):$CONTAINER_EXT/$(basename "$PLUGIN_JAR")" \
        -v "$(host_path "$TEST_PLANS/$jmx"):/test/$jmx" \
        -v "$(host_path "$out_dir"):/results" \
        "$IMAGE_NAME" \
        -n -t "/test/$jmx" \
        -l /results/results.jtl \
        -Jwebinsight.report.output=/results \
        -Jwebinsight.report.filename="$id-docker-report.html" \
        -Jwebinsight.report.title="$id: $name" \
        "$@"

    if file_exists "$out_dir/$id-docker-report.html"; then
        record "$id" "$name" PASS "report generated"
    else
        record "$id" "$name" FAIL "report not generated"
    fi
}

run_docker_test E16 "Docker Basic Test" docker-test.jmx \
    -Jthreads=10 -Jduration=30 -Jrampup=5

run_docker_test E17 "Docker Full Load" full-load-test.jmx \
    -Jthreads_browse=20 -Jthreads_shop=15 -Jthreads_api=10 -Jthreads_admin=5 \
    -Jduration=120 -Jrampup=20

print_summary "DOCKER TEST SUMMARY"
echo ""
echo "  Results: $DOCKER_RESULTS"

exit $FAIL
