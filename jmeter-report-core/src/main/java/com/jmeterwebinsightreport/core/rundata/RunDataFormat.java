package com.jmeterwebinsightreport.core.rundata;

import java.util.List;

/**
 * Run data, the common format (spec 001, contracts/run-data-format.md): its version and its record types, in one place
 * (constitution V). The published JSON Schema is held to these by a test.
 */
public final class RunDataFormat {

    /** The version this release writes. A minor version only adds optional fields or record types. */
    public static final String VERSION = "1.0";

    /** The major versions this release reads. Every earlier released version stays here (FR-014). */
    public static final List<String> READS = List.of("1");

    public static final String TYPE_RUN = "run";
    public static final String TYPE_SAMPLE = "sample";
    public static final String TYPE_USERS = "users";
    public static final String TYPE_CHECK = "check";
    public static final String TYPE_THRESHOLD = "threshold";
    public static final String TYPE_COUNTER = "counter";

    /** The file name beside a report: {@code <report name>-run-data.jsonl.gz}. */
    public static final String FILE_SUFFIX = "-run-data.jsonl.gz";

    private RunDataFormat() {
    }

    /** Whether {@code file} is run data by its name: {@code .jsonl}, or {@code .jsonl.gz}. */
    public static boolean isRunDataFile(java.io.File file) {
        String n = file == null ? "" : file.getName().toLowerCase(java.util.Locale.ROOT);
        return n.endsWith(".jsonl") || n.endsWith(".jsonl.gz");
    }

    /** Whether this release reads a file of {@code version} ("1.0", "1.3"): its major version is one it reads. */
    public static boolean reads(String version) {
        if (version == null) {
            return false;
        }
        int dot = version.indexOf('.');
        String major = dot < 0 ? version : version.substring(0, dot);
        return READS.contains(major.trim());
    }

    /** The FR-011 sentence for a version this release does not read. */
    public static String refusal(String found) {
        return "run data of format version " + (found == null ? "(none)" : "\"" + found + "\"")
                + " is not one this report reads: it reads " + String.join(", ", READS.stream()
                .map(m -> m + ".x").toList()) + ", and writes " + VERSION + ". No numbers are shown";
    }
}
