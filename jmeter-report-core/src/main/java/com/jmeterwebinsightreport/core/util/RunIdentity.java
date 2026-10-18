package com.jmeterwebinsightreport.core.util;

import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.model.TestMetadata;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * A short, stable identifier for one test run, carried by every artifact that run produces so
 * they can be checked against each other.
 *
 * <p><b>Why identity and not just a better filename.</b> Deriving the payload's name from the
 * report's name makes a mismatched pair unlikely; it does not make one detectable. Two large
 * reports written to one directory used to share a single payload, and the resulting page showed
 * run 1 in its server-rendered header cards and sampler table while every chart and every sampler
 * filter checkbox came from run 2 — so the report listed filter controls for samplers absent from
 * its own table. Nothing detected it, and nothing could: the template's only guard tested
 * {@code window.__WIR_DATA__} for presence, never for identity.
 *
 * <p><b>Derived, not generated.</b> Both halves compute this from the same {@link ReportData}
 * rather than being handed a value, so they cannot be plumbed inconsistently — and equality of the
 * id is exactly the property worth asserting. A random UUID would have needed threading through
 * the serializer, the renderer and both ingestion paths, with a bug in any one of them producing a
 * false mismatch warning, which is worse than no warning at all.
 *
 * <h2>The identity contract</h2>
 *
 * <p><b>Same JTL ⇒ same runId. Distinct executions ⇒ distinct runId.</b>
 *
 * <p>This is a stated contract, not an accident of the fields that happen to be hashed. It was
 * verified empirically before it was written down: a listener run and a {@code jmeter -g}
 * re-export of that run's own JTL both derived the same id. Two things depend on it:
 *
 * <ul>
 *   <li>The exists-policy. Both ingestion paths decide "is the report already sitting here mine,
 *       or someone else's?" by comparing this id. If a re-export derived a <em>different</em> id
 *       from the run it is re-exporting, that policy degrades into always-overwrite; if two
 *       genuinely different runs collided, it would silently skip generating a report — the exact
 *       failure class this whole mechanism exists to remove.</li>
 *   <li>The report's fatal payload check. A false mismatch replaces a perfectly good page with an
 *       error.</li>
 * </ul>
 *
 * <p>Both directions have a test behind them, and they fail loudly rather than degrading, because
 * a metadata change made for an unrelated reason is exactly how this contract would break.
 *
 * <h2>Why 64 bits, and why SHA-256</h2>
 *
 * <p>It was 32 bits of {@code String.hashCode}. Pairwise that is a collision about one time in
 * 4.3 × 10⁹, which is fine when a collision merely means "these two runs look identical in every
 * headline number, so a mismatched pair is not worth warning about" — the property the narrow id
 * originally had. It stopped being fine once the exists-policy started keying on the value:
 * there, a collision means <em>silently skipping a report</em>. Widening costs eight characters in
 * four files and one hash per report, and nothing external consumes the value yet, so this is the
 * only free moment to do it.
 *
 * <p>SHA-256 rather than a hand-rolled hash so "same input ⇒ same id, on every JVM, forever" is
 * guaranteed by a published standard instead of by our own code continuing to be right. The input
 * is encoded <b>explicitly as UTF-8</b>: the platform default charset would otherwise make the id
 * depend on the machine that produced it, which breaks the contract above in the one deployment
 * that needs it most — a Linux container re-exporting a JTL captured on a Windows workstation.
 *
 * <p>This is not a checksum of the payload. Hashing megabytes of chart data at the end of a soak
 * run would cost real time and buy nothing: the question is "which run is this", not "were the
 * bytes damaged".
 */
public final class RunIdentity {

    /** Hex characters in a resolved id — 64 bits. */
    public static final int LENGTH = 16;

    /**
     * What {@link #of} returns when there is no run to identify at all.
     *
     * <p>Deliberately not hex, and deliberately not empty: every consumer treats an empty id as
     * "this artifact predates identity" and skips the comparison, whereas this states that
     * identity was asked for and could not be derived.
     */
    public static final String UNKNOWN = "unknown";

    private RunIdentity() {
    }

    /**
     * Sixteen hex characters over the fields that differ between runs: test name, start time,
     * duration and the top-level sample and error counts.
     *
     * <p>Start time carries the entropy. It is an {@link java.time.Instant}, so it is
     * millisecond-precise — two parallel CI shards would have to start within the same
     * millisecond to collide, which is a much stronger separation than the seconds-resolution
     * {@code ${timestamp}} filename placeholder provides.
     */
    public static String of(ReportData reportData) {
        if (reportData == null) {
            return UNKNOWN;
        }
        return hash(fingerprint(reportData));
    }

    /**
     * The exact bytes-before-hashing, as a String.
     *
     * <p>Package-visible so a test can assert on the <em>material</em> rather than only on the
     * digest. A test that only ever compares two ids cannot tell "the fields are wired up
     * correctly" from "both sides are equally broken".
     */
    static String fingerprint(ReportData reportData) {
        TestMetadata meta = reportData.getMetadata();
        return new StringBuilder()
                .append(meta != null && meta.getTestName() != null ? meta.getTestName() : "")
                .append('|')
                .append(meta != null ? meta.getStartTime() : null)
                .append('|')
                .append(meta != null ? meta.getDurationMillis() : 0L)
                .append('|')
                .append(reportData.getTopLevelSampleCount())
                .append('|')
                .append(reportData.getTopLevelErrorCount())
                .toString();
    }

    /**
     * The same digest over arbitrary material — sixteen hex characters, stable across JVMs.
     *
     * <p>Public for the two other identities the server-metrics work needs: a
     * {@code collectionId} over the sources, queries and window a collection ran against, and
     * the filename a {@code file} source stores a recorded query under (a query is full of
     * characters a filename cannot hold). One digest, so "what does a 16-hex id look like" has
     * one answer everywhere. It is NOT {@link #of}: the run's identity is still that method's
     * material and nothing else.
     */
    public static String digest(String material) {
        return hash(material == null ? "" : material);
    }

    /** SHA-256 of the UTF-8 bytes, first 8 bytes, lower-case hex. */
    private static String hash(String material) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // Every conformant JRE ships SHA-256 (it is on the MessageDigest required-algorithm
            // list), so this is unreachable rather than merely unlikely. Declaring a checked
            // exception on a method called from the write path of every report would push a
            // catch block into four call sites to handle something that cannot happen.
            throw new IllegalStateException("SHA-256 is required of every Java platform", e);
        }
        StringBuilder hex = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH / 2; i++) {
            hex.append(Character.forDigit((digest[i] >> 4) & 0xF, 16));
            hex.append(Character.forDigit(digest[i] & 0xF, 16));
        }
        return hex.toString();
    }
}
