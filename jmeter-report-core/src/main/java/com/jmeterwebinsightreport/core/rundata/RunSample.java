package com.jmeterwebinsightreport.core.rundata;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One sample of a run, in the common format's model (spec 001, data-model.md "Sample"): a request, or one pass of a
 * transaction or group. Every path into the statistics engine builds these and hands them to {@link SampleFeed}: the
 * JTL parser, the listener, the run-data reader and the tool adapters. A field left null is one the producer did not
 * give, and the feed applies the same default every path applied before there was one feed.
 *
 * <p>A plain carrier, mutable and unsynchronised: it is built, fed and dropped by one thread.
 */
public final class RunSample {

    /** How a sample nests under its parent ({@code data-model.md Sample.link}). */
    public enum Link {
        /** Inside a transaction or group pass: the engine's transaction relation. */
        TRANSACTION,
        /** A request's own sub-result (a redirect hop, an embedded resource): nested, not a transaction. */
        SUBRESULT
    }

    /** How a sample is counted ({@code data-model.md Sample.kind}). */
    public enum Kind {
        REQUEST,
        /** A transaction row: out of the Errors tab's sample count and of Codes per Second. */
        TRANSACTION
    }

    /** Unique in a file; given when another sample names it as parent. */
    public String id;
    /** The id of the sample it ran inside, in run data. The in-process paths name the parent to the feed. */
    public String parent;
    public Link link = Link.TRANSACTION;
    public Kind kind = Kind.REQUEST;
    public String name;
    /** Start, epoch milliseconds. */
    public long start;
    /** The tool's own duration, ms. Null only for a sample with children, whose children decide it. */
    public Long elapsed;
    /** JMeter's IdleTime, ms. Wins over {@link #wallTime} when both are given. */
    public Long idle;
    /** A transaction's whole duration, think time included, ms. */
    public Long wallTime;
    /** Null only for a sample with children, which fails when a direct child failed. */
    public Boolean success;
    public String code;
    public String message;
    public String failureMessage;
    public String user;
    public Integer iteration;
    /** Null: true when the sample has no parent, else false. */
    public Boolean topLevel;
    /** The samples a result stands for (JMeter's SampleCount): 1 for every result but an aggregated one. */
    public long samples = 1;
    /** Its failures (JMeter's ErrorCount). Null: 0 for a success, 1 for a failure. */
    public Long errors;
    public Long bytes;
    public Long sentBytes;
    public Long latency;
    public Long connect;
    /** Active threads when it ended. Null: none reported, and the engine is not told. */
    public Integer threads;
    public String url;
    public String method;
    /** A tool's timing parts in ms (k6's blocked, connecting, tlsHandshaking, sending, waiting, receiving). */
    public Map<String, Long> timings;
    /** A failed sample's response body, as the report carries it (decision 11). */
    public String responseBody;
    /**
     * Its end, epoch ms, when the producer knows it exactly (the JMeter paths). Null: start + elapsed + idle. Not in
     * run data, which carries idle instead.
     */
    public Long end;

    public RunSample() {
    }

    public RunSample(String name, long start, long elapsed, boolean success) {
        this.name = name;
        this.start = start;
        this.elapsed = elapsed;
        this.success = success;
    }

    /** Adds one timing part, keeping the order the producer gives. */
    public RunSample timing(String part, long millis) {
        if (timings == null) {
            timings = new LinkedHashMap<>();
        }
        timings.put(part, millis);
        return this;
    }

    public boolean isTransaction() {
        return kind == Kind.TRANSACTION;
    }

    /** Where it ends: the producer's exact end, else start + elapsed + idle. */
    public long endTime() {
        if (end != null) {
            return end;
        }
        return start + Math.max(0, elapsed == null ? 0 : elapsed) + Math.max(0, idle == null ? 0 : idle);
    }

    @Override
    public String toString() {
        return "RunSample{" + name + " @" + start + " +" + elapsed + (success == null ? "" : success ? " ok" : " FAILED")
                + (parent == null ? "" : " in " + parent) + "}";
    }
}
