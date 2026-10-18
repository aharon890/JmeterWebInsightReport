#!/usr/bin/env bash
# =============================================================================
#  Web Insight Report — shared shell library
#
#  Sourced by every build/test script. Detects the environment rather than
#  hardcoding it, so the same code runs on macOS, Linux, WSL2 and Git Bash.
#
#  Override via environment:
#    JMETER_HOME      explicit JMeter install (else project-local, then PATH)
#    JAVA_HOME        passed through to JMeter when set
#    PLUGIN_VERSION   explicit version (else parsed from the parent pom.xml)
#    RESULTS_DIR      test output root (default <project>/test-results)
# =============================================================================

# ---- project layout ---------------------------------------------------------
# This file lives at <project>/scripts/lib/common.sh — everything else is
# relative to that, so no script ever needs an absolute path.
_common_lib_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$_common_lib_dir/../.." && pwd)"
TEST_PLANS="$PROJECT_DIR/test-plans"
ANNOTATIONS_DIR="$TEST_PLANS/annotations"
RESULTS_DIR="${RESULTS_DIR:-$PROJECT_DIR/test-results}"

# ---- platform shims ---------------------------------------------------------
# BSD (macOS) and GNU (Linux) disagree on stat and md5. Resolve once, up front.
# The previous code used `stat -c%s ... || echo 0`, which on macOS silently
# reported every file as 0 bytes and turned size assertions into false FAILs.
if stat -f%z /dev/null >/dev/null 2>&1; then
    stat_size() { [[ -f "$1" ]] || { echo 0; return 0; }; stat -f%z "$1"; }
else
    stat_size() { [[ -f "$1" ]] || { echo 0; return 0; }; stat -c%s "$1"; }
fi

if command -v md5sum >/dev/null 2>&1; then
    file_md5() { [[ -f "$1" ]] || { echo "none"; return 0; }; md5sum "$1" | cut -d' ' -f1; }
    # Paths arrive on stdin, one per line; emit "<path>\t<md5>". ONE process for the whole
    # list, which is the entire point: the fixture stamp covers 39 source files and is
    # compared against 10 fixtures, so a per-file shell-out is 390 process spawns — about
    # 23s under Git Bash, on a check that runs at the top of every suite. Substring
    # extraction rather than field splitting because md5sum writes "<32 hex><2 spaces><path>"
    # and a path may legitimately contain spaces.
    files_md5() { tr '\n' '\0' | xargs -0 md5sum 2>/dev/null \
        | awk '{h = substr($0, 1, 32); p = substr($0, 35); print p "\t" h}'; }
elif command -v md5 >/dev/null 2>&1; then
    file_md5() { [[ -f "$1" ]] || { echo "none"; return 0; }; md5 -q "$1"; }
    # BSD md5 has no --tag-free multi-file mode; it prints "MD5 (<path>) = <hash>". The tab is a
    # real one: `\t` in a sed replacement is a GNU extension, and BSD sed, the only sed this branch
    # meets, writes a literal "t" for it, which left the listing without a path/md5 separator.
    files_md5() { local tab=$'\t'; tr '\n' '\0' | xargs -0 md5 2>/dev/null \
        | sed -n "s/^MD5 (\(.*\)) = \([0-9a-f]*\)\$/\1${tab}\2/p"; }
else
    file_md5() { echo "no-md5-tool"; }
    files_md5() { cat >/dev/null; }
fi

# SHA-256 as 64 lowercase hex on stdout, for stdin. Where a suite has to compute the same name
# the plugin does (RunIdentity.digest: SHA-256, first 16 hex — C35's recording filename), and
# macOS ships `shasum -a 256`, not `sha256sum`. Bare sha256sum in a stage left the digest empty
# there and the recording named `.json`, so the file source read nothing. That failed the stage as
# "wir-collect exited 2" until D27 (an EMPTY series now exits 0); now C35's "wir-collect: OK app/up"
# check fails it.
if command -v sha256sum >/dev/null 2>&1; then
    sha256_hex() { sha256sum | cut -c1-64; }
elif command -v shasum >/dev/null 2>&1; then
    sha256_hex() { shasum -a 256 | cut -c1-64; }
else
    sha256_hex() { echo "no-sha256-tool" >&2; return 1; }
fi

# Python 3, by whichever name it answers to.
#
# `python3` cannot be trusted to exist OR to work: on Windows it is usually the
# Microsoft Store stub, which is on PATH, exits non-zero, and prints an advert about
# installing Python instead of running anything. `command -v python3` finds it happily.
# So probe by executing each candidate and asking it what version it is — the stub
# fails that, a real interpreter passes.
detect_python() {
    local p
    for p in python3 python py; do
        command -v "$p" >/dev/null 2>&1 || continue
        "$p" -c 'import sys; sys.exit(0 if sys.version_info[0] == 3 else 1)' >/dev/null 2>&1 \
            && { printf '%s\n' "$p"; return 0; }
    done
    return 1
}

require_python() {
    if ! PYTHON="$(detect_python)"; then
        echo "ERROR: no working Python 3 found (tried python3, python, py)." >&2
        echo "       On Windows, 'python3' is often the Microsoft Store stub, which is" >&2
        echo "       on PATH but does not run anything. Install Python 3 or fix PATH." >&2
        return 1
    fi
    export PYTHON
}

# macOS has no `readlink -f`; walk the symlink chain by hand.
resolve_link() {
    local p="$1" target
    while [[ -L "$p" ]]; do
        target="$(readlink "$p")"
        if [[ "$target" == /* ]]; then
            p="$target"
        else
            p="$(cd "$(dirname "$p")" && cd "$(dirname "$target")" && pwd)/$(basename "$target")"
        fi
    done
    printf '%s\n' "$p"
}

iso_timestamp() { date +%Y-%m-%dT%H:%M:%S; }

# Count files matching a glob without tripping over "no matches".
#
# Callers pass the glob UNQUOTED so the shell expands it and hands us the matches as
# separate arguments:  count_glob "$dir"/report_*.html
# The previous version took one quoted string and re-split it on whitespace, so every
# match under a path containing a space became several non-existent words and the count
# came back 0 — a false FAIL on any checkout whose path has a space in it.
count_glob() {
    local n=0 f
    for f in "$@"; do [[ -e "$f" ]] && n=$((n + 1)); done
    echo "$n"
}

# ---- version ----------------------------------------------------------------
# Read from the parent pom rather than hardcoding. Hardcoded versions are how
# the old scripts ended up pinned to a 1.0.4 JAR that no longer exists.
#
# The pom uses Maven's CI-friendly versioning, so <version> is the literal string
# "${revision}" and the number lives in the <revision> property. Read that property:
# scraping <version> now yields a placeholder, and the regex below would reject it —
# loudly, which is the point. An empty or unresolved parse used to sail straight into
# PLUGIN_JAR as "...-report-.jar" and fail much later with a confusing message.
detect_plugin_version() {
    local version
    version="$(sed -n 's:.*<revision>\([^<]*\)</revision>.*:\1:p' "$PROJECT_DIR/pom.xml" | head -1)"
    if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.]+)?$ ]]; then
        echo "ERROR: could not parse <revision> from $PROJECT_DIR/pom.xml (got '${version}')." >&2
        echo "       That property is the single source of the suite version." >&2
        echo "       Set PLUGIN_VERSION explicitly to work around it." >&2
        return 1
    fi
    printf '%s\n' "$version"
}

if [[ -z "${PLUGIN_VERSION:-}" ]]; then
    PLUGIN_VERSION="$(detect_plugin_version)" || exit 1
fi

PLUGIN_JAR="$PROJECT_DIR/jmeter-web-insight-report/target/jmeter-web-insight-report-${PLUGIN_VERSION}.jar"

# ---- JMeter discovery -------------------------------------------------------
# A valid JMETER_HOME has both bin/jmeter and lib/ext. Homebrew's bin/jmeter is
# a wrapper that execs libexec/bin/jmeter, so probe <dir>/libexec too.
_is_jmeter_home() { [[ -x "$1/bin/jmeter" && -d "$1/lib/ext" ]]; }

_normalize_jmeter_home() {
    local candidate="$1"
    if _is_jmeter_home "$candidate"; then printf '%s\n' "$candidate"; return 0; fi
    if _is_jmeter_home "$candidate/libexec"; then printf '%s\n' "$candidate/libexec"; return 0; fi
    return 1
}

detect_jmeter_home() {
    local candidate resolved

    # 1. Explicit override.
    if [[ -n "${JMETER_HOME:-}" ]]; then
        if candidate="$(_normalize_jmeter_home "$JMETER_HOME")"; then
            printf '%s\n' "$candidate"; return 0
        fi
        echo "ERROR: JMETER_HOME is set to '$JMETER_HOME' but that isn't a JMeter install." >&2
        return 1
    fi

    # 2. Project-local install (the layout on the Windows box).
    for candidate in "$PROJECT_DIR"/apache-jmeter-*; do
        if resolved="$(_normalize_jmeter_home "$candidate")"; then
            printf '%s\n' "$resolved"; return 0
        fi
    done

    # 3. On PATH (Homebrew, apt, manual install).
    if command -v jmeter >/dev/null 2>&1; then
        resolved="$(resolve_link "$(command -v jmeter)")"
        candidate="$(cd "$(dirname "$resolved")/.." && pwd)"
        if resolved="$(_normalize_jmeter_home "$candidate")"; then
            printf '%s\n' "$resolved"; return 0
        fi
    fi

    return 1
}

require_jmeter() {
    if ! JMETER_HOME="$(detect_jmeter_home)"; then
        cat >&2 <<'EOF'
ERROR: No JMeter installation found.

Tried, in order:
  1. $JMETER_HOME
  2. <project>/apache-jmeter-*
  3. `jmeter` on PATH

Install one (macOS: `brew install jmeter`) or set JMETER_HOME explicitly.
EOF
        exit 1
    fi
    export JMETER_HOME
    JMETER="$JMETER_HOME/bin/jmeter"
    JMETER_EXT="$JMETER_HOME/lib/ext"
}

require_plugin_jar() {
    if [[ ! -f "$PLUGIN_JAR" ]]; then
        echo "ERROR: Plugin JAR not found: $PLUGIN_JAR" >&2
        echo "Run ./scripts/build-and-deploy.sh first." >&2
        exit 1
    fi
}

# A built JAR is not a deployed one. Every JMX names the listener class, so an
# undeployed plugin makes JMeter fail to parse the test plan at all:
#
#   CannotResolveClassException: ...report.listener.WebInsightReportListener
#
# which surfaces as every scenario reporting "HTML report not generated" — nine
# identical failures that say nothing about the actual cause. Callers that skip
# the build (SKIP_BUILD=1) must assert the deploy separately, so the real problem
# is named once, up front.
require_deployed_plugin() {
    require_plugin_jar
    local installed="$JMETER_EXT/$(basename "$PLUGIN_JAR")"
    if [[ ! -f "$installed" ]]; then
        echo "ERROR: plugin $PLUGIN_VERSION is built but not deployed." >&2
        echo "       Expected: $installed" >&2
        echo "       Without it JMeter cannot load any test plan that uses the" >&2
        echo "       listener, and every scenario fails with an empty report." >&2
        echo "       Run ./scripts/build-and-deploy.sh, or drop SKIP_BUILD." >&2
        exit 1
    fi
    # Deployed, and alone. SKIP_BUILD skips deploy_plugin and so its purge: a stale JAR left
    # beside this one keeps both on JMeter's flat classpath, and everything run now is rendered
    # by whichever loads first. The fixture stamps would record that as "none" and refuse every
    # fixture afterwards, but only after minutes of regeneration. Refuse before the first one.
    _scan_plugin_jars
    if [[ $_PLUGIN_JAR_N -gt 1 ]]; then
        echo "ERROR: lib/ext holds $_PLUGIN_JAR_N plugin JARs ($_PLUGIN_JAR_NAMES)." >&2
        echo "       It is one flat classpath, so which one JMeter loads is non-deterministic," >&2
        echo "       and anything run now could be rendered by an old build." >&2
        echo "       Run ./scripts/build-and-deploy.sh, which removes the stale ones or names the" >&2
        echo "       one it cannot, or drop SKIP_BUILD." >&2
        exit 1
    fi
}

# ---- build & deploy ---------------------------------------------------------
build_plugin() {
    echo "[BUILD] Building plugin ${PLUGIN_VERSION}..."
    if ! (cd "$PROJECT_DIR" && mvn clean package -DskipTests -q); then
        echo "[BUILD] FAILED" >&2
        return 1
    fi
    verify_shaded_jar || return 1
    echo "[BUILD] SUCCESS"
}

# The fat JAR lands in JMeter's shared lib/ext, whose classloader is flat, so every
# bundled third-party library must be relocated under com.jmeterwebinsightreport.shaded.
# A new dependency added without a matching <relocation> would otherwise ship at its
# original coordinates and collide with another plugin's copy at runtime — a failure
# that only reproduces on a user's machine with a particular plugin mix.
#
# This is an ALLOWLIST, not a list of known offenders. Blocking only the six libraries we
# happen to bundle today would wave through the next dependency someone adds — precisely the
# case the guard exists to catch. Anything whose top-level path is not ours fails the build.
ALLOWED_JAR_ROOTS='^(com/jmeterwebinsightreport/|static/|templates/|META-INF/)'

verify_shaded_jar() {
    [[ -f "$PLUGIN_JAR" ]] || { echo "[BUILD] Shaded JAR missing: $PLUGIN_JAR" >&2; return 1; }
    command -v jar >/dev/null 2>&1 || return 0   # no JDK tools on PATH — skip, not fail

    # Directory entries, root-level files (LICENSE, .properties) and our own tree are fine;
    # report anything else as the package it lives in.
    # `|| true` because a CLEAN jar is the case where every line gets filtered out, and a
    # grep that matches nothing exits 1. Under `set -o pipefail` that failure propagates,
    # and under `set -e` it aborts the caller — so without this the guard blows up exactly
    # when the JAR is correct. The project's own runners use `set -uo pipefail` (no -e) and
    # happened to survive it; CI does not, and a library function should not depend on which
    # shell options its caller chose.
    local stray
    stray="$(jar tf "$PLUGIN_JAR" \
        | grep -vE '/$' \
        | grep -vE '^[^/]+$' \
        | grep -vE "$ALLOWED_JAR_ROOTS" \
        | sed 's:/[^/]*$::' | sort -u | head -10 || true)"
    if [[ -n "$stray" ]]; then
        echo "[BUILD] FAILED: the fat JAR ships classes outside com.jmeterwebinsightreport:" >&2
        printf '         %s\n' $stray >&2
        echo "         Add a <relocation> for them in jmeter-web-insight-report/pom.xml so" >&2
        echo "         they cannot collide with another lib/ext plugin's copy." >&2
        return 1
    fi
}

# A deploy only replaces the JAR of the same version. Every other build of the
# plugin still sitting in lib/ext stays on JMeter's classpath, so which copy of
# the plugin classes wins comes down to file order — and the symptom is a report
# that does not contain the change that was just built. Sweep them out.
#
# And FAIL when one will not go. This used to run `rm -f` and count the attempt: on Windows
# the VS Code Java language server holds a JAR it has indexed, the rm failed, "Removed 1
# stale plugin JAR(s)" printed anyway, the function returned 0 and deploy_plugin did not ask.
# A whole regression (N6, 2026-10-03) then ran green with 3.1.29 and 3.1.30 both in lib/ext;
# JMeter loaded 3.1.29's report.js, and every fixture stamped "current" carried a sentence one
# edit behind the tree. So the directory is listed again afterwards and believed over rm's
# word, "Removed N" counts only files that are actually gone, and anything left fails the
# deploy by name. selftest_purge_old_plugin_jars feeds this a JAR it cannot remove.
purge_old_plugin_jars() {
    local keep jar removed=0 had_nullglob=0
    local -a stale=() left=()
    keep="$(basename "$PLUGIN_JAR")"

    shopt -q nullglob && had_nullglob=1
    shopt -s nullglob
    for jar in "$JMETER_EXT"/jmeter-web-insight-report-*.jar; do
        [[ "$(basename "$jar")" == "$keep" ]] || stale+=("$jar")
    done
    for jar in ${stale[@]+"${stale[@]}"}; do
        echo "[DEPLOY] Removing stale $(basename "$jar")"
        # Its status is not trusted either way: the listing below is the answer.
        rm -f -- "$jar" || :
        [[ -e "$jar" ]] || removed=$((removed + 1))
    done
    for jar in "$JMETER_EXT"/jmeter-web-insight-report-*.jar; do
        [[ "$(basename "$jar")" == "$keep" ]] || left+=("$jar")
    done
    [[ $had_nullglob -eq 1 ]] || shopt -u nullglob

    if [[ $removed -gt 0 ]]; then
        echo "[DEPLOY] Removed $removed stale plugin JAR(s)"
    fi
    if [[ ${#left[@]} -eq 0 ]]; then
        return 0
    fi

    echo "[DEPLOY] ERROR: ${#left[@]} stale plugin JAR(s) could not be removed from $JMETER_EXT:" >&2
    local shown
    for jar in "${left[@]}"; do
        echo "           $jar" >&2
    done
    echo "         lib/ext is one flat classpath, so with more than one plugin JAR in it which copy" >&2
    echo "         JMeter loads is non-deterministic, and every report could come from an old one." >&2
    echo "         On Windows the file is usually held open by the VS Code Java language server." >&2
    echo "         PowerShell's Move-Item works where rm fails - move it anywhere outside lib/ext," >&2
    echo "         then deploy again:" >&2
    for jar in "${left[@]}"; do
        shown="$jar"
        command -v cygpath >/dev/null 2>&1 && shown="$(cygpath -w "$jar" 2>/dev/null || printf '%s' "$jar")"
        echo "           Move-Item -LiteralPath '$shown' -Destination \$env:TEMP -Force" >&2
    done
    return 1
}

# Only the shaded fat JAR is deployed — it already contains jmeter-report-core.
# Shipping core separately risks duplicate classes on the classpath.
deploy_plugin() {
    require_plugin_jar
    purge_old_plugin_jars || return 1
    echo "[DEPLOY] Copying $(basename "$PLUGIN_JAR") to $JMETER_EXT ..."
    cp -f "$PLUGIN_JAR" "$JMETER_EXT/" || return 1
    register_cli_exporter
    echo "[DEPLOY] Done"
}

# The `-g -o` pipeline only produces a Web Insight Report if the exporter is
# registered in reportgenerator.properties (see docs/FEATURES.md). This was a
# manual install step, so a fresh JMeter generates only JMeter's own dashboard
# and the CLI path silently does nothing. Idempotent.
EXPORTER_PROPERTY="jmeter.reportgenerator.exporter.webinsight.classname"
EXPORTER_CLASS="com.jmeterwebinsightreport.report.exporter.WebInsightDataExporter"

register_cli_exporter() {
    local props="$JMETER_HOME/bin/reportgenerator.properties"

    if [[ ! -f "$props" ]]; then
        echo "[DEPLOY] WARNING: $props not found — skipping -g -o exporter registration." >&2
        return 0
    fi

    if grep -q "^${EXPORTER_PROPERTY}=" "$props"; then
        echo "[DEPLOY] CLI exporter already registered"
        return 0
    fi

    if [[ ! -w "$props" ]]; then
        echo "[DEPLOY] WARNING: $props is not writable — the -g -o pipeline will not" >&2
        echo "         generate a Web Insight Report. Add this line manually:" >&2
        echo "         ${EXPORTER_PROPERTY}=${EXPORTER_CLASS}" >&2
        return 0
    fi

    {
        echo ""
        echo "# Web Insight Report — registers the -g -o CLI exporter"
        echo "${EXPORTER_PROPERTY}=${EXPORTER_CLASS}"
    } >> "$props"
    echo "[DEPLOY] Registered CLI exporter in $props"
}

# A stale plugin JAR the purge cannot remove must fail the deploy, by name (purge_old_plugin_jars
# says why). Feed it one: a scratch JMeter whose lib/ext holds an older build, and `rm` replaced —
# inside the command substitution, so nothing outside it sees the override — by a function that
# fails the way a JAR held open on Windows does, then by one that returns 0 and leaves the file,
# because the listing afterwards is the answer and rm's status is not. The same scratch install
# first takes an ordinary deploy over a stale JAR that CAN go, which must succeed and say so, so
# the refusal cannot be satisfied by a purge that refuses everything. Last, require_deployed_plugin
# (SKIP_BUILD's check, which has no purge) must refuse two plugin JARs and accept one. Needs no
# JMeter and no built JAR, so it runs in CI.
selftest_purge_old_plugin_jars() {
    local tmp out rc fail=0
    tmp="$(mktemp -d)" || { echo "[SELFTEST] mktemp failed" >&2; return 1; }
    mkdir -p "$tmp/bin" "$tmp/lib/ext" "$tmp/target"
    printf '%s=%s\n' "$EXPORTER_PROPERTY" "$EXPORTER_CLASS" > "$tmp/bin/reportgenerator.properties"
    printf 'new\n' > "$tmp/target/jmeter-web-insight-report-9.9.9.jar"
    : > "$tmp/lib/ext/another-plugin.jar"

    # 1. A stale JAR that can be removed: removed, counted, and the new one deployed.
    : > "$tmp/lib/ext/jmeter-web-insight-report-9.9.8.jar"
    rc=0
    out="$(JMETER_HOME="$tmp"; JMETER_EXT="$tmp/lib/ext"
           PLUGIN_JAR="$tmp/target/jmeter-web-insight-report-9.9.9.jar"
           deploy_plugin 2>&1)" || rc=$?
    if [[ $rc -ne 0 || -e "$tmp/lib/ext/jmeter-web-insight-report-9.9.8.jar" \
          || ! -f "$tmp/lib/ext/jmeter-web-insight-report-9.9.9.jar" \
          || ! -e "$tmp/lib/ext/another-plugin.jar" \
          || "$out" != *"Removed 1 stale plugin JAR(s)"* ]]; then
        echo "[SELFTEST] the deploy did not replace a stale plugin JAR it could remove (rc=$rc):" >&2
        printf '%s\n' "$out" | sed 's/^/    /' >&2
        fail=1
    fi

    # 2. One that cannot: refused, naming the file and the remedy, claiming no removal, and with
    #    nothing deployed beside it.
    rm -f "$tmp/lib/ext/jmeter-web-insight-report-9.9.9.jar"
    : > "$tmp/lib/ext/jmeter-web-insight-report-9.9.7.jar"
    rc=0
    out="$(JMETER_HOME="$tmp"; JMETER_EXT="$tmp/lib/ext"
           PLUGIN_JAR="$tmp/target/jmeter-web-insight-report-9.9.9.jar"
           rm() { echo "rm: cannot remove '${@: -1}': Device or resource busy" >&2; return 1; }
           deploy_plugin 2>&1)" || rc=$?
    if [[ $rc -eq 0 ]]; then
        echo "[SELFTEST] deploy_plugin succeeded with a stale plugin JAR it could not remove still in lib/ext:" >&2
        printf '%s\n' "$out" | sed 's/^/    /' >&2
        fail=1
    elif [[ "$out" != *ERROR*jmeter-web-insight-report-9.9.7.jar* || "$out" != *Move-Item* ]]; then
        echo "[SELFTEST] the deploy refused a stale JAR it could not remove, but did not name it and the remedy:" >&2
        printf '%s\n' "$out" | sed 's/^/    /' >&2
        fail=1
    elif [[ "$out" == *"Removed "* || -e "$tmp/lib/ext/jmeter-web-insight-report-9.9.9.jar" ]]; then
        echo "[SELFTEST] the deploy refused, but still claimed a removal or deployed beside the stale JAR:" >&2
        printf '%s\n' "$out" | sed 's/^/    /' >&2
        fail=1
    fi

    # 3. An rm that says it succeeded and leaves the file (a delete-pending file on Windows, a
    #    shim): the listing is believed, not rm, so this is refused exactly as case 2 is. A purge
    #    that trusted rm's status would pass case 2 and print "Removed 1" here.
    rm -f "$tmp/lib/ext/jmeter-web-insight-report-9.9.7.jar"
    : > "$tmp/lib/ext/jmeter-web-insight-report-9.9.6.jar"
    rc=0
    out="$(JMETER_HOME="$tmp"; JMETER_EXT="$tmp/lib/ext"
           PLUGIN_JAR="$tmp/target/jmeter-web-insight-report-9.9.9.jar"
           rm() { return 0; }
           deploy_plugin 2>&1)" || rc=$?
    if [[ $rc -eq 0 || "$out" != *ERROR*jmeter-web-insight-report-9.9.6.jar* || "$out" == *"Removed "* \
          || -e "$tmp/lib/ext/jmeter-web-insight-report-9.9.9.jar" ]]; then
        echo "[SELFTEST] the deploy believed an rm that returned 0 and left the stale JAR in place (rc=$rc):" >&2
        printf '%s\n' "$out" | sed 's/^/    /' >&2
        fail=1
    fi

    # 4. SKIP_BUILD's check, require_deployed_plugin, has no purge to lean on: two plugin JARs are
    #    refused by name before anything runs, and the deployed JAR alone is accepted.
    rm -f "$tmp/lib/ext/jmeter-web-insight-report-9.9.6.jar"
    : > "$tmp/lib/ext/jmeter-web-insight-report-9.9.7.jar"
    cp "$tmp/target/jmeter-web-insight-report-9.9.9.jar" "$tmp/lib/ext/"
    rc=0
    out="$(JMETER_EXT="$tmp/lib/ext"; PLUGIN_JAR="$tmp/target/jmeter-web-insight-report-9.9.9.jar"
           require_deployed_plugin 2>&1)" || rc=$?
    if [[ $rc -eq 0 || "$out" != *"lib/ext holds 2 plugin JARs (jmeter-web-insight-report-9.9.7.jar, jmeter-web-insight-report-9.9.9.jar)"* ]]; then
        echo "[SELFTEST] require_deployed_plugin accepted a lib/ext holding two plugin JARs (rc=$rc):" >&2
        printf '%s\n' "$out" | sed 's/^/    /' >&2
        fail=1
    fi
    rm -f "$tmp/lib/ext/jmeter-web-insight-report-9.9.7.jar"
    rc=0
    out="$(JMETER_EXT="$tmp/lib/ext"; PLUGIN_JAR="$tmp/target/jmeter-web-insight-report-9.9.9.jar"
           require_deployed_plugin 2>&1)" || rc=$?
    if [[ $rc -ne 0 ]]; then
        echo "[SELFTEST] require_deployed_plugin refused a lib/ext holding only the deployed JAR:" >&2
        printf '%s\n' "$out" | sed 's/^/    /' >&2
        fail=1
    fi
    rm -rf "$tmp"
    [[ $fail -eq 0 ]] || return 1
    echo "[SELFTEST] the deploy refuses a stale plugin JAR it cannot remove, and SKIP_BUILD refuses two"
}

# ---- JMeter invocation ------------------------------------------------------
# Every caller used the same shape; this is the one place it lives now.
#
# JMeter APPENDS to an existing -l file rather than truncating it, so rerunning a
# scenario into a directory from an earlier cycle analyses BOTH runs' samples. That
# is how the CMP fixture reached five concatenated 30-second runs in its baseline
# and nine in its current file, spanning 119 days. run-all-tests.sh and
# generate-cmp-fixture.sh clear their own JTLs because they invoke $JMETER directly;
# clearing here means every caller that goes through this function — the config and
# error suites, and anything added later — gets it without having to remember.
#
# It rots only on developer machines: CI checks out into an empty workspace every
# time, so a suite that is wrong locally stays green in the workflow. No caller has
# ever wanted the append behaviour.
# JMeter's OWN log file, which is a different thing from the console capture every
# caller writes to "$out_dir/jmeter.log".
#
# That distinction is the whole reason this exists. Without -j, JMeter writes jmeter.log
# into the process CWD, and nothing here ever captured it — so every log.warn and
# log.error the plugin emits went to a file no test looked at. JMeter's default
# log4j2.xml has NO Console appender (Root -> file + GUI panel only), so those lines
# never reached the captured stdout either. Two real defects survived releases because
# of it: a missing annotations file disabling the assert gate, and the -g -o exporter
# skipping generation. Both logged the reason, correctly, where nobody could read it.
#
# The name is deliberately NOT jmeter.log. Every runner already redirects merged
# stdout+stderr to "$out_dir/jmeter.log"; pointing log4j2's File appender at that same
# path would have the shell and log4j2 writing to one file, and which survives is not
# worth reasoning about. So: jmeter.log = what the console said, jmeter-runtime.log =
# what JMeter logged. Assert on the first with assert_contains, the second with
# assert_log_contains.
#
# WIR_RUNTIME_LOG lets a caller that invokes run_jmeter directly (the C and N suites,
# which do not go through run_test_jmeter) opt in without restating -j themselves.
run_jmeter() {
    local i
    for ((i = 1; i < $#; i++)); do
        if [[ "${!i}" == "-l" ]]; then
            local next=$((i + 1))
            rm -f "${!next}"
        fi
    done
    if [[ -n "${WIR_RUNTIME_LOG:-}" ]]; then
        mkdir -p "$(dirname "$WIR_RUNTIME_LOG")" 2>/dev/null
        "$JMETER" -j "$WIR_RUNTIME_LOG" "$@"
    else
        "$JMETER" "$@"
    fi
}

# run_test_jmeter <out_dir> <jmx> <report_basename> <title> [extra args...]
#
# Returns 0 when this run wrote <out_dir>/<base>.html, which it then stamps; 1 when it did not, and
# then there is no report and no stamp in <out_dir> for anything to call current. It used to stamp
# whatever was in the directory, so a run whose JMeter rendered nothing left the previous cycle's
# report under a fresh stamp naming the JAR now in lib/ext, and every reader called it current (F3).
#
# So it first removes what it is about to write (the report, its .json, .xml and external-data
# sidecar, and the stamp) and believes the directory over rm: a Windows lock makes rm fail, and a
# report still there afterwards cannot be told apart from one this run wrote, so it refuses to run
# over it. JMeter's exit code is deliberately not the signal: E25 runs with failonerror=false, a FAIL
# verdict exits 0, and a configuration error exits 1 beside a genuine report. Only the file is.
run_test_jmeter() {
    local out_dir="$1" jmx="$2" base="$3" title="$4"
    shift 4
    mkdir -p "$out_dir"
    rm -f "$out_dir/.fixture-stamp" "$out_dir/$base.html" "$out_dir/$base.json" \
          "$out_dir/$base.xml" "$out_dir/$base-data.js" 2>/dev/null
    # The default report name's sidecar keeps its literal name (externalDataFilename).
    [[ "$base" != web-insight-report ]] || rm -f "$out_dir/web-insight-data.js" 2>/dev/null
    if [[ -e "$out_dir/$base.html" ]]; then
        echo "ERROR: could not remove the previous $out_dir/$base.html; not running over it" \
            | tee "$out_dir/jmeter.log" >&2
        return 1
    fi
    # Through run_jmeter, not $JMETER directly, so this function clears its own
    # results.jtl rather than depending on each caller to remember. run-all-tests.sh
    # empties the whole scenario directory first as well; nothing else has to.
    WIR_RUNTIME_LOG="$out_dir/jmeter-runtime.log" \
    run_jmeter -n -t "$TEST_PLANS/$jmx" \
        -l "$out_dir/results.jtl" \
        -Jwebinsight.report.output="$out_dir" \
        -Jwebinsight.report.filename="${base}.html" \
        -Jwebinsight.report.title="$title" \
        "$@" > "$out_dir/jmeter.log" 2>&1
    [[ -f "$out_dir/$base.html" ]] || return 1
    write_fixture_stamp "$out_dir"
}

# ---- fixture provenance ------------------------------------------------------
# The Playwright suite does not generate reports — it opens ones a JMeter run wrote
# earlier into test-results/, which is gitignored. Change report.js, rebuild, forget
# to regenerate, and every spec asserts against the OLD report and passes.
#
# So each generated report gets a stamp recording the CONTENT of the files that decide
# what a report looks like. Content, not timestamps: an mtime comparison calls fixtures
# stale after any rebuild — even one that changed nothing — and after a branch switch,
# and a check that cries wolf just teaches people to bypass it.
# EVERY source file of both modules, not a hand-picked five.
#
# The old list named report.html, report.js, report.css, echarts.min.js and
# report/template/*.java — the files that decide what a report LOOKS like. It omitted
# everything that decides what a report SAYS: core/stats, core/parser, core/assertion,
# core/comparison. So editing the statistics engine left every stamp matching,
# fixtures_current returned true, run-regression.sh skipped regeneration, and all 259
# browser tests asserted against numbers computed by the previous build — green.
# Verified against LiveStatisticsEngine.java before this change: fixtures_current => true.
#
# SKILL.md has always told people to run regression after touching those packages; the
# suite just had no way to act on it. An allowlist of "files we think matter" has the same
# flaw the shade guard calls out (ALLOWED_JAR_ROOTS: "an ALLOWLIST, not a list of known
# offenders") — the next package someone adds is missed by construction. So: hash the
# sources, all of them, and let anything that cannot change a report simply never change.
_fixture_stamp_files() {
    # test-plans/server-metrics is in here for the same reason src/main is: it decides what a
    # report LOOKS LIKE. E26's KPI verdicts, coverage figures, instance counts and reason
    # sentences all come out of that directory's recording, annotations and collected payload,
    # so editing one of them and not regenerating leaves server-tab.spec.ts asserting against
    # the previous build's numbers — green, and about a report nobody has now.
    #
    # It is one listing compared whole, so adding a path invalidates EVERY fixture at once and
    # costs a full regeneration. That is the right trade here: the alternative is a directory
    # whose contents silently do not count.
    #
    # test-plans/playwright/fixture-stamp.js walks the same directories (STAMP_ROOTS) to compare
    # the listing whole for global-setup.ts, and js-unit/fixture-stamp.test.js parses this find
    # and fails when the two lists differ. Keep it a plain `find \`, one directory per line,
    # `-type f`: that test refuses any other shape rather than guess at it.
    ( cd "$PROJECT_DIR" 2>/dev/null && find \
        jmeter-report-core/src/main \
        jmeter-web-insight-report/src/main \
        test-plans/server-metrics \
        test-plans/server-catalogue \
        -type f 2>/dev/null | sort )
}

# "<relative path>\t<md5>" for every stamp file — one hashing pass for the whole set.
# Callers compare whole listings rather than re-hashing per fixture, so widening the set
# 8x made the check faster than the version it replaced, not slower.
_current_source_hashes() {
    ( cd "$PROJECT_DIR" 2>/dev/null || return 0
      _fixture_stamp_files | files_md5 )
}

# ...and which JAR rendered it. The listing above is the tree at the moment the stamp is
# written, which is not the same thing as what produced the report: N6's regression
# (2026-10-03) ran with 3.1.29 and 3.1.30 both in lib/ext, JMeter loaded 3.1.29's report.js,
# and every fixture inlined a sentence one edit behind the tree while its stamp, hashed from
# the tree, said "current". So after the listing the stamp records, for every resource the
# listing names, the md5 of that file inside the plugin JAR in lib/ext when the stamp was
# written, to be compared with the tree's own line for it above:
#
#   @deployed:<entry> <TAB> <md5 | none | unavailable> <TAB> <the JAR's name, or why no md5>
#
# <entry> is the path inside the JAR: the listing's <module>/src/main/resources/<entry>, minus
# META-INF/, which the shade plugin writes rather than copies (its manifest and services
# transformers). Everything else there (the template, report.js, report.css, echarts, the
# schema, core's catalogue) is shaded byte for byte, so it can be compared, and a resource added
# later is compared without anyone listing it. The first version of this record held report.js
# alone, and a JAR whose template or CSS was not the tree's stamped current.
#
# fixtures_current and global-setup.ts (through test-plans/playwright/fixture-stamp.js) both
# read the records: an md5 that is not the tree's is a fixture rendered by another build, "none"
# (no JAR, several JARs, the entry missing from it) is a fixture whose renderer is unknown, and a
# resource with no record means the stamp predates the record and is stale. "unavailable" means
# unzip was not on PATH: only this comparison is skipped, and that is said once, as
# verify_shaded_jar skips without `jar`.
#
# A JAR's classes cannot be compared with the tree's sources at all, so a JAR that differs from
# the tree in its Java alone is not caught here (check-deploy-freshness.sh warns on that, by
# mtime).
_STAMP_JAR_PREFIX='@deployed:'
_STAMP_RESOURCES='/src/main/resources/'
_STAMP_SHADE_OWNED='META-INF/'

# The JAR entry a listing path is compared on, into _STAMP_ENTRY (a global, because a $(...) is a
# fork); 1 when the path is not a compared resource.
_stamp_entry_of() {
    _STAMP_ENTRY=""
    [[ "$1" == *"$_STAMP_RESOURCES"* ]] || return 1
    local module="${1%%"$_STAMP_RESOURCES"*}"
    [[ -n "$module" && "$module" != */* ]] || return 1
    _STAMP_ENTRY="${1#*"$_STAMP_RESOURCES"}"
    if [[ -z "$_STAMP_ENTRY" || "$_STAMP_ENTRY" == "$_STAMP_SHADE_OWNED"* ]]; then
        _STAMP_ENTRY=""
        return 1
    fi
    return 0
}

# Behind a function so selftest_fixture_stamps can take unzip away in a subshell.
_have_unzip() { command -v unzip >/dev/null 2>&1; }

# The plugin JARs in lib/ext, into globals rather than onto stdout, because a $(...) is a fork
# and Git Bash forks slowly: _PLUGIN_JAR_N, _PLUGIN_JAR_NAMES ("a.jar, b.jar") and
# _PLUGIN_JAR_FIRST (a path). lib/ext is JMETER_EXT once require_jmeter has run, otherwise
# resolved the way require_jmeter resolves it, without its exit; no JMeter means no JARs.
_scan_plugin_jars() {
    local ext="${JMETER_EXT:-}" home jar
    _PLUGIN_JAR_N=0
    _PLUGIN_JAR_NAMES=""
    _PLUGIN_JAR_FIRST=""
    if [[ -z "$ext" ]]; then
        home="$(detect_jmeter_home 2>/dev/null)" || return 0
        ext="$home/lib/ext"
    fi
    for jar in "$ext"/jmeter-web-insight-report-*.jar; do
        [[ -e "$jar" ]] || continue
        _PLUGIN_JAR_N=$((_PLUGIN_JAR_N + 1))
        [[ -n "$_PLUGIN_JAR_FIRST" ]] || _PLUGIN_JAR_FIRST="$jar"
        _PLUGIN_JAR_NAMES="${_PLUGIN_JAR_NAMES:+$_PLUGIN_JAR_NAMES, }${jar##*/}"
    done
    return 0
}

# The stamp's JAR records for <listing> (the tree listing just written above them), for the JAR
# in lib/ext right now. See _STAMP_JAR_PREFIX.
_deployed_jar_stamp_lines() {
    local listing="$1" line entry md5 status="" info="" hashes="" tmp=""
    local -a entries=()
    while IFS= read -r line; do
        [[ -n "$line" ]] || continue
        _stamp_entry_of "${line%%$'\t'*}" && entries+=("$_STAMP_ENTRY")
    done <<< "$listing"
    [[ ${#entries[@]} -gt 0 ]] || return 0

    _scan_plugin_jars
    if [[ $_PLUGIN_JAR_N -eq 0 ]]; then
        status=none; info="no plugin JAR was in lib/ext"
    elif [[ $_PLUGIN_JAR_N -gt 1 ]]; then
        status=none; info="lib/ext held $_PLUGIN_JAR_N plugin JARs ($_PLUGIN_JAR_NAMES)"
    elif ! _have_unzip; then
        status=unavailable; info="unzip was not on PATH"
    elif ! tmp="$(mktemp -d 2>/dev/null)" || [[ -z "$tmp" ]]; then
        status=none; info="no scratch directory to unzip $_PLUGIN_JAR_NAMES into"
    else
        # One unzip and one hashing pass for every entry, not a process pair per file. An entry the
        # JAR lacks is simply not extracted (unzip says so and exits non-zero; both are expected).
        info="$_PLUGIN_JAR_NAMES"
        unzip -qq -o "$_PLUGIN_JAR_FIRST" "${entries[@]}" -d "$tmp" >/dev/null 2>&1
        hashes=$'\n'"$(cd "$tmp" && printf '%s\n' "${entries[@]}" | files_md5)"$'\n'
        rm -rf "$tmp"
    fi

    for entry in "${entries[@]}"; do
        if [[ -n "$status" ]]; then
            printf '%s%s\t%s\t%s\n' "$_STAMP_JAR_PREFIX" "$entry" "$status" "$info"
        elif [[ "$hashes" == *$'\n'"$entry"$'\t'* ]]; then
            md5="${hashes#*$'\n'"$entry"$'\t'}"
            printf '%s%s\t%s\t%s\n' "$_STAMP_JAR_PREFIX" "$entry" "${md5%%$'\n'*}" "$info"
        else
            printf '%s%s\t%s\t%s\n' "$_STAMP_JAR_PREFIX" "$entry" none "$info had no $entry"
        fi
    done
}

# write_fixture_stamp <report_dir>
write_fixture_stamp() {
    local out_dir="$1" listing
    [[ -d "$out_dir" ]] || return 0
    listing="$(_current_source_hashes)"
    { [[ -z "$listing" ]] || printf '%s\n' "$listing"
      _deployed_jar_stamp_lines "$listing"; } > "$out_dir/.fixture-stamp" 2>/dev/null || return 0
}

# _read_fixture_stamp <stamp file> splits a stamp into globals, with builtins only (it runs once
# per fixture at the top of every suite):
#   _STAMP_TREE   the tree listing, exactly as _current_source_hashes writes it
#   _STAMP_RES    the listing's compared resources, one "<entry>\t<md5>" per line, in its order
#   _STAMP_JAR    the JAR records, one "<entry>\t<md5>\t<info>" per line; "" when there are none
# A line beginning "@" is a record, never a file. One in an older shape (the first version's single
# "@deployed-report.js" line) is skipped, so its stamp has no records and reads as stale.
_read_fixture_stamp() {
    local line
    _STAMP_TREE=""
    _STAMP_RES=""
    _STAMP_JAR=""
    while IFS= read -r line || [[ -n "$line" ]]; do
        [[ -n "$line" ]] || continue
        if [[ "$line" == @* ]]; then
            [[ "$line" != "$_STAMP_JAR_PREFIX"* ]] \
                || _STAMP_JAR="${_STAMP_JAR:+$_STAMP_JAR$'\n'}${line#"$_STAMP_JAR_PREFIX"}"
            continue
        fi
        _STAMP_TREE="${_STAMP_TREE:+$_STAMP_TREE$'\n'}$line"
        # The glob first: most lines are sources, and a function call per line costs.
        [[ "$line" == *"$_STAMP_RESOURCES"* ]] && _stamp_entry_of "${line%%$'\t'*}" \
            && _STAMP_RES="${_STAMP_RES:+$_STAMP_RES$'\n'}$_STAMP_ENTRY"$'\t'"${line#*$'\t'}"
    done < "$1"
    return 0
}

# Whether the stamp just read says its fixture was rendered by a JAR holding the tree's copy of
# every resource its listing names. 0 when it was, or when that could not be checked (said once,
# on stderr); 1 with the reason in _STAMP_JAR_PROBLEM when it was not. Resources are visited in
# listing order: the first with no record, or with no md5, decides; every one whose md5 is not
# the tree's is named. The same rules and sentences as test-plans/playwright/fixture-stamp.js.
_check_stamp_jar() {
    local line entry rec md5 info any=0 differ="" differ_n=0 differ_info="" verb=is
    local jar=$'\n'"$_STAMP_JAR"$'\n'
    _STAMP_JAR_PROBLEM=""
    while IFS= read -r line; do
        [[ -n "$line" ]] || continue
        entry="${line%%$'\t'*}"
        any=1
        if [[ "$jar" != *$'\n'"$entry"$'\t'* ]]; then
            _STAMP_JAR_PROBLEM="stamped before stamps recorded which JAR rendered the report — regenerate"
            return 1
        fi
        rec="${jar#*$'\n'"$entry"$'\t'}"
        rec="${rec%%$'\n'*}"
        md5="${rec%%$'\t'*}"
        info=""
        [[ "$rec" != *$'\t'* ]] || info="${rec#*$'\t'}"
        if [[ "$md5" == unavailable ]]; then
            if [[ -z "${_WIR_UNZIP_NOTED:-}" ]]; then
                _WIR_UNZIP_NOTED=1
                echo "[FIXTURES] A fixture was stamped where unzip was not on PATH, so which JAR rendered it is not checked." >&2
            fi
            continue
        fi
        if [[ ! "$md5" =~ ^[0-9a-f]{32}$ ]]; then
            _STAMP_JAR_PROBLEM="stamped when ${info:-no JAR could be read}, so what rendered it is unknown — redeploy and regenerate"
            return 1
        fi
        if [[ "$md5" != "${line#*$'\t'}" ]]; then
            differ="${differ:+$differ, }$entry"
            differ_n=$((differ_n + 1))
            [[ -n "$differ_info" ]] || differ_info="$info"
        fi
    done <<< "$_STAMP_RES"
    if [[ $any -eq 0 ]]; then
        # A listing that names no resource has nothing the records could vouch for.
        _STAMP_JAR_PROBLEM="stamped before stamps recorded which JAR rendered the report — regenerate"
        return 1
    fi
    if [[ $differ_n -gt 0 ]]; then
        [[ $differ_n -eq 1 ]] || verb=are
        _STAMP_JAR_PROBLEM="rendered by a JAR whose $differ $verb not the tree's (${differ_info:-unnamed}) — redeploy and regenerate"
        return 1
    fi
    return 0
}

# The sentence stale_fixture_reason gives while lib/ext holds more than one plugin JAR, which is
# why fixtures_current refuses (silently). Call after _scan_plugin_jars.
_several_plugin_jars_sentence() {
    echo "lib/ext holds $_PLUGIN_JAR_N plugin JARs ($_PLUGIN_JAR_NAMES), so which one renders a report is non-deterministic and no fixture can be called current — ./scripts/build-and-deploy.sh removes the stale ones, or names the one it cannot"
}

# The fixtures the Playwright suite actually opens. This is the list that decides how
# much JMeter work an automated cycle has to do — every other scenario in
# run-all-tests.sh validates only "a report was produced" and is manual-tier.
# Keep in step with REQUIRED_FIXTURES in test-plans/playwright/global-setup.ts.
FIXTURE_IDS="E02 E05 E06 E07 E08 E09 E10 E11 E12 E19 E25 E26 E27 CMP"

# Every tracked .sh and git hook must be committed 100755.
#
# A script committed 100644 is not a syntax error and not a test failure — it is a
# "Permission denied" the moment someone follows a documented command, and for a hook
# it is worse: Linux and macOS skip a non-executable hook SILENTLY, so the gate that
# was supposed to block the push simply does not run. Windows sets core.filemode=false,
# so the bit never comes from the filesystem and has to be written into the index
# deliberately — nothing local complains when it is missing.
#
# This lived in ci.yml until the GitHub workflows were removed. It reads the git index,
# not the working tree, so it works the same on Windows as anywhere else.
verify_script_modes() {
    git -C "$PROJECT_DIR" rev-parse --git-dir >/dev/null 2>&1 || return 0

    local bad
    bad="$(git -C "$PROJECT_DIR" ls-files -s -- '*.sh' .githooks/ \
           | awk '$1 != "100755" {print $4}')"
    if [[ -n "$bad" ]]; then
        echo "[MODES] FAILED: committed without the executable bit:" >&2
        printf '        %s\n' $bad >&2
        echo "        Fix with: git update-index --chmod=+x <file>" >&2
        return 1
    fi
    echo "[MODES] All tracked scripts and hooks are 100755"
}

# Every tracked text blob must be stored LF.
#
# .gitattributes has said `* text=auto eol=lf` since 9811d47, but it only applies to
# files staged after it existed — it never renormalized what was already committed, and
# nothing ever checked. 26 files kept CRLF blobs, including both .jmx test plans, whose
# `*.jmx text eol=lf` pin carries the comment "parsed byte-for-byte; pin them". The pin
# applied to neither of them. A declaration nothing verifies is not a mechanism.
#
# The rule is simpler than .gitattributes makes it look: `eol=` governs the WORKING TREE
# only. Every text blob in git is LF, always — including the .bat files pinned eol=crlf,
# which are CRLF on disk and LF in the repo. So one rule covers .jmx, .sh, .bat, all of it.
#
# Why this failing silently is expensive: git hides it behind the stat cache, so a CRLF
# blob looks clean until something touches the file. Then the clean filter normalizes the
# checked-out copy to LF, it no longer matches the blob, and the file reads as permanently
# modified. Staging it turns a 158-line change into an 11,566-line diff — which is how a
# finished feature sat unmerged for eleven days behind a diff no one could review.
#
# Reads the index, not the working tree, for the same reason verify_script_modes does:
# the worktree copy is whatever the smudge filter produced, while the blob is what
# everyone else receives. `git grep --cached` searches the index in a single process
# (~50ms for this tree); -I skips binaries. -F with a literal CR rather than -P '\r$'
# because the PCRE form needs git built --with-libpcre, which is not guaranteed.
verify_line_endings() {
    git -C "$PROJECT_DIR" rev-parse --git-dir >/dev/null 2>&1 || return 0

    # `|| true` for the same reason verify_shaded_jar has one: grep exiting 1 on no
    # matches is the SUCCESS case here, and under `set -o pipefail` it would otherwise
    # fail the substitution and abort the caller.
    local bad
    bad="$(git -C "$PROJECT_DIR" grep --cached -I -l -F "$(printf '\r')" -- . || true)"
    if [[ -n "$bad" ]]; then
        echo "[EOL] FAILED: text blobs committed with CRLF:" >&2
        printf '        %s\n' $bad >&2
        echo "        .gitattributes says eol=lf. Note eol= governs checkout only —" >&2
        echo "        every text blob must be LF regardless of it." >&2
        echo "        Fix with: git add --renormalize <file> && git commit" >&2
        return 1
    fi
    echo "[EOL] All text blobs are LF"
}

# No tracked text file may carry a raw NUL byte.
#
# GNU grep treats a file as binary from the buffer that holds a NUL onwards, so every grep-shaped
# guard over that file goes blind past the byte, and it does so silently: `grep -n` prints
# "Binary file ... matches" instead of the lines, and a guard that pipes into a second grep
# filters even that away. Two NULs pasted into report.js (a '<NUL>' separator inside a
# dictionary key) hid four of the Server tab's inline handlers from the jsArg rule in
# verify_report_js_rules, and the same paste sat in Collector.java. Both now write the \u0000
# escape, which is the same character at runtime and plain ASCII in the file.
#
# "Text" means every path .gitattributes does not declare binary (`-text`). A single
# `git ls-files --eol` finds the suspects, because git reports a blob or a working-tree file
# holding a NUL as -text. Only those suspects are read to count the bytes, so a clean tree costs
# one process. Both the index and the working tree are checked: the index is what everyone else
# receives, and the working tree is where the next paste lands before anyone stages it. A
# genuinely binary format belongs in .gitattributes as `binary`, which keeps eol= off it as well.
#
# The optional argument is a repository directory, for selftest_no_nul_bytes. Every real caller
# passes nothing.
verify_no_nul_bytes() {
    local repo="${1:-$PROJECT_DIR}"
    git -C "$repo" rev-parse --git-dir >/dev/null 2>&1 || return 0

    # Fields before the TAB are "i/<index> w/<worktree> attr/<attributes>"; the path follows it.
    local suspects bad=() f n_index n_tree
    suspects="$(git -C "$repo" ls-files --eol \
        | awk -F'\t' '($1 ~ /^i\/-text/ || $1 ~ / w\/-text/) && $1 !~ /attr\/-text/ { print $2 }')"
    while IFS= read -r f; do
        [[ -n "$f" ]] || continue
        n_index=$(( $(git -C "$repo" cat-file blob ":$f" 2>/dev/null | LC_ALL=C tr -cd '\000' | wc -c) ))
        n_tree=0
        if [[ -f "$repo/$f" ]]; then
            n_tree=$(( $(LC_ALL=C tr -cd '\000' < "$repo/$f" | wc -c) ))
        fi
        if (( n_index > 0 || n_tree > 0 )); then
            bad+=("$f (index: $n_index, working tree: $n_tree)")
        fi
    done <<< "$suspects"

    if (( ${#bad[@]} > 0 )); then
        echo "[NUL] FAILED: raw NUL bytes in tracked text files:" >&2
        printf '        %s\n' "${bad[@]}" >&2
        echo "        A NUL turns grep's view of the rest of the file binary, which blinds every" >&2
        echo "        grep-shaped guard over it. Write the escape instead (\\u0000 in JS and Java)," >&2
        echo "        or, for a genuinely binary format, declare its extension binary in .gitattributes." >&2
        return 1
    fi
    echo "[NUL] No tracked text file carries a NUL byte"
}

# The NUL check reads git's own classification, so feed it a real index: a scratch repository
# with this project's attribute rules, one clean text file, one declared binary that is full of
# NULs, and then a text file with a pasted NUL. It must accept the first state and refuse the
# second by name — a check that accepted both would pass on every tree there is.
selftest_no_nul_bytes() {
    local repo out fail=0
    repo="$(mktemp -d)" || { echo "[SELFTEST] mktemp failed" >&2; return 1; }
    if ! git -C "$repo" init -q >/dev/null 2>&1; then
        echo "[SELFTEST] git init failed in $repo" >&2; rm -rf "$repo"; return 1
    fi
    printf '%s\n' '* text=auto eol=lf' '*.png binary' > "$repo/.gitattributes"
    printf '%s\n' 'var key = a + b;' > "$repo/clean.js"
    printf '\211PNG\r\n\032\n\0\0\0\rIHDR' > "$repo/image.png"
    git -C "$repo" add .gitattributes clean.js image.png >/dev/null 2>&1
    if ! verify_no_nul_bytes "$repo" >/dev/null 2>&1; then
        echo "[SELFTEST] the NUL check refused a clean text file or a declared binary" >&2; fail=1
    fi
    printf "var key = a + '\0' + b;\n" > "$repo/pasted.js"
    git -C "$repo" add pasted.js >/dev/null 2>&1
    if out="$(verify_no_nul_bytes "$repo" 2>&1)"; then
        echo "[SELFTEST] the NUL check accepted a raw NUL in a text file" >&2; fail=1
    elif [[ "$out" != *pasted.js* || "$out" == *image.png* ]]; then
        echo "[SELFTEST] the NUL check refused, but did not name exactly the pasted file:" >&2
        printf '%s\n' "$out" | sed 's/^/    /' >&2
        fail=1
    fi
    # And before it is staged: the working tree is where the next paste lands first.
    printf '%s\n' 'var key = a + b;' > "$repo/pasted.js"
    printf "var key = a + '\0' + b;\n" > "$repo/clean.js"
    git -C "$repo" add pasted.js >/dev/null 2>&1
    if out="$(verify_no_nul_bytes "$repo" 2>&1)" || [[ "$out" != *clean.js* ]]; then
        echo "[SELFTEST] the NUL check missed a NUL in a tracked file's working-tree copy" >&2; fail=1
    fi
    rm -rf "$repo"
    [[ $fail -eq 0 ]] || return 1
    echo "[SELFTEST] the NUL check refuses what it claims to"
}

# ...and this checks that they actually are in step, because a comment asking two
# files to agree is not a mechanism. The lists are the same set from two directions:
# FIXTURE_IDS decides which scenarios a runner generates, REQUIRED_FIXTURES decides
# which ones the browser suite refuses to start without. An ID in only one of them is
# either generated and never checked, or checked and never generated — and both fail
# quietly, which is the whole reason the stamps exist.
verify_fixture_lists() {
    local setup="$TEST_PLANS/playwright/global-setup.ts"
    [[ -f "$setup" ]] || { echo "[LISTS] global-setup.ts not found: $setup" >&2; return 1; }

    # `|| true` for the same reason verify_shaded_jar has one: the failure case here
    # is grep matching nothing, which exits 1, which under `set -o pipefail` fails the
    # whole substitution — and under `set -e` aborts the caller outright. CI runs its
    # steps with `set -euo pipefail`, so without this a reshaped declaration would kill
    # the step with no output instead of reaching the diagnostic below.
    local ts_ids sh_ids
    ts_ids="$(sed -n '/const REQUIRED_FIXTURES = \[/,/\]/p' "$setup" \
        | grep -oE "'[A-Za-z0-9_]+'" | tr -d "'" | sort | tr '\n' ' ' || true)"
    sh_ids="$(printf '%s\n' $FIXTURE_IDS | sort | tr '\n' ' ')"

    if [[ -z "$ts_ids" ]]; then
        echo "[LISTS] FAILED: could not parse REQUIRED_FIXTURES out of $setup." >&2
        echo "        If the declaration was reshaped, update this parser too —" >&2
        echo "        an unparseable list must fail, not silently agree." >&2
        return 1
    fi

    if [[ "$ts_ids" != "$sh_ids" ]]; then
        echo "[LISTS] FAILED: the two fixture lists disagree." >&2
        echo "        common.sh FIXTURE_IDS             : ${sh_ids% }" >&2
        echo "        global-setup.ts REQUIRED_FIXTURES : ${ts_ids% }" >&2
        return 1
    fi
    echo "[LISTS] Fixture lists agree (${sh_ids% })"
}

# True when every fixture exists and was generated by the source now in the tree.
# Same content-hash comparison global-setup.ts performs, so the two agree on what
# "stale" means — this one exists so a runner can SKIP regeneration rather than
# discover the mismatch after paying for it.
# Comparing whole listings, not line-by-line lookups, so a source file that was ADDED
# invalidates the fixtures too — a per-line check only sees files the stamp already knew
# about, and would wave through a brand-new class that changes every number in the report.
#
# And two refusals about what rendered them (see _STAMP_JAR_PREFIX): every stamp's JAR records
# must match the tree's resources, and lib/ext must not hold more than one plugin JAR right now
# — with two, whichever regeneration comes next is rendered by a JAR nobody chose. Quietly, like
# every other refusal here: stale_fixture_reason gives the reasons, and a runner that printed
# both used to say the two-JAR sentence twice.
fixtures_current() {
    local id current stamp
    _scan_plugin_jars
    [[ $_PLUGIN_JAR_N -le 1 ]] || return 1
    current="$(_current_source_hashes)"
    # No sources hashed means no md5 tool, or the tree moved. Either way we cannot claim
    # the fixtures are current; say so rather than pass by default.
    [[ -n "$current" ]] || return 1
    for id in $FIXTURE_IDS; do
        [[ -f "$RESULTS_DIR/$id/$id-report.html" ]] || return 1
        stamp="$RESULTS_DIR/$id/.fixture-stamp"
        [[ -f "$stamp" ]] || return 1
        _read_fixture_stamp "$stamp"
        [[ "$_STAMP_TREE" == "$current" ]] || return 1
        _check_stamp_jar || return 1
    done
    return 0
}

# Name the files that invalidated the fixtures, for a runner that wants to say why
# it is about to spend ten minutes regenerating.
stale_fixture_reason() {
    local id stamp current tmp tab=$'\t'
    _scan_plugin_jars
    if [[ $_PLUGIN_JAR_N -gt 1 ]]; then
        _several_plugin_jars_sentence
    fi
    current="$(_current_source_hashes)"
    for id in $FIXTURE_IDS; do
        if [[ ! -f "$RESULTS_DIR/$id/$id-report.html" ]]; then
            echo "$id: missing"; continue
        fi
        stamp="$RESULTS_DIR/$id/.fixture-stamp"
        if [[ ! -f "$stamp" ]]; then
            echo "$id: no provenance stamp"; continue
        fi
        _read_fixture_stamp "$stamp"
        # Any line in one listing but not the other is a source file that changed, was
        # added or was removed. Report the path once per fixture regardless of which. Only
        # when they differ: the diff is four processes per fixture, and Git Bash forks slowly.
        # The tab is a real one: `\t` in a sed pattern is a GNU extension, and BSD sed on
        # macOS reads it as a literal "t", so the path was never extracted there.
        if [[ "$_STAMP_TREE" != "$current" ]]; then
            while IFS= read -r tmp; do
                [[ -n "$tmp" ]] && echo "$id: $tmp"
            done < <(diff <(printf '%s\n' "$current") <(printf '%s\n' "$_STAMP_TREE") 2>/dev/null \
                     | sed -n "s/^[<>] \(.*\)${tab}[0-9a-f]*\$/\1/p" | sort -u)
        fi
        _check_stamp_jar || echo "$id: $_STAMP_JAR_PROBLEM"
    done
}

# Test helper for selftest_fixture_stamps: the JAR records a stamp over <listing> carries when
# every resource is recorded with <md5> (or, when <md5> is "", with the tree's own md5), all with
# <info>. Each further "<entry>=<md5>" argument replaces that one entry's md5.
_selftest_stamp_records() {
    local listing="$1" all="$2" info="$3" line md5 kv
    shift 3
    while IFS= read -r line; do
        [[ -n "$line" ]] || continue
        _stamp_entry_of "${line%%$'\t'*}" || continue
        md5="${all:-${line#*$'\t'}}"
        for kv in "$@"; do
            [[ "${kv%%=*}" != "$_STAMP_ENTRY" ]] || md5="${kv#*=}"
        done
        printf '%s%s\t%s\t%s\n' "$_STAMP_JAR_PREFIX" "$_STAMP_ENTRY" "$md5" "$info"
    done <<< "$listing"
}

# Test helper: the stamp <content> for every fixture id under <results>, beside an empty report.
# Builtins only after the first call, which makes the directories.
_selftest_write_stamps() {
    local id
    for id in $FIXTURE_IDS; do
        [[ -d "$1/$id" ]] || mkdir -p "$1/$id"
        [[ -f "$1/$id/$id-report.html" ]] || : > "$1/$id/$id-report.html"
        printf '%s\n' "$2" > "$1/$id/.fixture-stamp"
    done
}

# The stamp's JAR records through the shell's reader and writer, over a scratch results directory
# holding every fixture id (an empty report and a stamp each) and scratch lib/ext directories, so
# it needs no JMeter and no fixture. The reader: records matching the tree are current, silently;
# a stamp whose listing lacks a file the tree now holds (added since) and one listing a file the
# tree lacks (deleted since) are each stale, naming the file, because the listing is compared
# whole (the first is what a per-line reader calls current, as fixture-stamp.js did until F10);
# the same stamps while lib/ext holds two plugin JARs are stale, said once by stale_fixture_reason;
# a JAR whose report.css alone is not the tree's (what the report.js-only record let through), one
# whose report.css and report.js are not (N6), a stamp with no records, one in the first record's
# shape, and one recorded "none" (stamped on two JARs, read after one was moved out by hand) are
# each stale with their sentence; and every stamp recorded "unavailable" is current, noted once.
# The writer: two JARs, no unzip, and a JAR unzip cannot read each give one record per resource in
# their words, and the built plugin JAR gives a well-formed md5 for every entry, read back through
# _read_fixture_stamp, with report.css, report.js and report.html computed again here by another
# path (unzip -p to a file, file_md5). That last case is skipped, saying so, without unzip or the
# built JAR. global-setup.ts reads the same records through test-plans/playwright/fixture-stamp.js,
# pinned by js-unit/fixture-stamp.test.js.
#
# The body is a subshell, so the overrides below (the tree hashed once rather than in every call,
# unzip taken away) and the stamp globals cannot reach the runner that called it.
selftest_fixture_stamps() (
    local tmp out reason rc fail=0 listing line n_res=0 n rec entry rest md5 info bad want base
    local css='static/css/report.css' js='static/js/report.js' html='templates/report.html'
    local other=0123456789abcdef0123456789abcdef
    local note='[FIXTURES] A fixture was stamped where unzip was not on PATH, so which JAR rendered it is not checked.'
    tmp="$(mktemp -d)" || { echo "[SELFTEST] mktemp failed" >&2; return 1; }
    listing="$(_current_source_hashes)"
    while IFS= read -r line; do
        [[ -n "$line" ]] || continue
        _stamp_entry_of "${line%%$'\t'*}" && n_res=$((n_res + 1))
    done <<< "$listing"
    if [[ $n_res -eq 0 || "$listing" != *"$_STAMP_RESOURCES$css"$'\t'* || "$listing" != *"$_STAMP_RESOURCES$js"$'\t'* ]]; then
        echo "[SELFTEST] fixture stamps: the tree could not be hashed, or names no report.css and report.js (no md5 tool?)" >&2
        rm -rf "$tmp"; return 1
    fi
    # Hashing the tree is the slow part on Git Bash, and nothing here is about it. Under its own
    # name, because write_fixture_stamp has a local `listing` that would shadow this one.
    _SELFTEST_LISTING="$listing"
    _current_source_hashes() { printf '%s\n' "$_SELFTEST_LISTING"; }
    mkdir -p "$tmp/results" "$tmp/ext1" "$tmp/ext2" "$tmp/w"
    : > "$tmp/ext1/jmeter-web-insight-report-9.9.9.jar"
    : > "$tmp/ext2/jmeter-web-insight-report-9.9.8.jar"
    : > "$tmp/ext2/jmeter-web-insight-report-9.9.9.jar"
    base="$listing"$'\n'"$(_selftest_stamp_records "$listing" "" jmeter-web-insight-report-9.9.9.jar)"

    # --- the reader ---
    _selftest_write_stamps "$tmp/results" "$base"
    rc=0
    out="$(RESULTS_DIR="$tmp/results"; JMETER_EXT="$tmp/ext1"; fixtures_current 2>&1)" || rc=$?
    if [[ $rc -ne 0 || -n "$out" ]]; then
        echo "[SELFTEST] fixtures_current refused, or spoke over, stamps whose JAR records match the tree (rc=$rc): $out" >&2; fail=1
    fi

    rc=0
    out="$(RESULTS_DIR="$tmp/results"; JMETER_EXT="$tmp/ext2"; fixtures_current 2>&1)" || rc=$?
    reason="$(RESULTS_DIR="$tmp/results"; JMETER_EXT="$tmp/ext2"; stale_fixture_reason 2>&1)"
    if [[ $rc -eq 0 || -n "$out" \
          || "$reason" != *"lib/ext holds 2 plugin JARs (jmeter-web-insight-report-9.9.8.jar, jmeter-web-insight-report-9.9.9.jar)"* ]]; then
        echo "[SELFTEST] two plugin JARs in lib/ext: fixtures_current rc=$rc, said '$out'; stale_fixture_reason said: $reason" >&2; fail=1
    fi

    # A file added under the stamped directories since stamping (the tree holds a source the stamp
    # does not list) and one deleted since (the stamp lists a file the tree lacks). The listing is
    # compared whole; a per-line lookup of the stamp, which fixture-stamp.js used to do, never
    # visits the added file and calls that fixture current.
    local added='' less='' deleted='test-plans/server-metrics/deleted-since-stamping.csv'
    while IFS= read -r line; do
        if [[ "$line" == */src/main/java/* ]]; then
            added="${line%%$'\t'*}"; less="${listing/"$line"$'\n'/}"; break
        fi
    done <<< "$listing"
    if [[ -z "$added" || "$less" == "$listing" ]]; then
        echo "[SELFTEST] fixture stamps: the listing names no Java source to leave out" >&2
        rm -rf "$tmp"; return 1
    fi

    # Seven stamps that must each be refused on their own, and then named together. Each is
    # "<id>|<stamp>|<the sentence stale_fixture_reason must give for it>".
    local -a bad_stamps=(
        "E09|$less"$'\n'"$(_selftest_stamp_records "$less" "" jmeter-web-insight-report-9.9.9.jar)|E09: $added"
        "E08|$listing"$'\n'"$deleted"$'\t'"$other"$'\n'"$(_selftest_stamp_records "$listing" "" jmeter-web-insight-report-9.9.9.jar)|E08: $deleted"
        "E26|$listing"$'\n'"$(_selftest_stamp_records "$listing" "" jmeter-web-insight-report-9.9.8.jar "$css=$other")|E26: rendered by a JAR whose $css is not the tree's (jmeter-web-insight-report-9.9.8.jar) — redeploy and regenerate"
        "E25|$listing"$'\n'"$(_selftest_stamp_records "$listing" "" jmeter-web-insight-report-9.9.7.jar "$css=$other" "$js=$other")|E25: rendered by a JAR whose $css, $js are not the tree's (jmeter-web-insight-report-9.9.7.jar) — redeploy and regenerate"
        "E12|$listing|E12: stamped before stamps recorded which JAR rendered the report — regenerate"
        "E11|$listing"$'\n'"@deployed-report.js"$'\t'"$(file_md5 "$PROJECT_DIR/jmeter-web-insight-report$_STAMP_RESOURCES$js")"$'\t'"jmeter-web-insight-report-9.9.9.jar|E11: stamped before stamps recorded which JAR rendered the report — regenerate"
        "E10|$listing"$'\n'"$(_selftest_stamp_records "$listing" none "lib/ext held 2 plugin JARs (a.jar, b.jar)")|E10: stamped when lib/ext held 2 plugin JARs (a.jar, b.jar), so what rendered it is unknown — redeploy and regenerate"
    )
    local bad_case bad_id bad_stamp
    for bad_case in "${bad_stamps[@]}"; do
        bad_id="${bad_case%%|*}"; bad_stamp="${bad_case#*|}"; bad_stamp="${bad_stamp%|*}"
        _selftest_write_stamps "$tmp/results" "$base"
        printf '%s\n' "$bad_stamp" > "$tmp/results/$bad_id/.fixture-stamp"
        rc=0
        out="$(RESULTS_DIR="$tmp/results"; JMETER_EXT="$tmp/ext1"; fixtures_current 2>&1)" || rc=$?
        if [[ $rc -eq 0 || -n "$out" ]]; then
            echo "[SELFTEST] fixtures_current called this stamp current, or spoke (rc=$rc, '$out'): ${bad_case##*|}" >&2; fail=1
        fi
    done
    _selftest_write_stamps "$tmp/results" "$base"
    for bad_case in "${bad_stamps[@]}"; do
        bad_id="${bad_case%%|*}"; bad_stamp="${bad_case#*|}"; bad_stamp="${bad_stamp%|*}"
        printf '%s\n' "$bad_stamp" > "$tmp/results/$bad_id/.fixture-stamp"
    done
    reason="$(RESULTS_DIR="$tmp/results"; JMETER_EXT="$tmp/ext1"; stale_fixture_reason 2>&1)"
    for bad_case in "${bad_stamps[@]}"; do
        if [[ "$reason" != *"${bad_case##*|}"* ]]; then
            echo "[SELFTEST] stale_fixture_reason did not say: ${bad_case##*|}" >&2
            printf '%s\n' "$reason" | sed 's/^/    /' >&2; fail=1
        fi
    done

    _selftest_write_stamps "$tmp/results" "$listing"$'\n'"$(_selftest_stamp_records "$listing" unavailable "unzip was not on PATH")"
    rc=0
    out="$(unset _WIR_UNZIP_NOTED; RESULTS_DIR="$tmp/results"; JMETER_EXT="$tmp/ext1"; fixtures_current 2>&1)" || rc=$?
    if [[ $rc -ne 0 || "$out" != "$note" ]]; then
        echo "[SELFTEST] stamps written without unzip were not current with the note said once (rc=$rc): $out" >&2; fail=1
    fi

    # --- the writer ---
    (JMETER_EXT="$tmp/ext2"; write_fixture_stamp "$tmp/w")
    want="$listing"$'\n'"$(_selftest_stamp_records "$listing" none \
        "lib/ext held 2 plugin JARs (jmeter-web-insight-report-9.9.8.jar, jmeter-web-insight-report-9.9.9.jar)")"
    if [[ "$(<"$tmp/w/.fixture-stamp")" != "$want" ]]; then
        echo "[SELFTEST] write_fixture_stamp did not record every resource as none on two JARs:" >&2
        sed 's/^/    /' "$tmp/w/.fixture-stamp" | tail -n 3 >&2; fail=1
    fi

    (_have_unzip() { return 1; }; JMETER_EXT="$tmp/ext1"; write_fixture_stamp "$tmp/w")
    want="$listing"$'\n'"$(_selftest_stamp_records "$listing" unavailable "unzip was not on PATH")"
    if [[ "$(<"$tmp/w/.fixture-stamp")" != "$want" ]]; then
        echo "[SELFTEST] write_fixture_stamp did not record every resource as unavailable without unzip:" >&2
        sed 's/^/    /' "$tmp/w/.fixture-stamp" | tail -n 3 >&2; fail=1
    fi

    if _have_unzip; then
        # ext1's JAR is an empty file, so unzip extracts nothing from it.
        (JMETER_EXT="$tmp/ext1"; write_fixture_stamp "$tmp/w")
        _read_fixture_stamp "$tmp/w/.fixture-stamp"
        n=0; bad=""
        while IFS= read -r rec; do
            [[ -n "$rec" ]] || continue
            n=$((n + 1)); entry="${rec%%$'\t'*}"
            [[ "$rec" == "$entry"$'\t'none$'\t'"jmeter-web-insight-report-9.9.9.jar had no $entry" ]] || bad="$bad $entry"
        done <<< "$_STAMP_JAR"
        if [[ "$_STAMP_TREE" != "$listing" || $n -ne $n_res || -n "$bad" ]]; then
            echo "[SELFTEST] write_fixture_stamp did not record a JAR it could not read as none, per entry ($n of $n_res records; wrong:$bad)" >&2; fail=1
        fi
    fi

    if _have_unzip && [[ -f "$PLUGIN_JAR" ]]; then
        mkdir -p "$tmp/ext3"
        cp "$PLUGIN_JAR" "$tmp/ext3/"
        (JMETER_EXT="$tmp/ext3"; write_fixture_stamp "$tmp/w")
        _read_fixture_stamp "$tmp/w/.fixture-stamp"
        n=0; bad=""
        while IFS= read -r rec; do
            [[ -n "$rec" ]] || continue
            n=$((n + 1)); entry="${rec%%$'\t'*}"; rest="${rec#*$'\t'}"
            md5="${rest%%$'\t'*}"; info="${rest#*$'\t'}"
            if [[ ! "$md5" =~ ^[0-9a-f]{32}$ || "$info" != "${PLUGIN_JAR##*/}" ]]; then
                bad="$bad $entry"
            elif [[ "$entry" == "$css" || "$entry" == "$js" || "$entry" == "$html" ]]; then
                # Not every entry: an md5sum is a fork, and three in different places of the
                # listing are enough to catch a record given another entry's md5.
                unzip -p "$PLUGIN_JAR" "$entry" > "$tmp/entry" 2>/dev/null
                [[ "$md5" == "$(file_md5 "$tmp/entry")" ]] || bad="$bad $entry"
            fi
        done <<< "$_STAMP_JAR"
        if [[ "$_STAMP_TREE" != "$listing" || $n -ne $n_res || -n "$bad" ]]; then
            echo "[SELFTEST] write_fixture_stamp did not record the tree listing and each resource's md5 in the JAR ($n of $n_res records; wrong:$bad)" >&2
            fail=1
        fi
    else
        echo "[SELFTEST] fixture stamps: the writer's md5 case skipped (needs unzip and the built plugin JAR)"
    fi
    rm -rf "$tmp"
    [[ $fail -eq 0 ]] || return 1
    echo "[SELFTEST] fixture stamps refuse a fixture another JAR rendered"
)

# ---- test layers -------------------------------------------------------------
# Twenty seconds of real JMeter. Unit tests cannot catch a plugin that fails to load in
# lib/ext — a bad shade, a missing classpath resource, a JMeter API change — and that
# failure is invisible until something actually runs the listener.
#
# The report is DELETED first. Asserting "E24-report.html exists" after the run looks
# like proof and is not: the file survives from the previous cycle, so a JMeter that
# never started still leaves a passing assertion behind. Both callers used to do exactly
# that and also discarded the smoke's exit status, which is two independent ways for this
# check to report success without having checked anything.
run_live_smoke() {
    local report="$RESULTS_DIR/E24/E24-report.html"
    rm -f "$report"
    SKIP_BUILD=1 bash "$TEST_PLANS/scripts/run-all-tests.sh" --suite smoke >/dev/null 2>&1
    file_exists "$report"
}

# Java unit tests. Fast enough (~5s for 224) that every tier runs them.
run_java_tests() {
    echo "[JAVA] Running unit tests..."
    ( cd "$PROJECT_DIR" && mvn test -q ) || return 1
    echo "[JAVA] PASS"
}

# The pure half of report.js, in node, with no browser and no fixture.
#
# Roughly 2,000 lines inside WIR.asserts are pure — parsers, formatters, comparators, the
# evaluator core — and until this existed the cheapest test for any of them was to boot
# Chromium against a 1.7 MB generated report. So in practice they had none, and the defects
# that shipped were exactly the ones a pure-function test catches instantly.
#
# Runs in about a tenth of a second, so it sits beside the Java unit tests in every tier
# rather than behind a flag. No bundler and no module split: report.js already loads under
# two stubs, which is the whole reason this was cheap. See test-plans/js-unit/harness.js.
run_js_unit_tests() {
    local dir="$PROJECT_DIR/test-plans/js-unit"
    if [[ ! -d "$dir" ]]; then
        echo "[JS] No js-unit directory — skipping" >&2
        return 0
    fi
    if ! command -v node >/dev/null 2>&1; then
        # Loud rather than a silent skip: a check that passes when it cannot run is the
        # failure mode verify_shaded_jar already had, and node is required for Playwright
        # anyway, so its absence is a broken environment rather than a supported one.
        echo "[JS] FAIL: node is not on PATH, so the JS unit tests could not run" >&2
        return 1
    fi
    echo "[JS] Running report.js unit tests..."
    local f fail=0
    for f in "$dir"/*.test.js; do
        [[ -e "$f" ]] || continue
        node "$f" || fail=1
    done
    [[ $fail -eq 0 ]] || return 1
    echo "[JS] PASS"
}

# Playwright, invoked through node rather than `npx playwright` or the .bin shim:
# under Git Bash both fail — npx does not resolve the local install and .bin/playwright
# is a POSIX shim node tries to require as a module. cli.js works on every platform.
# How many browser tests exist right now.
#
# The runners used to say "Playwright (279 tests)" in a hardcoded label, which was
# true until someone added a spec and then quietly wrong forever — a count nobody
# has checked is worse than no count, because it reads like a verified figure.
playwright_test_count() {
    local pw_dir="$TEST_PLANS/playwright"
    local cli="$pw_dir/node_modules/@playwright/test/cli.js"
    [[ -f "$cli" ]] || { echo "?"; return 0; }
    local total
    total="$( cd "$pw_dir" \
        && WIR_SKIP_FIXTURE_CHECK=1 node "$cli" test --list 2>/dev/null \
        | awk '/^Total: [0-9]+ tests/ { print $2 }' | tail -1 )"
    echo "${total:-?}"
}

run_playwright() {
    local pw_dir="$TEST_PLANS/playwright"
    local cli="$pw_dir/node_modules/@playwright/test/cli.js"

    if ! command -v node >/dev/null 2>&1; then
        echo "[PW] Node.js not found on PATH — cannot run browser tests." >&2
        return 1
    fi
    if [[ ! -f "$cli" ]]; then
        echo "[PW] Playwright is not installed. Run: cd test-plans/playwright && npm ci" >&2
        return 1
    fi
    ( cd "$pw_dir" && node "$cli" test "$@" )
}

# ---- assertions -------------------------------------------------------------
file_exists()   { [[ -f "$1" ]]; }
file_absent()   { [[ ! -e "$1" ]]; }
file_size_gt()  { [[ -f "$1" ]] && [[ "$(stat_size "$1")" -gt "$2" ]]; }
assert_contains() { [[ -f "$1" ]] && grep -qF -- "$2" "$1" 2>/dev/null; }

# assert_log_contains <runtime-log> <literal text>
#
# For JMeter's own log (see WIR_RUNTIME_LOG on run_jmeter), which is where every plugin
# WARN and ERROR lands and where nothing else can see them. Separate from
# assert_contains only by intent — a caller reading these two names should not have to
# remember which file holds which half of the output.
assert_log_contains() { [[ -f "$1" ]] && grep -qF -- "$2" "$1" 2>/dev/null; }
assert_log_lacks()    { ! assert_log_contains "$@"; }

# assert_exit_code <actual> <expected>
#
# run_jmeter ends by exec'ing JMeter, so its status propagates; the suites set
# `set -uo pipefail` WITHOUT -e, so `run_jmeter …; rc=$?` is safe to capture.
assert_exit_code() { [[ "$1" == "$2" ]]; }

log_has_stacktrace() {
    grep -qE 'at [a-zA-Z0-9_.]+\(.+\.java:[0-9]+\)' "$1" 2>/dev/null
}

# report_gate_verdict <report.html>
#
# The client gate's verdict for a report, from the CI/CD JSON written beside it (the .json name is
# the HTML's, as the generator derives it): PASS, WARN or FAIL on stdout, status 0. Status 1 and
# nothing on stdout when the run evaluated no client gate - no .json, one that does not parse,
# assertsEvaluated false, no `gate` block (a run with server asserts only), or a gate whose status
# is NOT_EVALUATED because every rule it declared was refused or matched nothing.
#
# The JSON and never the page. refresh-report-example.sh read the first "ASSERTS [A-Z]*" in the
# HTML, and since 5874e10 put "ASSERTS NOT EVALUATED" into a report.css comment, which every report
# inlines above its header, that was the match: "ASSERTS NOT" whatever the gate said, and its
# no-verdict guard, `grep -q ASSERTS`, passed on a report with no gate at all (S5). node rather than
# jq, which is optional here (verify_jq_idioms skips without it); node is required.
report_gate_verdict() {
    local json="${1%.html}.json"
    [[ -f "$json" ]] || return 1
    if ! command -v node >/dev/null 2>&1; then
        echo "report_gate_verdict: node is not on PATH, so no verdict could be read" >&2
        return 1
    fi
    node -e '
        let d;
        try { d = JSON.parse(require("fs").readFileSync(process.argv[1], "utf8")); } catch (e) { process.exit(1); }
        const s = d && d.assertsEvaluated === true && d.gate ? d.gate.status : null;
        if (s !== "PASS" && s !== "WARN" && s !== "FAIL") process.exit(1);
        console.log(s);
    ' "$json"
}

# report_server_verdicts <report.html> <annotations.json>
#
# The server side of a report, from the same CI/CD JSON as report_gate_verdict: status 0 and a
# summary on stdout ("server FAIL", then a line per component and per assert) when serverStatus,
# every component the annotations file declares and every server assert carry a verdict - PASS,
# WARN or FAIL. Status 1, nothing on stdout and each problem on stderr otherwise: no .json, one
# that does not parse, no serverStatus (the interim report, or a refused payload), a declared
# component missing from serverComponents[] or one that judged nothing, an assert NOT_JUDGED, no
# assert at all, or an annotations file that declares no component. refresh-report-example.sh
# publishes nothing that fails it: the example's Server tab is there to show the feature working.
report_server_verdicts() {
    local json="${1%.html}.json"
    if [[ ! -f "$json" ]]; then
        echo "report_server_verdicts: there is no $json" >&2
        return 1
    fi
    if ! command -v node >/dev/null 2>&1; then
        echo "report_server_verdicts: node is not on PATH, so no verdict could be read" >&2
        return 1
    fi
    node -e '
        const fs = require("fs");
        const read = f => {
            try { return JSON.parse(fs.readFileSync(f, "utf8")); }
            catch (e) { console.error("report_server_verdicts: cannot read " + f + " - " + e.message); process.exit(1); }
        };
        const d = read(process.argv[1]) || {};
        const declared = Object.keys(((read(process.argv[2]) || {}).serverMetrics || {}).components || {});
        const verdicts = ["PASS", "WARN", "FAIL"];
        const components = Array.isArray(d.serverComponents) ? d.serverComponents : [];
        const asserts = Array.isArray(d.serverAsserts) ? d.serverAsserts : [];
        const problems = [];
        if (!verdicts.includes(d.serverStatus)) problems.push("serverStatus is " + (d.serverStatus || "absent"));
        if (!declared.length) problems.push("the annotations file declares no serverMetrics.components");
        for (const id of declared) {
            const c = components.find(x => x && x.id === id);
            if (!c) problems.push("component " + id + " is not in serverComponents[]");
            else if (!verdicts.includes(c.status)) problems.push("component " + id + " is " + c.status);
        }
        if (!asserts.length) problems.push("serverAsserts[] is empty");
        for (const a of asserts) {
            if (!verdicts.includes(a.status)) {
                problems.push("server assert " + a.id + " is " + a.status + (a.reason ? " - " + a.reason : ""));
            }
        }
        if (problems.length) {
            for (const p of problems) console.error("report_server_verdicts: " + p);
            process.exit(1);
        }
        console.log("server " + d.serverStatus);
        for (const c of components) console.log("  component " + c.id + " " + c.status);
        for (const a of asserts) console.log("    assert " + a.id + " (" + a.component + ") " + a.status + ", actual " + a.actual);
    ' "$json" "$2"
}

# report_local_path <file> <dir>...
#
# Status 0, and the spelling found on stdout, when <file> names any <dir>; status 1 when it names
# none. A directory is matched by its last two components joined by '/', '\/' (a JSON-escaped
# slash), '\' or '\\' (a JSON-escaped backslash), so Git Bash's /d/x/y, Java's D:/x/y and a JSON
# "D:\\x\\y" are one directory. A <dir> with fewer than two components, or empty, is skipped: a
# single name would match too much. refresh-report-example.sh refuses to publish a page naming the
# checkout, the results directory, JMeter or $HOME: wir-collect records the --csv path it was given
# as the source's resolvedUrl and the Server tab shows it, so an absolute one put the generating
# machine's path into reportExample/.
report_local_path() {
    local file="$1" dir parent name sep
    shift
    [[ -f "$file" ]] || return 1
    for dir in "$@"; do
        dir="${dir//\\//}"
        dir="${dir%/}"
        name="${dir##*/}"
        parent="${dir%/*}"
        [[ -n "$name" && "$parent" != "$dir" ]] || continue
        parent="${parent##*/}"
        [[ -n "$parent" ]] || continue
        for sep in '/' '\/' '\' '\\'; do
            if grep -qF -- "$parent$sep$name" "$file"; then
                printf '%s\n' "$parent$sep$name"
                return 0
            fi
        done
    done
    return 1
}

# ---- result tracking --------------------------------------------------------
PASS=0
FAIL=0
TOTAL=0
RESULTS=()

record() {
    local id="$1" name="$2" status="$3" note="${4:-}"
    TOTAL=$((TOTAL + 1))
    if [[ "$status" == "PASS" ]]; then
        PASS=$((PASS + 1))
    else
        FAIL=$((FAIL + 1))
    fi
    RESULTS+=("[$status] $id: $name${note:+ -- $note}")
    echo "  [$status] $id: $name"
    [[ -n "$note" ]] && echo "          $note"
    return 0
}

# Append a note to a variable, semicolon-separated (idiom from the old script).
append_note() {
    local existing="$1" addition="$2"
    printf '%s\n' "${existing:+$existing; }$addition"
}

print_banner() {
    echo "==============================================================="
    echo "  $1"
    echo "  $(date)"
    echo "==============================================================="
    echo ""
}

print_summary() {
    local title="${1:-TEST SUMMARY}"
    echo ""
    echo "==============================================================="
    echo "  $title"
    echo "==============================================================="
    echo ""
    local r
    for r in ${RESULTS[@]+"${RESULTS[@]}"}; do
        echo "  $r"
    done
    echo ""
    # JMETER_HOME is unset for container runs, where JMeter lives in the image.
    if [[ -n "${JMETER_HOME:-}" ]]; then
        echo "  Plugin: $PLUGIN_VERSION   JMeter: $JMETER_HOME"
    else
        echo "  Plugin: $PLUGIN_VERSION"
    fi
    echo "  TOTAL: $TOTAL    PASS: $PASS    FAIL: $FAIL"
    echo "==============================================================="
}

# Four grep-shaped rules over report.js, each of which would have caught a defect that
# actually shipped. They are here rather than in the JS unit layer because they are claims
# about the SOURCE — "this pattern appears nowhere" is not a thing a function can be asked.
#
# The bar for adding one, the same bar the other static checks meet: the failure has to be
# silent in the wild. A missing border is not; a handler that executes a rule id is.
verify_report_js_rules() {
    # The override exists for selftest_report_js_rules, which feeds the rules a known
    # violation and demands they refuse it. Every real caller passes nothing.
    local js="${1:-$PROJECT_DIR/jmeter-web-insight-report/src/main/resources/static/js/report.js}"
    [[ -f "$js" ]] || { echo "[JSRULES] report.js not found: $js" >&2; return 1; }
    local fail=0 hits

    # Every grep below is -a. A raw NUL byte makes GNU grep call the file binary: from there it
    # prints "Binary file ... matches" (grep 3.0-3.4, on stdout) or nothing at all (3.5+, on
    # stderr) instead of the matching lines. Rules 2-4 then refuse noisily on the message itself;
    # rule 1 pipes it into a second grep for `esc(`, finds none, and passes -- which is how two
    # NULs pasted into report.js hid four Server-tab handlers from it (A1-5). verify_no_nul_bytes
    # refuses the byte in the first place; -a means a rule never depends on that having run.

    # 1. Every inline handler interpolates through jsArg. esc() escapes for the HTML attribute
    #    parser and leaves the single quote alone, so `x');alert(1)//` in a rule id became a live
    #    handler on the broken-rule banner. Both judging modules are covered (D20): the server
    #    tab's ids, queries and gate labels come out of a file this page did not write, exactly
    #    like a rule id. NOTE the BRE escaping -- \(a\|b\) -- because this grep has no -E, and
    #    writing (asserts|serverKpis) here would match a literal parenthesis and silently stop
    #    matching anything, which is a guard that passes because it can no longer fail.
    #    -i: an HTML attribute name is case-insensitive, so `onClick=` is the same handler.
    hits=$(grep -ani 'onclick="WIR\.\(asserts\|serverKpis\)\.' "$js" | grep -a 'esc(' || true)
    if [[ -n "$hits" ]]; then
        echo "[JSRULES] an inline WIR handler interpolates through esc(), not jsArg():" >&2
        echo "$hits" >&2
        fail=1
    fi

    # 2. The chip class is assembled in exactly one place. Two sites built it by hand with a
    #    bare toLowerCase(), so 'NO VALUE' became `wir-chip-no value` — two classes, neither
    #    in the stylesheet, on every run carrying a rule the run could not measure.
    #    Both quote styles, matching the JS-unit guard in asserts-pure.test.js: this rule
    #    once matched only 'wir-chip-, so a double-quoted hand-built class passed the one
    #    tier that runs on machines without node.
    hits=$(grep -anE "wir-chip-['\"]" "$js" | grep -av "chipToken(" || true)
    if [[ -n "$hits" ]]; then
        echo "[JSRULES] a wir-chip- class built without chipToken():" >&2
        echo "$hits" >&2
        fail=1
    fi

    # 3. Neither re-rendered table goes through the generic table helpers. Those walk tbody tr
    #    and set style.display, which is right for the Thymeleaf-rendered tables and wrong for
    #    these two: render() rebuilds them, so a display filter would be erased by the next
    #    render and would orphan the detail rows belonging to the row above them. Both ids are
    #    spelled here EXACTLY as the DOM spells them -- this is a literal match, so a renamed
    #    table id silently drops out of the guard.
    hits=$(grep -an "\(filterGenericTable\|sortGenericTable\)([^)]*\(assertsTable\|serverKpiTable\)" "$js" || true)
    if [[ -n "$hits" ]]; then
        echo "[JSRULES] a re-rendered table was passed to a generic table helper:" >&2
        echo "$hits" >&2
        fail=1
    fi

    # 4. The server tab carries no oninput/onchange handler at all. The rules above and the
    #    structural XSS sweep read onclick, and the editor's form used to hang a two-argument
    #    `set('field', this.value)` off every control -- the one handler shape nothing swept, in
    #    the module whose comment promised there was none (R3-17). Its controls now report through
    #    one delegated listener; a handler written back into the module is refused here, whatever
    #    its arguments, so the sweep's blind spot cannot quietly reopen. Any event attribute but
    #    onclick (M7d X, review:D): oninput and onchange were named, and onkeyup or onblur passed
    #    both this grep and the browser sweep. -o reports every handler on a line on its own, so
    #    an onclick beside an onkeyup cannot hide it. -i on both greps: an HTML attribute name is
    #    case-insensitive, so `onKeyUp=` is an onkeyup handler and passed a lower-case pattern
    #    (review low, M7d X), while `onClick=` is still the sanctioned onclick.
    hits=$(grep -anoiE 'on[a-z]+="WIR\.serverKpis\.' "$js" | grep -vi ':onclick="' || true)
    if [[ -n "$hits" ]]; then
        echo "[JSRULES] an event handler other than onclick in WIR.serverKpis (its editor binds a delegated listener):" >&2
        echo "$hits" >&2
        fail=1
    fi

    # A FIFTH rule was written and removed: "rule.results is read only inside matchesOf".
    # The invariant is real and CLAUDE.md states it — the array is capped at
    # MAX_MATCHES_PER_RULE keeping the worst by severity, so anything keyed on it answers
    # differently before and after an unrelated edit — but it is semantic, not syntactic. The
    # detail rows legitimately render exactly what the payload carries, and a grep cannot tell
    # that from a verdict being computed. It needed a six-line allow-list to go green, which is
    # the shape of a check that will be silenced rather than obeyed the first time it is wrong.
    # Left to code review and to the comment on attainmentOf.

    [[ $fail -eq 0 ]] || return 1
    echo "[JSRULES] report.js source rules hold"
}

# The source rules are greps, and a grep guards nothing when its pattern is narrower than
# the offence: rule 2 shipped matching only the single-quoted form while the JS-unit guard
# matched both quote styles, so a double-quoted hand-built chip class would have passed the
# one tier that runs on machines without node. Feed the rules one violation per quote style
# and one sanctioned line; the check must refuse the first two and accept the third.
selftest_report_js_rules() {
    local tmp fail=0
    tmp="$(mktemp)" || { echo "[SELFTEST] mktemp failed" >&2; return 1; }
    printf '%s\n' "el.className = 'wir-chip-' + s.toLowerCase();" > "$tmp"
    if verify_report_js_rules "$tmp" >/dev/null 2>&1; then
        echo "[SELFTEST] rule 2 missed a single-quoted hand-built chip class" >&2; fail=1
    fi
    printf '%s\n' 'el.className = "wir-chip-" + s.toLowerCase();' > "$tmp"
    if verify_report_js_rules "$tmp" >/dev/null 2>&1; then
        echo "[SELFTEST] rule 2 missed a double-quoted hand-built chip class" >&2; fail=1
    fi
    printf '%s\n' "el.className = 'wir-chip wir-chip-' + chipToken(s);" > "$tmp"
    if ! verify_report_js_rules "$tmp" >/dev/null 2>&1; then
        echo "[SELFTEST] rule 2 flagged a chipToken()-routed chip class" >&2; fail=1
    fi
    # Rule 4, both event names: the exact shape the server editor shipped with (R3-17).
    printf '%s\n' "+ 'oninput=\"WIR.serverKpis.set(' + jsArg(field) + ', this.value)\"/>';" > "$tmp"
    if verify_report_js_rules "$tmp" >/dev/null 2>&1; then
        echo "[SELFTEST] rule 4 missed an oninput handler in the server module" >&2; fail=1
    fi
    printf '%s\n' "+ 'onchange=\"WIR.serverKpis.set(' + jsArg(field) + ', this.value)\">';" > "$tmp"
    if verify_report_js_rules "$tmp" >/dev/null 2>&1; then
        echo "[SELFTEST] rule 4 missed an onchange handler in the server module" >&2; fail=1
    fi
    # ...and any other event name, including one sharing a line with a sanctioned onclick.
    printf '%s\n' "+ '<button onclick=\"WIR.serverKpis.edit(' + jsArg(k) + ')\" onkeyup=\"WIR.serverKpis.set(' + jsArg(f) + ', this.value)\">';" > "$tmp"
    if verify_report_js_rules "$tmp" >/dev/null 2>&1; then
        echo "[SELFTEST] rule 4 missed an onkeyup handler beside an onclick in the server module" >&2; fail=1
    fi
    printf '%s\n' "+ '<button onclick=\"WIR.serverKpis.edit(' + jsArg(k) + ')\">';" > "$tmp"
    if ! verify_report_js_rules "$tmp" >/dev/null 2>&1; then
        echo "[SELFTEST] rule 4 flagged a sanctioned onclick in the server module" >&2; fail=1
    fi
    # ...and an attribute name in mixed case, which HTML reads as the same attribute.
    printf '%s\n' "+ '<input onKeyUp=\"WIR.serverKpis.set(' + jsArg(f) + ', this.value)\">';" > "$tmp"
    if verify_report_js_rules "$tmp" >/dev/null 2>&1; then
        echo "[SELFTEST] rule 4 missed a mixed-case onKeyUp handler in the server module" >&2; fail=1
    fi
    printf '%s\n' "+ '<button onClick=\"WIR.serverKpis.edit(' + jsArg(k) + ')\">';" > "$tmp"
    if ! verify_report_js_rules "$tmp" >/dev/null 2>&1; then
        echo "[SELFTEST] rule 4 flagged a sanctioned mixed-case onClick in the server module" >&2; fail=1
    fi
    printf '%s\n' "+ '\" onClick=\"WIR.asserts.edit(\\'' + esc(rule.id) + '\\')\">';" > "$tmp"
    if verify_report_js_rules "$tmp" >/dev/null 2>&1; then
        echo "[SELFTEST] rule 1 missed an esc()-interpolated mixed-case onClick handler" >&2; fail=1
    fi
    # Rule 1, in BOTH modules its alternation names (L3-14). The widened pattern is a BRE
    # \(asserts\|serverKpis\), and the way it fails is silent: spelled as an ERE it matches a
    # literal parenthesis, finds nothing, and passes every file -- so each half is fed a handler
    # interpolated through esc() and must refuse it, and the jsArg() form must pass.
    local esc_server="+ '\" onclick=\"WIR.serverKpis.edit(\\'' + esc(row.key) + '\\')\">';"
    printf '%s\n' "$esc_server" > "$tmp"
    if verify_report_js_rules "$tmp" >/dev/null 2>&1; then
        echo "[SELFTEST] rule 1 missed an esc()-interpolated WIR.serverKpis handler" >&2; fail=1
    fi
    printf '%s\n' "+ '\" onclick=\"WIR.asserts.edit(\\'' + esc(rule.id) + '\\')\">';" > "$tmp"
    if verify_report_js_rules "$tmp" >/dev/null 2>&1; then
        echo "[SELFTEST] rule 1 missed an esc()-interpolated WIR.asserts handler" >&2; fail=1
    fi
    printf '%s\n' "+ '\" onclick=\"WIR.serverKpis.edit(' + jsArg(row.key) + ')\">';" > "$tmp"
    if ! verify_report_js_rules "$tmp" >/dev/null 2>&1; then
        echo "[SELFTEST] rule 1 flagged a jsArg()-routed WIR.serverKpis handler" >&2; fail=1
    fi
    # ...and a raw NUL above the violation must not blind it: without -a, grep reports the file
    # as binary instead of printing the line, and rule 1's second grep finds no esc() to refuse.
    printf 'var dot = "\0";\n%s\n' "$esc_server" > "$tmp"
    if verify_report_js_rules "$tmp" >/dev/null 2>&1; then
        echo "[SELFTEST] rule 1 was blinded by a NUL byte above the violation (grep without -a)" >&2; fail=1
    fi
    rm -f "$tmp"
    [[ $fail -eq 0 ]] || return 1
    echo "[SELFTEST] report.js source rules refuse what they claim to"
}

# --accept must carry a failed regeneration in its exit code. It did not: the accept branch
# exited 0 unconditionally, so a case that crashed printed its error to stderr, the summary
# still said "accepted N baseline(s)", and a partial baseline set read as a clean accept to
# anything watching $?. Run the real runner against a stub JMeter that writes nothing and
# demand a non-zero exit — and demand the artifact-loop error alongside it, so a require_*
# refusing to start cannot satisfy this by failing for an unrelated reason.
#
# The stub means no JMeter install is needed, which is what lets this run in CI. It does
# need the built plugin JAR (run-goldens.sh asserts it), so it sits after the build stage.
selftest_goldens_accept() {
    local stub log rc
    stub="$(mktemp -d)" || { echo "[SELFTEST] mktemp failed" >&2; return 1; }
    log="$stub/run.log"
    mkdir -p "$stub/bin" "$stub/lib/ext"
    printf '%s\n' '#!/usr/bin/env bash' 'exit 1' > "$stub/bin/jmeter"
    chmod +x "$stub/bin/jmeter"
    : > "$stub/lib/ext/$(basename "$PLUGIN_JAR")"
    # `|| rc=$?`, not a bare call with $? read after: the runner is EXPECTED to exit
    # non-zero here, and under a caller running `set -e` (the CI step does) an unguarded
    # failing command kills the shell at this line — which is exactly how this selftest's
    # own first CI run died: exit 1, nothing printed. The suites run without -e, so only
    # CI could see it.
    rc=0
    JMETER_HOME="$stub" "$PROJECT_DIR/test-plans/scripts/run-goldens.sh" --accept > "$log" 2>&1 || rc=$?
    if [[ $rc -eq 0 ]] || ! grep -q "no .json was written" "$log"; then
        echo "[SELFTEST] run-goldens.sh --accept swallowed a failed regeneration (rc=$rc):" >&2
        sed 's/^/    /' "$log" >&2
        rm -rf "$stub"
        return 1
    fi
    rm -rf "$stub"
    echo "[SELFTEST] goldens --accept propagates failure"
}

# The example's verdict is the run's gate, read from its CI/CD JSON (report_gate_verdict), and the
# example script refuses a run that judged nothing. Neither was true: it grepped the page for the
# first "ASSERTS [A-Z]*", which since 5874e10 is a report.css comment reading "ASSERTS NOT
# EVALUATED", so it printed "ASSERTS NOT" for every verdict and its guard could never fire (S5).
#
# So the HTML here carries that comment above a PASS badge - the trap, checked to still fool the
# old grep, so this cannot go on passing over a page that no longer reproduces it - and the JSON
# beside it says each verdict in turn, which must come back as that word. Then every run with no
# client gate must be refused with nothing on stdout: no gate (E02's shape), a gate that judged
# nothing, server asserts only, a .json that is not JSON, and none at all. Needs node, as the
# js-unit tier does; every expected failure is taken with `|| rc=$?`, because CI calls this under
# errexit.
#
# The example publishes a -g -o report that judges server asserts over demo data, so the same holds
# for its server side (report_server_verdicts), each way of not showing a verdict refused in turn,
# and for the local paths a published page must not name (report_local_path): the payload once
# carried the generating machine's D:/... path to the CSV into reportExample/. The server check was
# first written inline in the script, which is S5's shape - a guard nothing tests - so both live here.
selftest_report_gate_verdict() {
    local tmp html json out rc verdict fail=0
    tmp="$(mktemp -d)" || { echo "[SELFTEST] mktemp failed" >&2; return 1; }
    html="$tmp/web-insight-report.html"
    json="$tmp/web-insight-report.json"
    printf '%s\n' '<style>/* a gate with nothing judged is the neutral badge - ASSERTS NOT EVALUATED */</style>' \
        '<span class="badge badge-pass">ASSERTS PASS</span>' > "$html"
    if [[ "$(grep -o 'ASSERTS [A-Z]*' "$html" | head -1 || true)" != "ASSERTS NOT" ]]; then
        echo "[SELFTEST] this selftest's page no longer reproduces S5: the old grep did not read 'ASSERTS NOT' off it" >&2
        fail=1
    fi
    for verdict in PASS WARN FAIL; do
        printf '%s\n' "{\"status\": \"$verdict\", \"assertsEvaluated\": true, \"gate\": {\"status\": \"$verdict\", \"declared\": 2, \"evaluated\": 2}}" > "$json"
        rc=0
        out="$(report_gate_verdict "$html")" || rc=$?
        if [[ $rc -ne 0 || "$out" != "$verdict" ]]; then
            echo "[SELFTEST] report_gate_verdict read '$out' (rc=$rc) off a $verdict gate under the comment" >&2
            fail=1
        fi
    done
    local -a refused=(
        'no gate|{"status": "UNKNOWN", "assertsEvaluated": false}'
        'a gate that judged nothing|{"status": "UNKNOWN", "assertsEvaluated": true, "gate": {"status": "NOT_EVALUATED", "declared": 2, "evaluated": 0}}'
        'server asserts only|{"status": "UNKNOWN", "assertsEvaluated": true, "serverStatus": "PASS"}'
        'a .json that is not JSON|not json'
    )
    local entry
    for entry in "${refused[@]}"; do
        printf '%s\n' "${entry#*|}" > "$json"
        rc=0
        out="$(report_gate_verdict "$html")" || rc=$?
        if [[ $rc -eq 0 || -n "$out" ]]; then
            echo "[SELFTEST] report_gate_verdict read '$out' (rc=$rc) off ${entry%%|*}" >&2
            fail=1
        fi
    done
    rm -f "$json"
    rc=0
    out="$(report_gate_verdict "$html")" || rc=$?
    if [[ $rc -eq 0 || -n "$out" ]]; then
        echo "[SELFTEST] report_gate_verdict read '$out' (rc=$rc) with no .json beside the page" >&2
        fail=1
    fi

    # The server side (report_server_verdicts): a verdict for serverStatus, every declared component
    # and every assert, or a refusal with nothing on stdout. Each refused JSON breaks one of those.
    local annotations="$tmp/annotations.json"
    printf '%s\n' '{"serverMetrics": {"components": {"db": {"type": "postgres"}, "bus": {"type": "kafka"}}}}' > "$annotations"
    local comps='"serverComponents": [{"id": "db", "status": "PASS"}, {"id": "bus", "status": "FAIL"}]'
    local asserts='"serverAsserts": [{"id": "a", "component": "db", "status": "PASS", "actual": 1}, {"id": "b", "component": "bus", "status": "WARN", "actual": 2}, {"id": "c", "component": "bus", "status": "FAIL", "actual": 3}]'
    printf '%s\n' "{\"serverStatus\": \"FAIL\", $comps, $asserts}" > "$json"
    rc=0
    out="$(report_server_verdicts "$html" "$annotations" 2>/dev/null)" || rc=$?
    if [[ $rc -ne 0 || "$(printf '%s\n' "$out" | head -1)" != "server FAIL" ]]; then
        echo "[SELFTEST] report_server_verdicts refused (rc=$rc) a server side with a verdict everywhere: '$out'" >&2
        fail=1
    fi
    local -a server_refused=(
        "the interim report or a refused payload, no serverStatus|{\"status\": \"PASS\", \"assertsEvaluated\": true}"
        "serverStatus NOT_EVALUATED|{\"serverStatus\": \"NOT_EVALUATED\", $comps, $asserts}"
        "a declared component missing|{\"serverStatus\": \"PASS\", \"serverComponents\": [{\"id\": \"db\", \"status\": \"PASS\"}], $asserts}"
        "a component that judged nothing|{\"serverStatus\": \"PASS\", \"serverComponents\": [{\"id\": \"db\", \"status\": \"PASS\"}, {\"id\": \"bus\", \"status\": \"NOT_EVALUATED\"}], $asserts}"
        "an assert NOT_JUDGED|{\"serverStatus\": \"FAIL\", $comps, \"serverAsserts\": [{\"id\": \"a\", \"component\": \"db\", \"status\": \"PASS\"}, {\"id\": \"b\", \"component\": \"bus\", \"status\": \"NOT_JUDGED\", \"reason\": \"SOURCE_FAILED\"}]}"
        "no server assert|{\"serverStatus\": \"FAIL\", $comps, \"serverAsserts\": []}"
        "a .json that is not JSON|not json"
    )
    for entry in "${server_refused[@]}"; do
        printf '%s\n' "${entry#*|}" > "$json"
        rc=0
        out="$(report_server_verdicts "$html" "$annotations" 2>/dev/null)" || rc=$?
        if [[ $rc -eq 0 || -n "$out" ]]; then
            echo "[SELFTEST] report_server_verdicts read '$out' (rc=$rc) off ${entry%%|*}" >&2
            fail=1
        fi
    done
    printf '%s\n' "{\"serverStatus\": \"FAIL\", $comps, $asserts}" > "$json"
    printf '%s\n' '{"assertions": []}' > "$tmp/no-components.json"
    rc=0
    out="$(report_server_verdicts "$html" "$tmp/no-components.json" 2>/dev/null)" || rc=$?
    if [[ $rc -eq 0 || -n "$out" ]]; then
        echo "[SELFTEST] report_server_verdicts read '$out' (rc=$rc) for an annotations file declaring no component" >&2
        fail=1
    fi
    rm -f "$json"
    rc=0
    out="$(report_server_verdicts "$html" "$annotations" 2>/dev/null)" || rc=$?
    if [[ $rc -eq 0 || -n "$out" ]]; then
        echo "[SELFTEST] report_server_verdicts read '$out' (rc=$rc) with no .json beside the page" >&2
        fail=1
    fi

    # The published page names no local directory (report_local_path): every spelling of one -
    # Git Bash's, Java's, JSON-escaped either way - is found, and a page naming none is clean.
    local dir="/d/Work Space/My-Repo" spelling page="$tmp/page.html"
    for spelling in 'D:/Work Space/My-Repo/x.csv' 'D:\/Work Space\/My-Repo\/x.csv' \
                    'D:\Work Space\My-Repo\x.csv' 'D:\\Work Space\\My-Repo\\x.csv' '/d/Work Space/My-Repo/x.csv'; do
        printf '<script>var d = {"resolvedUrl": "%s"};</script>\n' "$spelling" > "$page"
        rc=0
        out="$(report_local_path "$page" "/nowhere/else" "$dir")" || rc=$?
        if [[ $rc -ne 0 || -z "$out" ]]; then
            echo "[SELFTEST] report_local_path did not find $dir in a page naming $spelling" >&2
            fail=1
        fi
    done
    printf '<script>var d = {"resolvedUrl": "x.csv", "a": "My-Repo", "b": "Work Space"};</script>\n' > "$page"
    rc=0
    out="$(report_local_path "$page" "$dir" "/My-Repo" "")" || rc=$?
    if [[ $rc -eq 0 || -n "$out" ]]; then
        echo "[SELFTEST] report_local_path found '$out' in a page naming no directory" >&2
        fail=1
    fi

    # And the example script reads every verdict through these, not through a grep of the page or
    # a check of its own nothing tests.
    local example="$PROJECT_DIR/scripts/refresh-report-example.sh"
    if ! grep -q 'report_gate_verdict "\$HTML"' "$example" \
            || grep -q "grep -[oq]* *['\"]ASSERTS" "$example"; then
        echo "[SELFTEST] refresh-report-example.sh does not read its verdict through report_gate_verdict" >&2
        fail=1
    fi
    if ! grep -q 'report_server_verdicts "\$HTML"' "$example" || ! grep -q 'report_local_path "\$HTML"' "$example" \
            || grep -q 'node -e' "$example"; then
        echo "[SELFTEST] refresh-report-example.sh does not check its server side and its paths through" \
             "report_server_verdicts and report_local_path (or judges something inline with node -e)" >&2
        fail=1
    fi
    rm -rf "$tmp"
    [[ $fail -eq 0 ]] || return 1
    echo "[SELFTEST] the example's verdicts are the run's JSON, not the first ASSERTS on the page, and it names no local path"
}

# A fixture regeneration that wrote no report must leave nothing behind that a reader could call
# current. It used to: run_test_jmeter stamped the directory whatever JMeter had done,
# _execute_scenario deleted only results.jtl, and validate_results is `[[ -f ]]`, so a scenario
# whose JMeter rendered nothing PASSED on the previous cycle's .html and .json, under a fresh stamp
# naming the JAR now in lib/ext. R04 said "regenerated" and R05 tested the previous build, green.
# generate-cmp-fixture.sh had the same shape, and stamped right after recording its own FAIL (F3).
#
# So every producer empties what it is about to write, believes the directory over rm, and stamps
# only a report the run itself wrote. JMeter's exit code is never the signal: E25 runs with
# failonerror=false, E11's FAIL verdict exits 0, and a config error exits 1 beside a real report.
#
# The stub JMeter exits 0 having written nothing (E25's shape: failonerror=false) and 1 beside a
# report it wrote (a configuration error's), so a producer keyed on the exit code gets both wrong.
#
# 1. JMeter wrote nothing. Three producers, each handed the previous build's output:
#   - run_test_jmeter itself, every caller's contract: non-zero, its artifacts and stamp gone, the
#     default name's web-insight-data.js sidecar included;
#   - run-all-tests.sh over E05 and E19: E05 holds a stale report-annotations.json, an INPUT the
#     listener auto-detects in its output directory, and E19 its external-data sidecar; both must
#     FAIL by name, and nothing of the old run may survive;
#   - generate-cmp-fixture.sh over an old CMP-report.html that carries the comparison marker.
# 2. rm cannot remove the previous report (a Windows lock, the I1 lesson), the case each producer's
#    still-there guard exists for. rm is replaced, as selftest_purge_old_plugin_jars replaces it,
#    by one that fails and then by one that returns 0 and leaves the file, because the directory is
#    believed and rm's status is not. run_test_jmeter, run-all-tests.sh over E05, E26 and E27 (the
#    last two rm -rf their own directory for `-g -o`) and generate-cmp-fixture.sh must each refuse:
#    non-zero, JMeter never called, the old stamp untouched, every scenario a FAIL by name. Without
#    the guards each of them passed on the old report and stamped it as this JAR's.
# 3. The opposite: a stub that does write its report gets it stamped, so a fix that simply never
#    stamps cannot pass.
#
# Needs the built plugin JAR (require_deployed_plugin), like selftest_goldens_accept, and no JMeter.
# It runs the real runners with RESULTS_DIR pointed at a scratch directory, and those runners now
# rm -rf what they regenerate, so before calling any of them it proves that a child shell sourcing
# this file resolves RESULTS_DIR to that directory, and refuses otherwise: a broken override would
# empty the real test-results/E05, E19, E26, E27 and CMP. A subshell body, so nothing it sets
# reaches the runner that called it. Every expected failure is taken with `|| rc=$?` and every
# diagnostic pipe ends `|| true`, because CI calls this under errexit.
selftest_failed_regeneration() (
    local tmp log rc probe fail=0 f id verdict
    tmp="$(mktemp -d)" || { echo "[SELFTEST] mktemp failed" >&2; return 1; }
    log="$tmp/run.log"
    probe="$(RESULTS_DIR="$tmp/results" bash -c '. "$1" >/dev/null 2>&1; printf "%s" "$RESULTS_DIR"' \
        _ "$PROJECT_DIR/scripts/lib/common.sh")" || probe=""
    if [[ "$probe" != "$tmp/results" ]]; then
        echo "[SELFTEST] failed regeneration: a child shell resolved RESULTS_DIR to '$probe', not '$tmp/results'." >&2
        echo "           Refusing to run the runners, which would empty the real fixtures." >&2
        rm -rf "$tmp"; return 1
    fi
    mkdir -p "$tmp/home/bin" "$tmp/home/lib/ext" "$tmp/results/X" "$tmp/results/W" \
             "$tmp/results/E05" "$tmp/results/E19" "$tmp/results/CMP"
    : > "$tmp/home/lib/ext/$(basename "$PLUGIN_JAR")"
    # Logs every call to WIR_STUB_CALLS when set, and creates every -l file, so CMP's baseline check
    # passes. Writes the report and its .json only with WIR_STUB_REPORT=1, and then exits 1;
    # otherwise exits 0 having written no report.
    cat > "$tmp/home/bin/jmeter" <<'STUB'
#!/usr/bin/env bash
[[ -z "${WIR_STUB_CALLS:-}" ]] || echo "jmeter $*" >> "$WIR_STUB_CALLS"
out="" name="" prev=""
for a in "$@"; do
    [[ "$prev" != -l ]] || : > "$a"
    case "$a" in
        -Jwebinsight.report.output=*) out="${a#*=}" ;;
        -Jwebinsight.report.filename=*) name="${a#*=}" ;;
    esac
    prev="$a"
done
[[ "${WIR_STUB_REPORT:-0}" == 1 && -n "$out" && -n "$name" ]] || exit 0
echo '<html></html>' > "$out/$name"
echo '{}' > "$out/${name%.html}.json"
exit 1
STUB
    chmod +x "$tmp/home/bin/jmeter"
    # rm as a lock leaves it: nothing at or under a path in WIR_SELFTEST_LOCKED ('|'-separated) is
    # removed, and rm returns WIR_SELFTEST_RM_RC (1, as a held file fails; 0, as a delete-pending
    # one or a shim succeeds). Exported, so the runners and their background scenarios inherit it.
    # Call it inside a ( ) only.
    _selftest_lock_rm() {
        export WIR_SELFTEST_LOCKED="$1" WIR_SELFTEST_RM_RC="${2:-1}"
        rm() {
            local a p rest
            for a in "$@"; do
                [[ "$a" != -* ]] || continue
                rest="$WIR_SELFTEST_LOCKED|"
                while [[ -n "$rest" ]]; do
                    p="${rest%%|*}"; rest="${rest#*|}"
                    if [[ -n "$p" && ( "$a" == "$p" || "$a" == "$p"/* ) ]]; then
                        [[ "$WIR_SELFTEST_RM_RC" == 0 ]] \
                            || echo "rm: cannot remove '$a': Device or resource busy" >&2
                        return "$WIR_SELFTEST_RM_RC"
                    fi
                done
            done
            command rm "$@"
        }
        export -f rm
    }
    local -a old=(
        X/X-report.html X/X-report.json X/X-report.xml X/X-report-data.js X/.fixture-stamp
        W/web-insight-report.html W/web-insight-report.json W/web-insight-data.js W/.fixture-stamp
        E05/E05-report.html E05/E05-report.json E05/.fixture-stamp E05/report-annotations.json
        E19/E19-report.html E19/E19-report.json E19/E19-report-data.js E19/.fixture-stamp
        CMP/.fixture-stamp
    )
    for f in "${old[@]}"; do echo "the previous build's" > "$tmp/results/$f"; done
    printf '%s\n' '{\"hasComparison\":true}' > "$tmp/results/CMP/CMP-report.html"

    # --- 1. JMeter wrote nothing ---
    # run_test_jmeter itself, whoever calls it; run_jmeter exits 0, as the stub does.
    rc=0
    ( run_jmeter() { return 0; }; run_test_jmeter "$tmp/results/X" none.jmx X-report T ) || rc=$?
    [[ $rc -ne 0 ]] || { echo "[SELFTEST] run_test_jmeter returned 0 for a run that wrote no report" >&2; fail=1; }
    rc=0
    ( run_jmeter() { return 0; }; run_test_jmeter "$tmp/results/W" none.jmx web-insight-report T ) || rc=$?
    [[ $rc -ne 0 ]] || { echo "[SELFTEST] run_test_jmeter returned 0 for a default-named run that wrote no report" >&2; fail=1; }

    # run-all-tests.sh: two scenarios whose JMeter wrote nothing.
    rc=0
    RESULTS_DIR="$tmp/results" JMETER_HOME="$tmp/home" SKIP_BUILD=1 WIR_JOBS=1 WIR_STUB_REPORT=0 \
        bash "$PROJECT_DIR/test-plans/scripts/run-all-tests.sh" --suite fixtures --only E05,E19 > "$log" 2>&1 || rc=$?
    if [[ $rc -eq 0 ]] || ! grep -q '\[FAIL\] E05' "$log" || ! grep -q '\[FAIL\] E19' "$log" \
            || ! grep -q 'HTML report not generated' "$log"; then
        echo "[SELFTEST] run-all-tests.sh passed scenarios whose JMeter wrote no report (rc=$rc):" >&2
        { grep -E '\[(PASS|FAIL)\]|ERROR' "$log" || true; } | sed 's/^/    /' >&2; fail=1
    fi

    # generate-cmp-fixture.sh: both JTLs written, no report.
    rc=0
    RESULTS_DIR="$tmp/results" JMETER_HOME="$tmp/home" SKIP_BUILD=1 WIR_STUB_REPORT=0 \
        bash "$PROJECT_DIR/test-plans/scripts/generate-cmp-fixture.sh" > "$log" 2>&1 || rc=$?
    if [[ $rc -eq 0 ]] || ! grep -q 'report not generated' "$log"; then
        echo "[SELFTEST] generate-cmp-fixture.sh passed a run that wrote no report (rc=$rc):" >&2
        { grep -E '\[(PASS|FAIL)\]|ERROR|FAILED' "$log" || true; } | sed 's/^/    /' >&2; fail=1
    fi

    for f in "${old[@]}" CMP/CMP-report.html; do
        if [[ -e "$tmp/results/$f" ]]; then
            echo "[SELFTEST] a regeneration that wrote no report left $f behind" >&2; fail=1
        fi
    done

    # --- 2. rm cannot remove the previous report ---
    # run_test_jmeter itself, under an rm that fails and then one that returns 0; rm is a function
    # in this subshell, so only the guard stands between the old report and a fresh stamp.
    for verdict in 1 0; do
        mkdir -p "$tmp/results/L"
        rm -f "$tmp/results/L/called" "$tmp/results/L/jmeter.log"
        for f in L-report.html L-report.json .fixture-stamp; do
            echo "the previous build's" > "$tmp/results/L/$f"
        done
        rc=0
        ( _selftest_lock_rm "$tmp/results/L" "$verdict"
          run_jmeter() { : > "$tmp/results/L/called"; }
          run_test_jmeter "$tmp/results/L" none.jmx L-report T ) > "$log" 2>&1 || rc=$?
        if [[ $rc -eq 0 || -e "$tmp/results/L/called" \
              || "$(cat "$tmp/results/L/.fixture-stamp" 2>/dev/null)" != "the previous build's" ]] \
              || ! grep -q 'not running over it' "$tmp/results/L/jmeter.log" 2>/dev/null; then
            echo "[SELFTEST] run_test_jmeter ran over a previous report rm left behind (rm status $verdict, rc=$rc)" >&2
            { cat "$log" || true; } | sed 's/^/    /' >&2; fail=1
        fi
    done

    # run-all-tests.sh: E05 through run_test_jmeter, E26 and E27 through their own rm -rf. Each old
    # report carries what its scenario's checks look for, so only the guard can fail it.
    for id in E05 E26 E27; do
        mkdir -p "$tmp/results/$id"
        echo "the previous build's" > "$tmp/results/$id/.fixture-stamp"
        echo '{}' > "$tmp/results/$id/$id-report.json"
    done
    echo 'the previous build' > "$tmp/results/E05/E05-report.html"
    echo '<div id="tab-server"></div> serverGates' > "$tmp/results/E26/E26-report.html"
    echo 'serverCatalogue cache.hit.pct' > "$tmp/results/E27/E27-report.html"
    rc=0
    rm -f "$tmp/calls"
    ( _selftest_lock_rm "$tmp/results/E05|$tmp/results/E26|$tmp/results/E27"
      RESULTS_DIR="$tmp/results" JMETER_HOME="$tmp/home" SKIP_BUILD=1 WIR_JOBS=1 WIR_STUB_REPORT=0 \
          WIR_STUB_CALLS="$tmp/calls" \
          bash "$PROJECT_DIR/test-plans/scripts/run-all-tests.sh" --suite fixtures --only E05,E26,E27 ) \
        > "$log" 2>&1 || rc=$?
    [[ $rc -ne 0 ]] || { echo "[SELFTEST] run-all-tests.sh exited 0 over three reports rm left behind" >&2; fail=1; }
    for id in E05 E26 E27; do
        if ! grep -q "\[FAIL\] $id" "$log" || grep -qF "$tmp/results/$id" "$tmp/calls" 2>/dev/null \
                || [[ "$(cat "$tmp/results/$id/.fixture-stamp" 2>/dev/null)" != "the previous build's" ]]; then
            echo "[SELFTEST] run-all-tests.sh ran $id over a previous report rm left behind:" >&2
            { grep -E "\[(PASS|FAIL)\] $id|ERROR.*/$id/" "$log" || true; } | sed 's/^/    /' >&2
            { grep -F "$tmp/results/$id" "$tmp/calls" 2>/dev/null || true; } | sed 's/^/    called: /' >&2
            fail=1
        fi
    done

    # generate-cmp-fixture.sh over an old CMP-report.html with the comparison marker.
    mkdir -p "$tmp/results/CMP"
    echo "the previous build's" > "$tmp/results/CMP/.fixture-stamp"
    printf '%s\n' '{\"hasComparison\":true}' > "$tmp/results/CMP/CMP-report.html"
    rc=0
    rm -f "$tmp/calls"
    ( _selftest_lock_rm "$tmp/results/CMP"
      RESULTS_DIR="$tmp/results" JMETER_HOME="$tmp/home" SKIP_BUILD=1 WIR_STUB_REPORT=0 \
          WIR_STUB_CALLS="$tmp/calls" \
          bash "$PROJECT_DIR/test-plans/scripts/generate-cmp-fixture.sh" ) > "$log" 2>&1 || rc=$?
    if [[ $rc -eq 0 || -e "$tmp/calls" ]] || ! grep -q 'could not remove' "$log" \
            || [[ "$(cat "$tmp/results/CMP/.fixture-stamp" 2>/dev/null)" != "the previous build's" ]]; then
        echo "[SELFTEST] generate-cmp-fixture.sh ran over a previous report rm left behind (rc=$rc):" >&2
        { grep -E '\[(PASS|FAIL)\]|ERROR|FAILED' "$log" || true; } | sed 's/^/    /' >&2; fail=1
    fi

    # --- 3. a run that does write its report is stamped ---
    rc=0
    RESULTS_DIR="$tmp/results" JMETER_HOME="$tmp/home" SKIP_BUILD=1 WIR_JOBS=1 WIR_STUB_REPORT=1 \
        bash "$PROJECT_DIR/test-plans/scripts/run-all-tests.sh" --suite fixtures --only E05 > "$log" 2>&1 || rc=$?
    if [[ $rc -ne 0 || ! -f "$tmp/results/E05/E05-report.html" || ! -f "$tmp/results/E05/.fixture-stamp" ]]; then
        echo "[SELFTEST] a scenario that wrote its report was not stamped (rc=$rc)" >&2
        { grep -E '\[(PASS|FAIL)\]|ERROR' "$log" || true; } | sed 's/^/    /' >&2; fail=1
    fi
    rm -rf "$tmp"
    [[ $fail -eq 0 ]] || return 1
    echo "[SELFTEST] a failed fixture regeneration leaves no report and no stamp behind, and none runs over a report rm left"
)

# The jq idioms a pipeline gates with are kept in ONE file, test-plans/goldens/jq-gate.txt, and
# every line of it is RUN here over the golden CI/CD documents its CASES line names, and must
# answer what the file says beside it. The idiom this replaced was written into the plan and a
# javadoc and run by nothing; it errored on every client-only run and read false on every
# server-only one.
#
# Opens like verify_shaded_jar: with no jq on PATH it says so and passes, because jq is not a
# dependency of this project. CI's ubuntu runner has jq, so the check is enforced there on every
# push; locally test-plans/js-unit/jq-gate.test.js runs a JavaScript translation of each line
# over the same goldens. Optional arguments, for selftest_jq_idioms: the idiom file, and the
# directory holding the G*.json documents. Every real caller passes nothing.
#
# File format: comments (#) and blank lines; one `CASES G1 G2 ...` line before any idiom; then
# `NAME <answer per case> PROGRAM`, the program taking the rest of the line, spaces and all.
verify_jq_idioms() {
    command -v jq >/dev/null 2>&1 || { echo "[JQ] skipped: jq is not on PATH (CI runs this check)"; return 0; }
    local file="${1:-$PROJECT_DIR/test-plans/goldens/jq-gate.txt}"
    local goldens="${2:-$PROJECT_DIR/test-plans/goldens/expected}"
    [[ -f "$file" ]] || { echo "[JQ] FAILED: no idiom file at $file" >&2; return 1; }

    local line rest tok name program id want got rc i k n=0 bad=0
    local -a cases=() wants=()
    while IFS= read -r line || [[ -n "$line" ]]; do
        line="${line%$'\r'}"
        [[ -z "${line//[[:space:]]/}" || "$line" == \#* ]] && continue
        if [[ "$line" == CASES[[:space:]]* ]]; then
            read -r -a cases <<< "${line#CASES}"
            for id in "${cases[@]}"; do
                [[ -f "$goldens/$id.json" ]] || { echo "[JQ] FAILED: CASES names $id, and there is no $goldens/$id.json" >&2; bad=1; }
            done
            continue
        fi
        if (( ${#cases[@]} == 0 )); then
            echo "[JQ] FAILED: an idiom before any CASES line: $line" >&2
            bad=1
            continue
        fi
        # NAME, then one answer per case, then the program — the rest of the line.
        rest="$line"
        name=""
        wants=()
        for (( k = 0; k <= ${#cases[@]}; k++ )); do
            rest="${rest#"${rest%%[![:space:]]*}"}"
            tok="${rest%%[[:space:]]*}"
            rest="${rest#"$tok"}"
            if (( k == 0 )); then name="$tok"; else wants+=("$tok"); fi
        done
        program="${rest#"${rest%%[![:space:]]*}"}"
        if [[ -z "$program" ]]; then
            echo "[JQ] FAILED: not NAME, ${#cases[@]} answer(s) and a PROGRAM: $line" >&2
            bad=1
            continue
        fi
        for want in "${wants[@]}"; do
            if [[ "$want" != true && "$want" != false ]]; then
                echo "[JQ] FAILED: $name: an answer is true or false, not '$want': $line" >&2
                bad=1
                continue 2
            fi
        done
        n=$((n + 1))
        for (( i = 0; i < ${#cases[@]}; i++ )); do
            id="${cases[$i]}"
            want="${wants[$i]}"
            # `|| rc=$?`: jq -e exits 1 on a false answer, which is an expected value here and
            # must not kill a caller running under `set -e` (the CI step does).
            rc=0
            jq -e "$program" "$goldens/$id.json" >/dev/null 2>&1 || rc=$?
            case $rc in
                0) got=true ;;
                1) got=false ;;
                *) got="an error (jq exit $rc)" ;;
            esac
            if [[ "$got" != "$want" ]]; then
                echo "[JQ] FAILED: $name over $id.json is $got; the file says $want" >&2
                bad=1
            fi
        done
    done < "$file"
    if (( n == 0 )); then
        echo "[JQ] FAILED: no idiom in $file" >&2
        return 1
    fi
    [[ $bad -eq 0 ]] || return 1
    echo "[JQ] $n jq idiom(s) answer as written over ${cases[*]}"
}

# What verify_jq_idioms must refuse, fed back in. Skipped, like the check itself, where there is
# no jq.
#
# 1. The idiom D8 documented, with the answers the corrected idioms give: refused by name and over
#    the documents it gets wrong (G1: no gate block, so `null == 0` is false; G3: no serverGates,
#    so jq errors). A check that accepted it would be the check that let it ship.
# 2. A malformed line: a refusal, not a line skipped in silence.
# 3. Every clause of the real file's lines, broken one at a time and given that line's own
#    answers: each mutant must answer differently over SOME golden, or the goldens cannot see
#    that clause at all. This is the probe that found the server half blind over G1-G4 — a wrong
#    key, a deleted server clause and an inverted failsBuild test all passed — kept as a guard.
#    A mutation whose text is no longer in the line is a failure too, naming the clause, so the
#    line cannot be edited out from under its guard.
selftest_jq_idioms() {
    command -v jq >/dev/null 2>&1 || { echo "[SELFTEST] jq idioms: skipped, jq is not on PATH"; return 0; }
    local real="$PROJECT_DIR/test-plans/goldens/jq-gate.txt"
    local tmp out rc fail=0
    tmp="$(mktemp)" || { echo "[SELFTEST] mktemp failed" >&2; return 1; }
    printf '%s\n' '# the D8 idiom' 'CASES G1 G2 G3 G4' \
        'D8 true false true false .gate.failed == 0 and ([.serverGates[] | select(.failsBuild) | .failed] | add // 0) == 0' \
        > "$tmp"
    rc=0
    out="$(verify_jq_idioms "$tmp" 2>&1)" || rc=$?
    if [[ $rc -eq 0 ]]; then
        echo "[SELFTEST] the jq check accepted the D8 idiom that errors on a client-only run" >&2; fail=1
    elif [[ "$out" != *"D8 over G1.json is false"* || "$out" != *"D8 over G3.json is an error"* ]]; then
        echo "[SELFTEST] the jq check refused the D8 idiom, but not for what is wrong with it:" >&2
        printf '%s\n' "$out" | sed 's/^/    /' >&2
        fail=1
    fi

    printf '%s\n' 'CASES G1 G2' 'HALF true false' > "$tmp"
    rc=0
    verify_jq_idioms "$tmp" >/dev/null 2>&1 || rc=$?
    if [[ $rc -eq 0 ]]; then
        echo "[SELFTEST] the jq check accepted a line with no program" >&2; fail=1
    fi

    local cases_line default_line strict_line
    cases_line="$(grep -m1 '^CASES[[:space:]]' "$real")"
    default_line="$(grep -m1 '^DEFAULT[[:space:]]' "$real")"
    strict_line="$(grep -m1 '^STRICT[[:space:]]' "$real")"
    if [[ -z "$cases_line" || -z "$default_line" || -z "$strict_line" ]]; then
        echo "[SELFTEST] $real no longer has a CASES, a DEFAULT and a STRICT line to mutate" >&2
        rm -f "$tmp"
        return 1
    fi
    # Parallel arrays rather than one delimited string: a jq program is full of `|`.
    # label, the line it breaks, the clause's text, and what that text becomes.
    local -a labels=() bases=() froms=() tos=()
    labels+=(wrong-key);            bases+=(DEFAULT); froms+=('.serverStatus');                   tos+=('.serverVerdict')
    labels+=(no-server-half);       bases+=(DEFAULT); froms+=(' and (.serverStatus // "PASS") != "FAIL"'); tos+=('')
    labels+=(no-collection-clause); bases+=(STRICT);  froms+=('.notJudged.sourceFailed + .notJudged.lowCoverage'); tos+=('0')
    labels+=(no-findings-clause);   bases+=(STRICT)
    froms+=(' and ([.findings[]? | select(.severity == "error")] | length) == 0');                    tos+=('')
    local j label base from to source mutated
    for (( j = 0; j < ${#labels[@]}; j++ )); do
        label="${labels[$j]}"; base="${bases[$j]}"; from="${froms[$j]}"; to="${tos[$j]}"
        if [[ "$base" == DEFAULT ]]; then source="$default_line"; else source="$strict_line"; fi
        mutated="${source/"$from"/"$to"}"
        if [[ "$mutated" == "$source" ]]; then
            echo "[SELFTEST] jq mutant $label no longer applies: '$from' is not in the $base line - re-aim it" >&2
            fail=1
            continue
        fi
        mutated="$label ${mutated#"$base"}"
        printf '%s\n' "$cases_line" "$mutated" > "$tmp"
        rc=0
        out="$(verify_jq_idioms "$tmp" 2>&1)" || rc=$?
        if [[ $rc -eq 0 || "$out" != *"$label over "* ]]; then
            echo "[SELFTEST] the goldens cannot see the $base clause '$from': mutant $label answers as $base does over every one" >&2
            fail=1
        fi
    done
    rm -f "$tmp"
    [[ $fail -eq 0 ]] || return 1
    echo "[SELFTEST] the jq check refuses what it claims to (D8, a malformed line, ${#labels[@]} one-clause mutants)"
}

# Every runner is named by the release skill.
#
# The repo ships 13 runners and the release tier named 5 of them, so 8 were invoked by no
# documented workflow at all — including run-family-ab-tests.sh, which carries B12, one of the
# two deliberate exit-0 exemptions CLAUDE.md cites BY NAME. An exemption defended only by a
# stage nobody runs is not defended, and neither is the -n -t -e -o handshake that D01-D06 is
# the only coverage of.
#
# Three are exempt, each for its own reason: run-sanity.sh is the constant-cycle tier;
# run-regression.sh is invoked by name at the top of the release list; and run-goldens.sh is
# invoked BY regression as R06, so naming it separately would run it twice.
verify_release_tier() {
    local skill="$PROJECT_DIR/.claude/skills/_perf_release/SKILL.md"
    # Untracked on some machines by .gitignore; absence is not a failure here.
    [[ -f "$skill" ]] || { echo "[TIER] no _perf_release skill — skipping"; return 0; }
    local missing=() f name
    for f in "$PROJECT_DIR"/test-plans/scripts/run-*.sh; do
        name="$(basename "$f")"
        case "$name" in
            run-sanity.sh|run-goldens.sh) continue ;;
        esac
        grep -q "$name" "$skill" || missing+=("$name")
    done
    if [[ ${#missing[@]} -gt 0 ]]; then
        echo "[TIER] runners that no release tier invokes: ${missing[*]}" >&2
        echo "       Add them to .claude/skills/_perf_release/SKILL.md, or delete the runner." >&2
        return 1
    fi
    echo "[TIER] every runner is in the release tier"
}
