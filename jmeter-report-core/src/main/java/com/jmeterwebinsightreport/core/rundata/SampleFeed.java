package com.jmeterwebinsightreport.core.rundata;

import com.jmeterwebinsightreport.core.stats.LiveStatisticsEngine;

/**
 * The one way a sample reaches the statistics engine (spec 001, research R3; constitution VII).
 *
 * <p>The JTL parser, the listener, the run-data reader and every tool adapter call this, so one run's samples give one
 * report whichever path carried them. Before it, the JTL parser and the listener each called the engine their own way,
 * and kept equal only by R07 and the fixtures. The defaults below are the ones both paths applied:
 * bytes 0, connect and latency -1, one sample, one failure for a failed sample.
 */
public final class SampleFeed {

    private SampleFeed() {
    }

    /**
     * Feeds one sample whose elapsed time and success are known (a transaction's are worked out before, from its
     * children, by the run-data reader).
     *
     * @param parentName the name of the sample it ran inside, or null. The engine records the relation by name, as a
     *        transaction ({@link RunSample.Link#TRANSACTION}) or as a request's sub-result.
     * @throws IllegalArgumentException when the sample has no elapsed time or no success
     */
    public static void feed(LiveStatisticsEngine engine, RunSample s, String parentName) {
        if (s.elapsed == null || s.success == null) {
            throw new IllegalArgumentException("a sample reaches the engine with its elapsed time and success known: "
                    + s);
        }
        long elapsed = s.elapsed;
        boolean success = s.success;
        boolean topLevel = s.topLevel != null ? s.topLevel : parentName == null;
        long samples = Math.max(1, s.samples);
        long errors = s.errors != null ? s.errors : (success ? 0 : 1);
        long endOfElapsed = LiveStatisticsEngine.endOf(s.start, elapsed);

        engine.recordSample(s.name, elapsed, success,
                s.bytes != null ? s.bytes : 0,
                s.sentBytes != null ? s.sentBytes : 0,
                s.start, s.endTime(),
                s.connect != null ? s.connect : -1,
                s.latency != null ? s.latency : -1,
                samples, errors, topLevel);
        if (s.threads != null) {
            engine.updateActiveThreads(s.threads, endOfElapsed);
        }
        if (s.isTransaction()) {
            // Left out of the Errors tab, as JMeter's HTML report leaves a transaction's own result out (E3)
            engine.recordTransactionRow(samples);
        } else {
            // and out of the response codes, as its Codes per Second does (T5)
            engine.recordResponseCode(s.code, endOfElapsed);
        }
        if (!success) {
            engine.recordError(s.name, s.code, s.message, s.failureMessage, s.start, s.user, s.url, null,
                    s.responseBody, topLevel, s.errors != null ? s.errors : 1, s.isTransaction());
        }
        if (parentName != null) {
            // Nested as the listener nests a parent sample's sub-results: the transaction counts once in the totals,
            // its requests are its breakdown (B5); a request's own redirects nest under it, not as a transaction
            engine.recordTransaction(parentName, s.name, s.link == RunSample.Link.TRANSACTION);
        }
    }
}
