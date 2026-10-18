package com.jmeterwebinsightreport.report.servermetrics;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmeterwebinsightreport.core.servermetrics.collect.ServerMetricsPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.function.Function;

/**
 * Reads {@code server-metrics.json} for the generator — the {@code AnnotationLoader} of the
 * server side, under the same contract: what was loaded, and what went wrong loading it.
 *
 * <p>Three ways to get a problem rather than a payload — a config error when an enabled assert
 * sits in a gate that builds, a warning otherwise ({@code ServerGateEvaluation.fatalPayloadProblem},
 * A1-7): a
 * configured path that does not exist, a file that will not parse or carries another schema,
 * and a payload whose {@code runId} is not this run's.
 * The last is refused outright rather than rendered under a banner: a foreign payload drawn
 * under this run's headings is the bug {@code RunIdentity} exists to stop.
 *
 * <p>No auto-detection. Under {@code -g -o} the output directory must be empty, so there is
 * nothing to detect in; the path comes from {@code -Jwebinsight.report.servermetrics}.
 */
public final class ServerMetricsPayloadLoader {

    /**
     * Why a configured payload was refused — the machine-readable half of {@link Result#problem()}.
     * It travels to the page, which has to say "refused, and why" rather than "none was given":
     * the second sentence sent a reader whose title did not match the payload's to re-run the very
     * command that had just been refused.
     */
    public enum Refusal {
        /** The configured path does not exist. */
        MISSING,
        /** The file would not parse as a payload. */
        UNREADABLE,
        /** It parsed, and declares another schema. */
        WRONG_SCHEMA,
        /** It belongs to another run: another JTL, or the same JTL under another test name. */
        FOREIGN_RUN,
        /** A series carries a status this version does not know. */
        UNKNOWN_STATUS
    }

    public static final class Result {
        private final ServerMetricsPayload payload;
        private final Refusal refusal;
        private final String problem;
        private final String detail;

        Result(ServerMetricsPayload payload, Refusal refusal, String problem, String detail) {
            this.payload = payload;
            this.refusal = refusal;
            this.problem = problem;
            this.detail = detail;
        }

        /** Nothing configured: no payload and nothing wrong. */
        public static Result none() {
            return new Result(null, null, null, null);
        }

        /** The payload, or {@code null} when none was configured or it could not be used. */
        public ServerMetricsPayload payload() { return payload; }

        /** Why a configured payload was refused, or {@code null} when none was configured or it was accepted. */
        public Refusal refusal() { return refusal; }

        /**
         * One sentence naming the file and what is wrong with it, or {@code null} — for the
         * console and the exit reason: the absolute path, and for {@link Refusal#UNREADABLE} the
         * parser's own message.
         */
        public String problem() { return problem; }

        /**
         * The same sentence as it may be PUBLISHED, into the report and its sidecar, or
         * {@code null}: the file's name rather than its absolute path, and for
         * {@link Refusal#UNREADABLE} a fixed sentence rather than the parser's message.
         *
         * <p>The report is archived and passed around; the console is not. An absolute path
         * carries the user's home directory, and a parser message quotes the offending token —
         * from a file that, being unreadable, is by definition not the collector's redacted
         * output, so a {@code -J} path pointed at the wrong file would put its first token into
         * every copy of the report.
         */
        public String detail() { return detail; }

        public boolean present() { return payload != null; }
    }

    private static final Logger log = LoggerFactory.getLogger(ServerMetricsPayloadLoader.class);
    private final ObjectMapper objectMapper;

    public ServerMetricsPayloadLoader() {
        this.objectMapper = new ObjectMapper();
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        // One JSON value per file, as AnnotationLoader reads one (SF4): Jackson otherwise stops
        // after the first and ignores whatever follows, so two payloads concatenated, or a `>>`
        // append, loaded the first in silence. Trailing content is UNREADABLE.
        objectMapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /**
     * @param file  the configured payload, or null when none was configured
     * @param runId this run's identity, which the payload must carry
     */
    public Result read(File file, String runId) {
        if (file == null) {
            return Result.none();
        }
        if (!file.exists()) {
            return refuse(Refusal.MISSING, file, where -> "the server-metrics payload does not exist: " + where
                    + " — no server assert was judged");
        }
        ServerMetricsPayload payload;
        try {
            payload = objectMapper.readValue(file, ServerMetricsPayload.class);
        } catch (Exception e) {
            return refuse(Refusal.UNREADABLE, file,
                    where -> "the server-metrics payload could not be read: " + where + " — " + e.getMessage(),
                    where -> "the server-metrics payload could not be read: " + where + " — it is not a JSON "
                            + "payload wir-collect wrote. The parser's reason is in the run's console output");
        }
        String schema = payload.getSchema();
        if (!ServerMetricsPayload.SCHEMA.equals(schema)) {
            return refuse(Refusal.WRONG_SCHEMA, file, where -> "the server-metrics payload " + where
                    + " declares schema '" + schema + "', not '" + ServerMetricsPayload.SCHEMA
                    + "' — was it written by a different plugin version?");
        }
        if (runId != null && !runId.equals(payload.getRunId())) {
            // Two causes, and the commoner one is not "a different JTL": the run identity hashes
            // the test name, which on the -g -o path is the report title. A -Jwebinsight.report.title
            // without the same --test-name refuses a payload collected from this very JTL, and a
            // sentence naming only the JTL sent the reader to re-run the command that had just
            // produced it.
            String theirs = payload.getRunId();
            return refuse(Refusal.FOREIGN_RUN, file, where -> "the server-metrics payload " + where
                    + " belongs to run " + theirs + ", and this is run " + runId
                    + " — it was collected from a different JTL, or from this one under a different "
                    + "test name. The run identity includes the test name, which is the report title: "
                    + "wir-collect's --test-name must be the same text as -Jwebinsight.report.title "
                    + "(leave both unset to use the default). Re-run wir-collect against this run's "
                    + "results with the matching name");
        }
        // Every series status must be one this version knows: a hand-edited file or a newer
        // writer's word is a problem here, never an IllegalArgumentException in the evaluator.
        for (ServerMetricsPayload.SeriesRecord s : payload.getSeries()) {
            try {
                s.collectionStatus();
            } catch (IllegalArgumentException e) {
                String status = s.getStatus();
                return refuse(Refusal.UNKNOWN_STATUS, file, where -> "the server-metrics payload " + where
                        + " carries a series status '" + status + "' this version does not know — "
                        + "was it written by a different plugin version, or edited by hand?");
            }
        }
        log.info("Loaded server-metrics payload from {}: {} source(s), {} series, collection {}",
                file.getAbsolutePath(), payload.getSources().size(), payload.getSeries().size(),
                payload.getCollectionId());
        return new Result(payload, null, null, null);
    }

    private static Result refuse(Refusal refusal, File file, Function<String, String> sentence) {
        return refuse(refusal, file, sentence, sentence);
    }

    /**
     * Each sentence is built around where it names the file: the absolute path on the console,
     * the quoted file name in the report ({@link Result#detail()}).
     *
     * @param console   the sentence for the console and the exit reason
     * @param published the sentence for the report and its sidecar
     */
    private static Result refuse(Refusal refusal, File file, Function<String, String> console,
                                 Function<String, String> published) {
        String problem = console.apply(file.getAbsolutePath());
        // WARN, not ERROR: whether this fails the run is not the loader's to say. It is a config
        // error only when an enabled assert sits in a gate that builds
        // (ServerGateEvaluation.fatalPayloadProblem, A1-7), and then the CONFIG ERROR line and an
        // ERROR finding say so; otherwise it is a WARNING finding. An ERROR here would put "error"
        // in the log of a run that, by decision, has none.
        log.warn("{}", problem);
        return new Result(null, refusal, problem, published.apply("'" + file.getName() + "'"));
    }
}
