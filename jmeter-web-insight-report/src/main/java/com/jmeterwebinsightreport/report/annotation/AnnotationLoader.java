package com.jmeterwebinsightreport.report.annotation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer;
import com.fasterxml.jackson.databind.deser.std.StringDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

/**
 * Loads user-defined annotations from a companion JSON file
 * for display on report charts and the Notes tab.
 *
 * <h2>A failure here is not a quiet one</h2>
 *
 * <p>Two paths through this class used to end in {@code log.warn} and an empty document: a
 * configured file that does not exist, and one that could not be parsed. Both mean the gate a
 * tester configured did not run, and neither reached a single artifact — not the CI/CD JSON,
 * not the HTML, not the JUnit XML. The report said {@code assertsEvaluated: false}, which is
 * what a run with no annotations at all says, so a typo'd path and a deliberate no-gate run
 * were indistinguishable everywhere except a log file.
 *
 * <p>{@link Result} carries the problem out instead. It becomes a finding, which is already
 * wired to every one of those surfaces, and {@link Result#unusable} says whether the run should
 * be treated as failed.
 */
public class AnnotationLoader {

    /**
     * What was loaded, and what went wrong loading it.
     *
     * <p>{@code unusable} is deliberately narrower than "there were findings": a file with one
     * bad rule among nine is usable and its findings are advisory. A file that is missing or
     * unparseable produced NO rules at all, and the run is gating on nothing.
     */
    public static final class Result {
        private final ReportAnnotations annotations;
        private final String problem;

        Result(ReportAnnotations annotations, String problem) {
            this.annotations = annotations;
            this.problem = problem;
        }

        public ReportAnnotations annotations() { return annotations; }

        /** One sentence naming the file and what is wrong with it, or {@code null}. */
        public String problem() { return problem; }

        /** True when a file was configured and nothing could be read from it. */
        public boolean unusable() { return problem != null; }
    }

    private static final Logger log = LoggerFactory.getLogger(AnnotationLoader.class);
    private final ObjectMapper objectMapper;

    public AnnotationLoader() {
        this.objectMapper = newObjectMapper();
    }

    /**
     * The mapper an annotations file is read with — here, and by every test that claims to read a
     * file "as the run does" (the parity suites).
     *
     * <p>One rule beyond Jackson's defaults: a NUMBER written where the model takes text — a
     * {@code source}, a {@code gate}, a target's {@code scope} — is read as its VALUE, written as
     * {@link ServerMetricsMapper#numberText} writes it, not as its literal. Jackson kept the
     * literal ({@code "2.0"} for {@code 2.0}, {@code "1e-7"} for {@code 1e-7}); the page reads the
     * same file through JSON.parse, which keeps only the value, so a source written {@code 2.0}
     * was one source to the run and another to the board (review follow-up to M7d X). report.js
     * reads such a field through {@code WIR.javaText.text}, by the same rule.
     *
     * <p>And a file is ONE JSON value. Jackson stops after the first root value unless told
     * otherwise, so {@code cat a.json b.json} — or a {@code >>} append, or a stray closing brace
     * left by a bad merge — loaded {@code a.json} and dropped the rest in silence: the second
     * document's rules never gated, and the run exited 0 under {@code failonassert} while the
     * page, reading the same file through JSON.parse, refused it (SF4). Trailing content is now
     * the parse failure it is, "the annotations file could not be read", and {@code failonerror}
     * applies.
     */
    public static ObjectMapper newObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        SimpleModule text = new SimpleModule("wir-annotations-text");
        text.addDeserializer(String.class, new NumberAsValueText());
        mapper.registerModule(text);
        return mapper;
    }

    /** A text field: a number by its value (numberText), anything else as Jackson reads text. */
    private static final class NumberAsValueText extends StdScalarDeserializer<String> {
        private static final long serialVersionUID = 1L;

        NumberAsValueText() {
            super(String.class);
        }

        @Override
        public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            if (p.hasToken(JsonToken.VALUE_NUMBER_INT) || p.hasToken(JsonToken.VALUE_NUMBER_FLOAT)) {
                return ServerMetricsMapper.numberText(p.getNumberValue());
            }
            return StringDeserializer.instance.deserialize(p, ctxt);
        }
    }

    /**
     * Load annotations from the given JSON file.
     *
     * @param annotationFile path to the report-annotations.json file
     * @return parsed annotations, or empty annotations if file doesn't exist or fails
     */
    public ReportAnnotations load(File annotationFile) {
        return read(annotationFile).annotations();
    }

    /**
     * Load, and say what went wrong rather than only logging it.
     *
     * @param annotationFile path to the report-annotations.json file, or null when none was
     *        configured and auto-detection found none
     */
    public Result read(File annotationFile) {
        if (annotationFile == null) {
            // Auto-detection looked and found nothing. Ordinary, and not worth a warning —
            // but it must still name where it looked, because the old message ("No annotation
            // file found") did not, so even -Lcom.jmeterwebinsightreport=DEBUG could not tell
            // you which directory was searched.
            log.debug("No annotations file configured or auto-detected; "
                    + "using empty annotations (no assertions, no markers, no notes)");
            return new Result(new ReportAnnotations(), null);
        }
        if (!annotationFile.exists()) {
            // A non-null file that does not exist can only have come from
            // -Jwebinsight.report.annotations: both auto-detection sites set the field ONLY
            // after exists() returns true. So this is an operator typo, and staying quiet
            // about it silently disables the gate — no rule is declared, the CI/CD
            // JSON reports UNKNOWN, and the JUnit XML reports every sampler as passing. That
            // is precisely the failure the baseline-JTL warning in WebInsightReportGenerator
            // was added to prevent; this is the same mistake in the other file.
            String problem = "the annotations file does not exist: "
                    + annotationFile.getAbsolutePath()
                    + " — nothing was declared, so nothing was judged";
            log.error("{}", problem);
            return new Result(new ReportAnnotations(), problem);
        }

        try {
            log.info("Loading annotations from: {}", annotationFile.getAbsolutePath());
            ReportAnnotations annotations =
                    objectMapper.readValue(annotationFile, ReportAnnotations.class);
            // A legacy loadValidity block becomes an ordinary assertions[] entry here, so a
            // caller that only reads the document sees the same shape the gate will judge.
            // Idempotent; GateEvaluation calls it again because that is the one place a run is
            // judged and nothing may reach the evaluator unmigrated.
            annotations.migrateLegacyBlocks();
            return new Result(annotations, null);
        } catch (Exception e) {
            // A parse failure is louder than a missing file, not quieter: the tester wrote the
            // file, so they believe the gate is running. The message keeps the parser's own
            // text because it names the line and column, which is the only actionable part.
            String problem = "the annotations file could not be read: "
                    + annotationFile.getAbsolutePath() + " — " + e.getMessage();
            log.error("{}", problem);
            return new Result(new ReportAnnotations(), problem);
        }
    }
}
