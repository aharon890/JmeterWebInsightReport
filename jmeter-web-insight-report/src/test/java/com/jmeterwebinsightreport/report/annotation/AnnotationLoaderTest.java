package com.jmeterwebinsightreport.report.annotation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AnnotationLoaderTest {

    private AnnotationLoader loader;

    @BeforeEach
    void setUp() {
        loader = new AnnotationLoader();
    }

    private File getResourceFile(String name) {
        URL url = getClass().getClassLoader().getResource("annotations/" + name);
        assertNotNull(url, "Test resource not found: annotations/" + name);
        // toURI, not getFile: getFile keeps the URL's %20, so a checkout under a path with a space
        // found no resource at all.
        try {
            return new File(url.toURI());
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void loadFromFile_parsesCompleteAnnotations() {
        ReportAnnotations annotations = loader.load(getResourceFile("full-annotations.json"));

        assertNotNull(annotations);
        assertEquals("1.0", annotations.getVersion());
        assertNotNull(annotations.getTestNotes());
        assertFalse(annotations.getTestNotes().isEmpty());
        assertNotNull(annotations.getSamplerNotes());
        assertFalse(annotations.getSamplerNotes().isEmpty());
        assertNotNull(annotations.getTimelineMarkers());
        assertFalse(annotations.getTimelineMarkers().isEmpty());
    }

    @Test
    void loadFromFile_parsesMinimalAnnotations() {
        ReportAnnotations annotations = loader.load(getResourceFile("minimal-annotations.json"));

        assertNotNull(annotations);
        assertEquals("1.0", annotations.getVersion());
        // Minimal file has only version; other fields should be defaults
        assertTrue(annotations.getTimelineMarkers().isEmpty());
        assertTrue(annotations.getSamplerNotes().isEmpty());
        assertTrue(annotations.getAssertions().isEmpty());
    }

    /**
     * A key this version does not recognise loads, survives, and declares nothing.
     *
     * <p>Rejecting the whole file would throw away the notes, markers and any real assertions
     * beside it, which is a worse outcome than one key nobody understands. So an unrecognised
     * block is kept verbatim for the round trip, and is never turned into a rule.
     */
    @Test
    void anUnrecognisedBlockLoadsAndDeclaresNothing() {
        ReportAnnotations annotations = loader.load(getResourceFile("full-annotations.json"));

        assertNotNull(annotations);
        assertTrue(annotations.getAssertions() == null || annotations.getAssertions().isEmpty(),
                "an unrecognised block must not become assertions behind the reader's back");
        // The rest of the file survives it.
        assertNotNull(annotations.getTestNotes());
        assertFalse(annotations.getTimelineMarkers().isEmpty());
    }

    /**
     * A number written where the model takes text is read by its VALUE, as the page reads it
     * (M7d X review follow-up). Jackson kept the literal — "2.0", "1e-7" — and JSON.parse cannot,
     * so a source written {@code 2.0} was the source '2.0' to the run and '2' to the board. The
     * parity vectors pin the sentences through {@link AnnotationLoader#newObjectMapper()}; this
     * pins that a file the run READS goes through it too.
     */
    @Test
    void aNumberInATextFieldIsReadByItsValue(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("report-annotations.json");
        Files.write(file, ("{\"assertions\": [{\"id\": 2.0, \"target\": {\"scope\": 1e-7},"
                + " \"metric\": \"p95\", \"fail\": {\"gt\": 2.0}}],"
                + " \"serverMetrics\": {\"components\": {\"s\": {\"type\": 12345678.5}},"
                + " \"asserts\": [{\"component\": 2.0, \"metric\": 7, \"query\": \"up\"}]}}")
                .getBytes(StandardCharsets.UTF_8));

        AnnotationLoader.Result result = loader.read(file.toFile());

        assertNull(result.problem(), "the file loads");
        ReportAnnotations a = result.annotations();
        assertEquals("2", a.getAssertions().get(0).getId());
        assertEquals("1.0E-7", a.getAssertions().get(0).getTarget().getScope());
        assertEquals(2.0, ((Number) a.getAssertions().get(0).getFail().get("gt")).doubleValue(),
                "a band value is a number, not text, and is untouched");
        assertEquals("1.23456785E7", a.getServerMetrics().getComponents().get("s").getType());
        assertEquals("2", a.getServerMetrics().getAsserts().get(0).getComponent());
        assertEquals("7", a.getServerMetrics().getAsserts().get(0).getMetric());
    }

    @Test
    void loadFromFile_handlesMissingFile() {
        // AnnotationLoader.load returns empty annotations for non-existent file
        ReportAnnotations annotations = loader.load(new File("/nonexistent/path/annotations.json"));

        assertNotNull(annotations, "Should return empty annotations, not null");
        assertEquals("1.0", annotations.getVersion());
        assertTrue(annotations.getTimelineMarkers().isEmpty());
    }

    @Test
    void loadFromFile_handlesNullFile() {
        ReportAnnotations annotations = loader.load(null);

        assertNotNull(annotations, "Should return empty annotations for null file");
        assertEquals("1.0", annotations.getVersion());
    }

    @Test
    void loadFromFile_handlesInvalidJson() {
        // AnnotationLoader.load returns empty annotations on parse failure (does not throw)
        ReportAnnotations annotations = loader.load(getResourceFile("invalid-annotations.json"));

        assertNotNull(annotations, "Should return empty annotations for invalid JSON");
        assertEquals("1.0", annotations.getVersion());
    }

    @Test
    void aMalformedServerBlockCostsTheServerSideOnlyAndTheClientRulesStillLoad(@TempDir Path dir) throws Exception {
        // "asserts": {} does not bind to a list. It used to fail the whole file: the client rule
        // was lost, the gate read "nothing was declared" and the run exited 1. The server tab is a
        // bonus on the client report, so the block is carried as one server-side problem instead.
        Path file = dir.resolve("a.json");
        Files.write(file, ("{\"assertions\":[{\"id\":\"p95\",\"target\":{\"scope\":\"overall\"},\"metric\":\"p95\","
                + "\"fail\":{\"gt\":1000}}],"
                + "\"serverMetrics\":{\"components\":{\"db\":{\"type\":\"custom\"}},\"asserts\":{}}}")
                .getBytes(StandardCharsets.UTF_8));
        AnnotationLoader.Result r = loader.read(file.toFile());
        assertFalse(r.unusable(), String.valueOf(r.problem()));
        assertEquals(1, r.annotations().getAssertions().size(), "the client rule is read");
        ServerMetricsJson block = r.annotations().getServerMetrics();
        assertNotNull(block);
        assertTrue(block.getUnreadable().startsWith("the serverMetrics block could not be read — "),
                block.getUnreadable());
        // Carried verbatim for a Download.
        assertTrue(block.getUnknownKeys().containsKey("asserts"));
        assertTrue(block.getUnknownKeys().containsKey("components"));
        // And refused as one block problem: nothing under it is judged.
        ServerMetricsMapper.Result mapped = new ServerMetricsMapper().map(block);
        assertTrue(mapped.config().kpis().isEmpty());
        assertEquals(1, mapped.config().errorCount());
    }

    @Test
    void aSyntaxErrorStillLosesTheWholeFile(@TempDir Path dir) throws Exception {
        // One file cannot be half-parsed: a client rule beside a broken brace is lost too.
        Path file = dir.resolve("a.json");
        Files.write(file, "{\"assertions\":[], \"serverMetrics\": {\"asserts\": [}"
                .getBytes(StandardCharsets.UTF_8));
        assertTrue(loader.read(file.toFile()).unusable());
    }

    @Test
    void aFileIsOneJsonValueAndAnythingAfterItIsRefusedNotDropped(@TempDir Path dir) throws Exception {
        // SF4. Jackson stopped after the first value, so `cat a.json b.json` (or a >> append)
        // loaded a.json, dropped b.json's rules without a word, and the run exited 0 under
        // failonassert - while the page, reading the same file through JSON.parse, refused it.
        String first = "{\"assertions\":[{\"id\":\"a\",\"target\":{\"scope\":\"overall\"},\"metric\":\"p95\","
                + "\"fail\":{\"gt\":1000}}]}";
        String second = "{\"assertions\":[{\"id\":\"load:min\",\"target\":{\"scope\":\"overall\"},"
                + "\"metric\":\"throughput\",\"fail\":{\"lt\":100000}}]}";
        for (String trailing : new String[] {"\n" + second + "\n", "\n}\n"}) {
            Path file = dir.resolve("concatenated.json");
            Files.write(file, (first + trailing).getBytes(StandardCharsets.UTF_8));
            AnnotationLoader.Result r = loader.read(file.toFile());
            assertTrue(r.unusable(), "trailing content must not load as the first document: " + trailing);
            assertTrue(r.problem().startsWith("the annotations file could not be read: " + file.toFile().getPath()),
                    r.problem());
            // The parser's own reason, which names the line: a second document is a trailing
            // token, a stray brace an unexpected close marker.
            assertTrue(r.problem().contains(trailing.contains("load:min") ? "Trailing token" : "Unexpected close marker"),
                    r.problem());
            assertTrue(r.problem().contains("(line 2,"), r.problem());
            // Jackson's source description is a local path; it never reaches the published text.
            assertFalse(r.problem().contains("(File)"), r.problem());
        }
        // ...and trailing whitespace is not content.
        Path spaced = dir.resolve("spaced.json");
        Files.write(spaced, (first + "\n\n  \t\n").getBytes(StandardCharsets.UTF_8));
        AnnotationLoader.Result ok = loader.read(spaced.toFile());
        assertFalse(ok.unusable(), ok.problem());
        assertEquals(1, ok.annotations().getAssertions().size());
    }

    /**
     * A rule's warn or fail that is not an object refuses the whole file, and null or {} is no band
     * (CN21). This is the Java half the page's client slice now mirrors when it is handed a
     * document (asserts-write-back.test.js, document-replace.test.js): a band binds to a Map, so a
     * string — even "" — a number, a boolean or an array is a file the run cannot load.
     */
    @Test
    void aBandThatIsNotAnObjectRefusesTheFile(@TempDir Path dir) throws Exception {
        String[][] shapes = {{"fail", "\"soon\""}, {"fail", "[]"}, {"fail", "5"}, {"warn", "\"\""},
                {"warn", "true"}};
        for (String[] shape : shapes) {
            // The band under test beside a good one of the other kind, each key once.
            String other = "fail".equals(shape[0]) ? "\"warn\":{\"gt\":1}" : "\"fail\":{\"gt\":100}";
            Path file = dir.resolve("band.json");
            Files.write(file, ("{\"assertions\":[{\"id\":\"r\",\"target\":{\"sampler\":\"A\"},"
                    + "\"metric\":\"p95\"," + other + ",\"" + shape[0] + "\":" + shape[1] + "}]}")
                    .getBytes(StandardCharsets.UTF_8));
            AnnotationLoader.Result r = loader.read(file.toFile());
            assertTrue(r.unusable(), shape[0] + " " + shape[1] + " loaded: " + r.annotations().getAssertions());
            assertTrue(r.problem().startsWith("the annotations file could not be read: "), r.problem());
        }
        for (String none : new String[] {"null", "{}"}) {
            Path file = dir.resolve("none.json");
            Files.write(file, ("{\"assertions\":[{\"id\":\"r\",\"target\":{\"sampler\":\"A\"},"
                    + "\"metric\":\"p95\",\"warn\":{\"gt\":1},\"fail\":" + none + "}]}")
                    .getBytes(StandardCharsets.UTF_8));
            AnnotationLoader.Result r = loader.read(file.toFile());
            assertFalse(r.unusable(), none + ": " + r.problem());
            assertEquals(1, r.annotations().getAssertions().size(), none);
        }
    }

    /**
     * The legacy {@code loadValidity} block, and its {@code warn} and {@code fail}, bind to beans
     * (LoadValidityConfig, BandConfig), so a value that is not an object refuses the whole file, and
     * null is no block or no band (REV-2). This is the Java half the page's client slice mirrors when
     * it is handed a document (asserts-write-back.test.js): it read such a block or band as absent,
     * judged the file and offered to save it, while the run could not load it.
     */
    @Test
    void aLoadValidityBlockOrBandThatIsNotAnObjectRefusesTheFile(@TempDir Path dir) throws Exception {
        String[] blocks = {"\"soon\"", "\"\"", "5", "true", "[]",
                "{\"target\":10,\"fail\":\"soon\"}", "{\"target\":10,\"fail\":[]}",
                "{\"target\":10,\"warn\":5}", "{\"target\":10,\"warn\":\"\"}", "{\"target\":10,\"warn\":true}"};
        for (String block : blocks) {
            Path file = dir.resolve("legacy.json");
            Files.write(file, ("{\"loadValidity\":" + block + "}").getBytes(StandardCharsets.UTF_8));
            AnnotationLoader.Result r = loader.read(file.toFile());
            assertTrue(r.unusable(), "loadValidity " + block + " loaded");
            assertTrue(r.problem().startsWith("the annotations file could not be read: "), r.problem());
        }
        for (String block : new String[] {"null", "{}", "{\"target\":10,\"fail\":null}",
                "{\"target\":10,\"warn\":null,\"fail\":{\"tolerance\":0.5}}"}) {
            Path file = dir.resolve("legacy-none.json");
            Files.write(file, ("{\"loadValidity\":" + block + "}").getBytes(StandardCharsets.UTF_8));
            AnnotationLoader.Result r = loader.read(file.toFile());
            assertFalse(r.unusable(), block + ": " + r.problem());
        }
    }

    @Test
    void loadFromFile_parsesTimelineMarkers() {
        ReportAnnotations annotations = loader.load(getResourceFile("full-annotations.json"));

        List<ReportAnnotations.TimelineMarker> markers = annotations.getTimelineMarkers();
        assertNotNull(markers);
        assertEquals(2, markers.size());

        ReportAnnotations.TimelineMarker first = markers.get(0);
        assertEquals(1700000002000L, first.getTimestamp());
        assertEquals("DB Failover", first.getLabel());
        assertEquals("warning", first.getType());
        assertEquals("Primary DB switched to replica", first.getDescription());
        assertEquals("#ff9800", first.getColor());

        ReportAnnotations.TimelineMarker second = markers.get(1);
        assertEquals(1700000004000L, second.getTimestamp());
        assertEquals("Recovery", second.getLabel());
        assertEquals("info", second.getType());
    }

    @Test
    void loadFromFile_parsesSamplerNotes() {
        ReportAnnotations annotations = loader.load(getResourceFile("full-annotations.json"));

        Map<String, String> notes = annotations.getSamplerNotes();
        assertNotNull(notes);
        assertEquals(2, notes.size());
        assertEquals("Includes 2s payment processing delay", notes.get("POST /api/checkout"));
        assertEquals("Cached response, should be fast", notes.get("GET /api/products"));
    }

    @Test
    void loadFromFile_parsesTestNotes() {
        ReportAnnotations annotations = loader.load(getResourceFile("full-annotations.json"));

        String testNotes = annotations.getTestNotes();
        assertNotNull(testNotes);
        assertTrue(testNotes.contains("Load Test Results"));
        assertTrue(testNotes.contains("checkout flow"));
    }


    @Test
    void loadFromFile_parsesComparisonThresholds() {
        ReportAnnotations annotations = loader.load(getResourceFile("full-annotations.json"));

        ReportAnnotations.ComparisonThresholdConfig ct = annotations.getComparisonThresholds();
        assertNotNull(ct, "comparisonThresholds should be parsed from full-annotations.json");
        assertEquals(15.0, ct.getP95PctChange());
        assertEquals(3.0, ct.getErrorRateChange());
        assertEquals(20.0, ct.getMeanPctChange());
        assertEquals(25.0, ct.getP99PctChange());
        assertEquals(10.0, ct.getThroughputPctChange());
    }
}
