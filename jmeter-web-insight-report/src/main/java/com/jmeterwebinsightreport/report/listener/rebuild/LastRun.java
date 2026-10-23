package com.jmeterwebinsightreport.report.listener.rebuild;

import java.io.File;

/**
 * What the last run of one Web Insight Report listener actually resolved, kept on the element the
 * panel edits so the panel's Rebuild button rebuilds <em>that</em> run (L3).
 *
 * <p><b>Why it has to be kept at all.</b> Re-reading the fields at the click gets the run's own
 * inputs wrong: {@code results-${__time(HHmmss)}.jtl} names a file that does not exist, and a Report
 * Title edited since the run changes the {@code runId} (the title is hashed) and orphans the page's
 * drafts. The run knew all of it when it started; this is that knowledge, written once.
 *
 * <p><b>Why it is immutable and written once.</b> A GUI Start runs on clones, so the run that
 * resolved these values is not the element the panel reads (R1). The run-clone builds one of these
 * after it generated its report and publishes it into its source element through a volatile field;
 * the panel reads that field on the event thread. One object, never mutated, is the whole of the
 * handshake: there is nothing to see half-written.
 *
 * <p>The Server data and Annotations fields are kept twice, as the text the run was started with
 * and as the run resolved it. That is R4: a field unchanged since the run reads as the run read it
 * (a test-plan variable included, which only a running test can resolve), and an edited one is
 * evaluated at the click. The other inputs are only ever the run's.
 */
public final class LastRun {

    private final File jtl;
    private final File outputDirectory;
    private final String htmlFilename;
    private final String title;
    private final long rampUpMillis;
    private final boolean junit;
    private final boolean externalData;
    private final long externalDataThreshold;
    private final String apdexThreshold;
    private final File baseline;
    private final String runId;
    private final long endedAt;
    private final String serverDataRaw;
    private final String serverDataResolved;
    private final String annotationsRaw;
    private final String annotationsResolved;

    private LastRun(Builder b) {
        this.jtl = b.jtl;
        this.outputDirectory = b.outputDirectory;
        this.htmlFilename = b.htmlFilename;
        this.title = b.title;
        this.rampUpMillis = b.rampUpMillis;
        this.junit = b.junit;
        this.externalData = b.externalData;
        this.externalDataThreshold = b.externalDataThreshold;
        this.apdexThreshold = b.apdexThreshold;
        this.baseline = b.baseline;
        this.runId = b.runId;
        this.endedAt = b.endedAt;
        this.serverDataRaw = nullToEmpty(b.serverDataRaw);
        this.serverDataResolved = nullToEmpty(b.serverDataResolved);
        this.annotationsRaw = nullToEmpty(b.annotationsRaw);
        this.annotationsResolved = nullToEmpty(b.annotationsResolved);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The results file the run wrote, resolved as ResultCollector resolved it, or null when it wrote none. */
    public File jtl() {
        return jtl;
    }

    /** The output directory as finally resolved, the default included. */
    public File outputDirectory() {
        return outputDirectory;
    }

    /** The HTML filename the run wrote - a {@code ${timestamp}} already stamped, so a rebuild overwrites it. */
    public String htmlFilename() {
        return htmlFilename;
    }

    public String title() {
        return title;
    }

    public long rampUpMillis() {
        return rampUpMillis;
    }

    public boolean junit() {
        return junit;
    }

    public boolean externalData() {
        return externalData;
    }

    public long externalDataThreshold() {
        return externalDataThreshold;
    }

    /** {@code webinsight.apdex.threshold} as the run read it, or null. */
    public String apdexThreshold() {
        return apdexThreshold;
    }

    /** The baseline the run was explicitly given ({@code webinsight.report.baseline}), or null: an auto-detected one is found again. */
    public File baseline() {
        return baseline;
    }

    /** The {@code runId} of the report the run wrote. */
    public String runId() {
        return runId;
    }

    /** When the report was generated, epoch ms: the run is over by then. */
    public long endedAt() {
        return endedAt;
    }

    /** The Server data field's text as the run was started with it ("" when empty). */
    public String serverDataRaw() {
        return serverDataRaw;
    }

    /** The Server data field as the run resolved it ("" when empty). */
    public String serverDataResolved() {
        return serverDataResolved;
    }

    /** The Annotations File field's text as the run was started with it ("" when empty). */
    public String annotationsRaw() {
        return annotationsRaw;
    }

    /** The Annotations File field as the run resolved it ("" when empty). */
    public String annotationsResolved() {
        return annotationsResolved;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    /** Filled by the run-clone, field by field, then built once. */
    public static final class Builder {
        private File jtl;
        private File outputDirectory;
        private String htmlFilename;
        private String title;
        private long rampUpMillis;
        private boolean junit;
        private boolean externalData;
        private long externalDataThreshold;
        private String apdexThreshold;
        private File baseline;
        private String runId;
        private long endedAt;
        private String serverDataRaw;
        private String serverDataResolved;
        private String annotationsRaw;
        private String annotationsResolved;

        private Builder() {
        }

        public Builder jtl(File jtl) {
            this.jtl = jtl;
            return this;
        }

        public Builder outputDirectory(File outputDirectory) {
            this.outputDirectory = outputDirectory;
            return this;
        }

        public Builder htmlFilename(String htmlFilename) {
            this.htmlFilename = htmlFilename;
            return this;
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder rampUpMillis(long rampUpMillis) {
            this.rampUpMillis = rampUpMillis;
            return this;
        }

        public Builder junit(boolean junit) {
            this.junit = junit;
            return this;
        }

        public Builder externalData(boolean externalData, long threshold) {
            this.externalData = externalData;
            this.externalDataThreshold = threshold;
            return this;
        }

        public Builder apdexThreshold(String apdexThreshold) {
            this.apdexThreshold = apdexThreshold;
            return this;
        }

        public Builder baseline(File baseline) {
            this.baseline = baseline;
            return this;
        }

        public Builder runId(String runId) {
            this.runId = runId;
            return this;
        }

        public Builder endedAt(long endedAt) {
            this.endedAt = endedAt;
            return this;
        }

        public Builder serverData(String raw, String resolved) {
            this.serverDataRaw = raw;
            this.serverDataResolved = resolved;
            return this;
        }

        public Builder annotations(String raw, String resolved) {
            this.annotationsRaw = raw;
            this.annotationsResolved = resolved;
            return this;
        }

        public LastRun build() {
            return new LastRun(this);
        }
    }
}
