#!/usr/bin/env bash
#
# wir-collect: collect server metrics for a finished JMeter run into server-metrics.json.
#
#   wir-collect.sh --jtl results.jtl --annotations report-annotations.json \
#                  --out server-metrics.json [--env NAME] [--test-name NAME] \
#                  [--rampup MILLIS] [--max-wait 10m] [--no-wait]
#   wir-collect.sh --help        the options, the exit codes and the plain java -cp command
#
# Exit codes (D27): 0 whenever the payload was written, whatever each series' status -- a dead
# source is recorded in the payload, and step 3 (`jmeter -g ... -Jwebinsight.report.servermetrics`)
# decides what it means; 1 when nothing was written (a usage, input or environment fault, and every
# refusal in this script, a missing java included). So the three steps can run under `set -e` or a
# Jenkins `sh` step.
#
# --test-name must equal -Jwebinsight.report.title when the -g -o step sets one: RunIdentity
# hashes the name, and a payload collected under another name is refused as another run's.
#
# SELF-CONTAINED, ON PURPOSE. This file is meant to be copied out of the repository -- beside a
# Jenkinsfile, onto a CI image, into $JMETER_HOME/bin -- and run where there is a JMeter install
# with the plugin JAR in lib/ext and nothing else; the release attaches this file (and its .bat)
# beside that JAR, not the scripts/lib/ it would need from a checkout. So it sources
# nothing. It used to source scripts/lib/common.sh, which made it run only inside a checkout of
# this repository. Everything it needs is below. (wir-collect.bat does not wrap this file: it is
# one java line over the launcher in the plugin JAR, and needs no bash.)
#
# Without either script, the same command by hand -- `--help` prints it with the JAR's own name:
#   java -jar "$JMETER_HOME/lib/ext/jmeter-web-insight-report-X.Y.Z.jar" --jtl ...
# The JAR's launcher finds JMeter from its place in lib/ext and puts JMeter's lib/ (Jackson and
# slf4j are `provided` by JMeter) on the classpath beside the plugin.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

die() {
    echo "wir-collect: $*" >&2
    exit 1
}

# Git Bash, MSYS2 and Cygwin run a Windows java.exe; WSL reports Linux and runs a Linux java.
case "$(uname -s 2>/dev/null)" in
    MINGW*|MSYS*|CYGWIN*) ON_WINDOWS=1 ;;
    *)                    ON_WINDOWS=0 ;;
esac

# ---- JMETER_HOME --------------------------------------------------------------------------------
# In order: $JMETER_HOME; `jmeter` on PATH with symlinks followed; and, last, an apache-jmeter-*
# beside this script's parent directory -- the layout of this repository's checkout, where
# build-and-deploy.sh deploys the JAR. That last probe matches nothing anywhere else, so outside a
# checkout the order is exactly "the variable, then PATH".
#
# A JMeter home here is a directory with lib/ext (and so lib/). Homebrew's bin/jmeter is a wrapper
# whose real home is <prefix>/libexec, so each candidate is also probed at libexec/.

is_jmeter_home() { [[ -d "$1/lib/ext" ]]; }

normalize_jmeter_home() {
    if is_jmeter_home "$1"; then printf '%s\n' "$1"; return 0; fi
    if is_jmeter_home "$1/libexec"; then printf '%s\n' "$1/libexec"; return 0; fi
    return 1
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

GIVEN_HOME="${JMETER_HOME:-}"
# A Windows user's JMETER_HOME is `C:\apache-jmeter-5.6.3`, inherited by Git Bash as written.
if (( ON_WINDOWS )) && [[ -n "$GIVEN_HOME" ]]; then
    GIVEN_HOME="$(cygpath -u "$GIVEN_HOME")" || die "cygpath could not read JMETER_HOME '$JMETER_HOME'"
fi
ON_PATH="$(command -v jmeter 2>/dev/null || true)"

find_jmeter_home() {
    local candidate resolved
    if [[ -n "$GIVEN_HOME" ]]; then
        # An explicit setting that is wrong is an error, never a reason to look elsewhere: falling
        # through would collect with another install's JAR and say nothing about it.
        normalize_jmeter_home "$GIVEN_HOME"
        return
    fi
    if [[ -n "$ON_PATH" ]]; then
        resolved="$(resolve_link "$ON_PATH")"
        candidate="$(cd "$(dirname "$resolved")/.." && pwd)"
        if normalize_jmeter_home "$candidate"; then
            return 0
        fi
    fi
    for candidate in "$SCRIPT_DIR"/../apache-jmeter-*; do
        if resolved="$(normalize_jmeter_home "$candidate")"; then
            (cd "$resolved" && pwd)
            return
        fi
    done
    return 1
}

if ! JMETER_HOME="$(find_jmeter_home)"; then
    if [[ -n "$GIVEN_HOME" ]]; then
        die "JMETER_HOME is set to '$GIVEN_HOME', which has no lib/ext - that is not a JMeter install"
    elif [[ -n "$ON_PATH" ]]; then
        die "JMETER_HOME is unset, and \`jmeter\` on PATH ($ON_PATH) is not in a JMeter install's bin/ -
set JMETER_HOME to the JMeter directory (the one holding bin/ and lib/ext/)"
    else
        die "no JMeter install found: JMETER_HOME is unset and \`jmeter\` is not on PATH -
set JMETER_HOME to the JMeter directory (the one holding bin/ and lib/ext/), or put its bin/ on PATH"
    fi
fi

# ---- the plugin JAR -----------------------------------------------------------------------------
# Exactly one in lib/ext is the deploy invariant: with two, which copy of a class JMeter loads is
# not deterministic, and a collector that silently picked one would hide exactly that.
shopt -s nullglob
jars=("$JMETER_HOME"/lib/ext/jmeter-web-insight-report-*.jar)
shopt -u nullglob
if (( ${#jars[@]} == 0 )); then
    die "no jmeter-web-insight-report-*.jar in $JMETER_HOME/lib/ext - copy the released JAR there (in a checkout of this repository: ./scripts/build-and-deploy.sh)"
fi
if (( ${#jars[@]} > 1 )); then
    die "$JMETER_HOME/lib/ext must hold exactly one plugin JAR, found ${#jars[@]}: ${jars[*]}"
fi
PLUGIN_JAR="${jars[0]}"

# ---- the classpath: the plugin, then JMeter's lib/* (a JVM classpath wildcard, not a glob) -------
# A Windows java.exe reads `D:\a.jar;D:\lib\*`. Joining POSIX paths with ';' stops MSYS's own
# ':'->';' conversion, so java.exe once received `/d/.../plugin.jar;...` verbatim and could not
# find the main class (C35, 2026-08-22); and `cygpath -wp` over a ':'-joined list splits a
# JMETER_HOME given as `D:/...` on its drive colon. So: convert each path on its own and join with
# ';' here. An argument holding backslashes is never path-converted by MSYS, so this does not
# depend on MSYS_NO_PATHCONV either way.
if (( ON_WINDOWS )); then
    plugin_w="$(cygpath -w "$PLUGIN_JAR")" || die "cygpath could not convert $PLUGIN_JAR"
    lib_w="$(cygpath -w "$JMETER_HOME/lib")" || die "cygpath could not convert $JMETER_HOME/lib"
    CP="$plugin_w;$lib_w\\*"
else
    CP="$PLUGIN_JAR:$JMETER_HOME/lib/*"
fi

# ---- java: the one bin/jmeter would use ---------------------------------------------------------
# bin/jmeter's order: $JAVA_HOME/bin/java when JAVA_HOME is set, else $JRE_HOME/bin/java when
# JRE_HOME is, else `java` on PATH. A variable that is set is used and only it: a wrong setting is
# an error rather than a reason to look elsewhere, as for JMETER_HOME. Found here rather than left
# to exec, whose failure is a bare 127 (or 126) where this script promises 1 and a sentence for
# every refusal.
if [[ -n "${JAVA_HOME:-}" ]]; then
    JAVA="$JAVA_HOME/bin/java"
    [[ -f "$JAVA" && -x "$JAVA" ]] \
        || die "JAVA_HOME is set to '$JAVA_HOME', which has no bin/java - set it to the Java JMeter runs on, or unset it to use \`java\` on PATH"
elif [[ -n "${JRE_HOME:-}" ]]; then
    JAVA="$JRE_HOME/bin/java"
    [[ -f "$JAVA" && -x "$JAVA" ]] \
        || die "JRE_HOME is set to '$JRE_HOME', which has no bin/java - set it to the Java JMeter runs on, or unset it to use \`java\` on PATH"
else
    JAVA="$(type -P java)" \
        || die "no Java found: JAVA_HOME and JRE_HOME are unset and \`java\` is not on PATH - install the Java JMeter runs on, or set JAVA_HOME to it"
fi
exec "$JAVA" -cp "$CP" com.jmeterwebinsightreport.WirCollect "$@"
