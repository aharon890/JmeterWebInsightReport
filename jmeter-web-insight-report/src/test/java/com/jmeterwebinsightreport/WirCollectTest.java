package com.jmeterwebinsightreport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.parser.JtlParser;
import com.jmeterwebinsightreport.core.servermetrics.collect.FileSource;
import com.jmeterwebinsightreport.core.servermetrics.collect.ServerMetricsPayload;
import com.jmeterwebinsightreport.core.util.RunIdentity;
import com.jmeterwebinsightreport.report.servermetrics.ServerMetricsPayloadLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The collector end to end over the committed goldens JTL and a {@code file} source — the
 * same inputs the G-series uses, so the payload it writes is the one the report will read.
 */
class WirCollectTest {

    /** Committed and fixed; the goldens are built from it, so it is always there. */
    private static final File PLAIN_JTL = new File("../test-plans/goldens/plain.jtl");

    @TempDir
    Path dir;

    private static final class Streams {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream outStream() { return new PrintStream(out, true); }
        PrintStream errStream() { return new PrintStream(err, true); }
        String out() { return new String(out.toByteArray(), StandardCharsets.UTF_8); }
        String err() { return new String(err.toByteArray(), StandardCharsets.UTF_8); }
    }

    private static String matrix(long startSeconds, long stepSeconds, int n) {
        StringBuilder values = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) values.append(',');
            values.append('[').append(startSeconds + i * stepSeconds).append(",\"").append(100 + i).append("\"]");
        }
        return "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[{\"metric\":{\"pod\":\"a\"},\"values\":["
                + values + "]}]}}";
    }

    /** The file names what is tested; where it is fetched from is the command line's (C8). */
    private File annotations(Path recordings) throws Exception {
        String json = "{\"serverMetrics\":{\"components\":{\"bus\":{\"type\":\"custom\"}},"
                + "\"asserts\":[{\"id\":\"lag\",\"component\":\"bus\",\"query\":\"max(lag)\",\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gt\":800}},"
                + "{\"id\":\"missing\",\"component\":\"bus\",\"query\":\"unrecorded\",\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gt\":1}}]}}";
        Path p = dir.resolve("report-annotations.json");
        Files.write(p, json.getBytes(StandardCharsets.UTF_8));
        return p.toFile();
    }

    /** The collection's flags: a recording, at 1 s. */
    private static String[] collect(String jtl, String annotations, String out, Path recordings, String... more) {
        String[] base = {"--jtl", jtl, "--annotations", annotations, "--out", out,
                "--recording", recordings.toString(), "--step", "1s", "--no-wait"};
        String[] all = new String[base.length + more.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(more, 0, all, base.length, more.length);
        return all;
    }

    @Test
    void collectsTheRunsWindowFromTheJtlAndWritesAPayloadTheReportWillAccept() throws Exception {
        assertTrue(PLAIN_JTL.isFile(), "the goldens JTL must exist: " + PLAIN_JTL.getAbsolutePath());
        ReportData run = new JtlParser().parse(PLAIN_JTL, WirCollect.DEFAULT_TEST_NAME);
        long start = run.getMetadata().getStartTime().toEpochMilli();
        long end = run.getMetadata().getEndTime().toEpochMilli();

        // A recording that covers the run's window at 1 s — from one second before the window
        // to one after: the JTL's millisecond edges do not fall on whole seconds, and an edge
        // slot is covered by any point within half a step of it.
        Path recordings = dir.resolve("rec");
        Files.createDirectories(recordings);
        int points = (int) ((end - start) / 1000) + 3;
        Files.write(recordings.resolve(FileSource.filenameFor("max(lag)")),
                matrix(start / 1000 - 1, 1, points).getBytes(StandardCharsets.UTF_8));

        File out = dir.resolve("server-metrics.json").toFile();
        Streams s = new Streams();
        int exit = WirCollect.run(collect(PLAIN_JTL.getPath(), annotations(recordings).getPath(), out.getPath(), recordings),
                s.outStream(), s.errStream(), Collections.<String, String>emptyMap());

        // The `missing` KPI's query has no recording → EMPTY → not judgeable. The payload was
        // written, so the exit is 0 (D27): the status is in the file and the report judges it.
        assertEquals(0, exit, s.out() + s.err());
        assertTrue(out.isFile());
        // component/query for a custom series (SF25), and coverage says which window it is of (D28).
        assertTrue(s.out().contains("wir-collect: OK bus/max(lag) coverage=100% of the collected window\n")
                || s.out().contains("wir-collect: OK bus/max(lag) coverage=100% of the collected window\r\n"), s.out());
        assertTrue(s.out().contains("wir-collect: EMPTY bus/unrecorded"), s.out());
        assertTrue(s.out().contains("1 not judgeable (recorded in the payload; the report decides what they mean)"), s.out());

        // And the report accepts it: same runId, because the same name and the same JTL.
        ServerMetricsPayloadLoader.Result loaded = new ServerMetricsPayloadLoader().read(out, RunIdentity.of(run));
        assertNull(loaded.problem());
        ServerMetricsPayload p = loaded.payload();
        assertEquals(start, p.getWindow().getStart());
        assertEquals(end, p.getWindow().getEnd());
        assertNull(p.getWindow().getRampUpEnd());
        assertEquals(1.0, p.find("metrics", "max(lag)").getCoverage());
        assertEquals("metrics", p.getSources().get(0).getId(), "the collection's one source");
        assertEquals("file", p.getSources().get(0).getType());
        assertEquals(16, p.getCollectionId().length());

        // Pretty-printed, so a person can read why a KPI was not judged — and timestamps are
        // whole milliseconds, not a double's 1.7E12.
        JsonNode raw = new ObjectMapper().readTree(out);
        assertEquals(ServerMetricsPayload.SCHEMA, raw.get("schema").asText());
        assertTrue(Files.readAllLines(out.toPath()).size() > 5, "indented");
        String text = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);
        assertTrue(text.contains("[ " + (start / 1000 - 1) * 1000 + ", 100.0 ]"), text);
        assertFalse(text.contains("E12"), text);
        // ASCII on the console: these lines are for grep in a CI log, through whatever charset
        // the JVM writes System.out in.
        for (String line : (s.out() + s.err()).split("\n")) {
            assertTrue(line.chars().allMatch(c -> c < 128), "non-ASCII console line: " + line);
        }
    }

    @Test
    void aWrittenPayloadExitsZeroWhateverTheSeriesStatusesAndOnlyAFaultThatWritesNothingExitsOne() throws Exception {
        // D27. The collector cannot know a gate's failsBuild, so it must not decide what a dead
        // source means: it used to exit 2 for any series that was not judgeable, and a Jenkins
        // `sh` step (or any `set -e` script) then stopped before step 3, the only report that
        // gates - an observe-only gate whose source was down broke the build it promised never
        // to break. Now: written means 0, whatever is in it; not written means 1. Nothing else.
        Path recordings = dir.resolve("rec");
        Files.createDirectories(recordings);
        // An unreadable recording is FAILED (the source answered nothing usable), and the second
        // query has no recording at all, so it is EMPTY. Neither is judgeable.
        Files.write(recordings.resolve(FileSource.filenameFor("max(lag)")),
                "this is not a query_range response".getBytes(StandardCharsets.UTF_8));
        File annotations = annotations(recordings);

        File out = dir.resolve("server-metrics.json").toFile();
        Streams s = new Streams();
        int exit = WirCollect.run(collect(PLAIN_JTL.getPath(), annotations.getPath(), out.getPath(), recordings),
                s.outStream(), s.errStream(), Collections.<String, String>emptyMap());
        assertEquals(0, exit, "nothing judgeable, but the payload was written: " + s.out() + s.err());
        assertTrue(s.out().contains("wir-collect: FAILED bus/max(lag)"), s.out());
        assertTrue(s.out().contains("wir-collect: EMPTY bus/unrecorded"), s.out());
        assertTrue(s.out().contains("2 not judgeable"), s.out());
        ServerMetricsPayload written = new ServerMetricsPayloadLoader()
                .read(out, RunIdentity.of(new JtlParser().parse(PLAIN_JTL, WirCollect.DEFAULT_TEST_NAME))).payload();
        assertEquals("FAILED", written.find("metrics", "max(lag)").collectionStatus().wireName(), "the payload records it");
        assertEquals("EMPTY", written.find("metrics", "unrecorded").collectionStatus().wireName());

        // The faults that write nothing still exit 1, and leave no payload behind.
        // An --env the file does not declare (Collector.CollectionException):
        File noEnv = dir.resolve("no-env.json").toFile();
        s = new Streams();
        assertEquals(1, WirCollect.run(collect(PLAIN_JTL.getPath(), annotations.getPath(), noEnv.getPath(), recordings,
                "--env", "PROD"), s.outStream(), s.errStream(), Collections.<String, String>emptyMap()), s.err());
        assertTrue(s.err().contains("environment 'PROD' is not declared"), s.err());
        assertFalse(noEnv.exists());
        // An --out that cannot be written (its parent is a file):
        Path blocker = dir.resolve("blocker");
        Files.write(blocker, new byte[0]);
        File unwritable = blocker.resolve("server-metrics.json").toFile();
        s = new Streams();
        assertEquals(1, WirCollect.run(collect(PLAIN_JTL.getPath(), annotations.getPath(), unwritable.getPath(), recordings),
                s.outStream(), s.errStream(), Collections.<String, String>emptyMap()), s.out() + s.err());
        assertTrue(s.err().contains("the payload could not be written"), s.err());
    }

    @Test
    void helpPrintsThePlainJavaCommandForAMachineWithOnlyTheReleasedJar() {
        // M8-02: a user may have only the JAR. With neither wrapper script they need the java -cp
        // line, and it is not java -jar - Jackson and slf4j come from JMeter's lib/.
        for (String flag : new String[] {"--help", "-h"}) {
            Streams s = new Streams();
            assertEquals(0, WirCollect.run(new String[] {flag}, s.outStream(), s.errStream(),
                    Collections.<String, String>emptyMap()), s.err());
            assertEquals("", s.err(), "help is an answer, not an error");
            String help = s.out();
            assertTrue(help.startsWith(WirCollect.USAGE), help);
            // Decision 13: one line for every OS - the JAR's launcher finds JMeter's lib/ itself.
            assertTrue(help.contains("java -jar <JMETER_HOME>/lib/ext/" + WirCollect.JAR_PLACEHOLDER
                    + " --jtl results.jtl"), help);
            assertTrue(help.contains("0  the payload was written, whatever each series' status"), help);
            assertTrue(help.contains("1  nothing was written"), help);
            // The help cannot drift from the parser: every option it accepts is listed.
            for (String option : WirCollect.OPTIONS) {
                assertTrue(help.contains("  --" + option + " "), "--" + option + " is not in --help");
            }
            for (String line : help.split("\r?\n")) {
                assertTrue(line.chars().allMatch(c -> c < 128), "non-ASCII help line: " + line);
                assertFalse(line.matches("\\s*2\\s{2}.*"), "there is no exit 2 any more: " + line);
            }
        }
        // The class name the line prints is the class that runs.
        assertEquals(WirCollect.class.getName(), WirCollect.MAIN_CLASS);
        // Loaded from target/classes here, so the placeholder; from lib/ext, the JAR's own name.
        assertEquals(WirCollect.JAR_PLACEHOLDER, WirCollect.pluginJarName());
        assertTrue(WirCollect.help("jmeter-web-insight-report-3.1.17.jar")
                .contains("java -jar <JMETER_HOME>/lib/ext/jmeter-web-insight-report-3.1.17.jar --jtl"));

        // A usage error points at it.
        Streams s = new Streams();
        assertEquals(1, WirCollect.run(new String[] {"--jtl"}, s.outStream(), s.errStream(),
                Collections.<String, String>emptyMap()));
        assertTrue(s.err().contains(WirCollect.MORE), s.err());
    }

    @Test
    void helpIsAnOptionNeverAnOptionsValueSoATestNameThatReadsLikeHelpStillCollects() throws Exception {
        // review:W. --help and -h were matched anywhere in argv, so `--test-name -h` printed the
        // help and exited 0 with no payload written. 0 means "the payload was written" (D27), so
        // under set -e step 3 then ran without one.
        ReportData run = new JtlParser().parse(PLAIN_JTL, "-h");
        long start = run.getMetadata().getStartTime().toEpochMilli();
        long end = run.getMetadata().getEndTime().toEpochMilli();
        Path recordings = dir.resolve("rec");
        Files.createDirectories(recordings);
        Files.write(recordings.resolve(FileSource.filenameFor("max(lag)")),
                matrix(start / 1000 - 1, 1, (int) ((end - start) / 1000) + 3).getBytes(StandardCharsets.UTF_8));
        File annotations = annotations(recordings);

        for (String help : new String[] {"-h", "--help"}) {
            File out = dir.resolve("sm" + help + ".json").toFile();
            Streams s = new Streams();
            int exit = WirCollect.run(collect(PLAIN_JTL.getPath(), annotations.getPath(), out.getPath(), recordings,
                    "--test-name", help), s.outStream(), s.errStream(), Collections.<String, String>emptyMap());
            assertEquals(0, exit, s.out() + s.err());
            assertFalse(s.out().startsWith(WirCollect.USAGE), "--test-name " + help + " printed the help: " + s.out());
            assertTrue(out.isFile(), "--test-name " + help + " is a test name, and the payload is written");
            ServerMetricsPayloadLoader.Result loaded = new ServerMetricsPayloadLoader().read(out,
                    RunIdentity.of(new JtlParser().parse(PLAIN_JTL, help)));
            assertNull(loaded.problem(), "the run is named " + help);
        }

        // Wherever an option can stand it is still the help: first, last, after a value, after
        // --no-wait (which takes none), after an inline value, and after an unknown option.
        String[][] asks = {
            {"--help"}, {"-h"}, {"--jtl", "a", "-h"}, {"--test-name", "x", "--help"}, {"--no-wait", "-h"},
            {"--test-name=-h", "--help"}, {"--bogus", "--help"}, {"--test-name", "-h", "--help"}};
        for (String[] args : asks) {
            assertTrue(WirCollect.asksForHelp(args), String.join(" ", args));
        }
        // ...and never as a value, inline or not.
        String[][] values = {
            {"--test-name", "-h"}, {"--out", "--help"}, {"--test-name=--help"},
            {"--jtl", "a", "--annotations", "-h", "--out", "--help"}};
        for (String[] args : values) {
            assertFalse(WirCollect.asksForHelp(args), String.join(" ", args));
        }
        // The parser reads the same position as a value - one rule for both.
        assertEquals("--help", WirCollect.parseArgs(new String[] {"--jtl", "a", "--annotations", "-h",
                "--out", "--help", "--recording", "r"}).get("out"));
    }

    @Test
    void findingsAndErrorsReachTheConsoleAsAsciiNotOnlyTheHelp() throws Exception {
        // review:W. The class says console lines are ASCII, and only --help was: a finding is the
        // mapper's sentence, written for the UTF-8 page, and its em dash reached a Git Bash log as
        // U+FFFD ("reduce.kind 'maxx' is not a reducer ? valid here").
        Path recordings = dir.resolve("rec");
        Files.createDirectories(recordings);
        String json = "{\"serverMetrics\":{\"components\":{\"bus\":{\"type\":\"custom\"}},"
                + "\"asserts\":[{\"id\":\"typo\",\"component\":\"bus\",\"query\":\"max(lag)\",\"reduce\":{\"kind\":\"maxx\"},\"fail\":{\"gt\":1}},"
                + "{\"id\":\"lag\",\"component\":\"bus\",\"query\":\"max(lag)\",\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gt\":1}}]}}";
        Path annotations = dir.resolve("typo.json");
        Files.write(annotations, json.getBytes(StandardCharsets.UTF_8));

        File out = dir.resolve("sm.json").toFile();
        Streams s = new Streams();
        WirCollect.run(collect(PLAIN_JTL.getPath(), annotations.toString(), out.getPath(), recordings),
                s.outStream(), s.errStream(), Collections.<String, String>emptyMap());
        assertTrue(s.err().contains("wir-collect: assertion 'typo': reduce.kind 'maxx' is not a reducer - "), s.err());
        for (String line : (s.out() + s.err()).split("\r?\n")) {
            assertTrue(line.chars().allMatch(c -> c < 128), "non-ASCII console line: " + line);
        }

        // The typography has an ASCII spelling; anything else is its Java escape, which says exactly
        // what was there - a label value in a query, a path - and still greps.
        assertEquals("a - b - c -> d => e >= f <= g ... 'h' \"i\" j | k",
                WirCollect.ascii("a — b – c → d ⇒ e ≥ f ≤ g … ‘h’ “i” j · k"));
        assertEquals("pod=\"caf\\u00e9\" \\ud83d\\ude00", WirCollect.ascii("pod=\"café\" 😀"));
        String plain = "wir-collect: OK rec up coverage=100%";
        assertTrue(plain == WirCollect.ascii(plain), "ASCII text is returned as it is");
    }

    @Test
    void aControlCharacterIsEscapedSoOneOutcomeStaysOneLine() {
        // SF25. ascii() passed everything below 128 through, so a multi-line PromQL query - the U7
        // trim leaves its inside alone - printed a raw line break and split one outcome across two
        // lines of a console that promises one line per outcome. Spelled by code point: a literal
        // here would be the very character under test.
        char tab = (char) 9, lf = (char) 10, cr = (char) 13, bell = (char) 7, del = (char) 127;
        String backslash = String.valueOf((char) 92);
        assertEquals("sum by (pod)" + backslash + "n  (rate(x[1m]))" + backslash + "t" + backslash + "r",
                WirCollect.ascii("sum by (pod)" + lf + "  (rate(x[1m]))" + tab + cr));
        assertEquals("a" + backslash + "u0007b" + backslash + "u007f", WirCollect.ascii("a" + bell + "b" + del));
        for (int c = 0; c < 0x20; c++) {
            String once = WirCollect.ascii(String.valueOf((char) c));
            assertTrue(once.chars().allMatch(ch -> ch >= 0x20 && ch < 0x7f), "U+" + Integer.toHexString(c) + " -> " + once);
        }
    }

    @Test
    void twoComponentsAskingOneQueryPrintTwoLinesACiLogCanTellApart() throws Exception {
        // SF25. A custom series was named by its bare query, so E26's kafka and db - which share
        // "lag" and "cpu" - printed "OK lag" twice; catalogue lines already said component/metric.
        // A series is a component's and a query's (the payload keys it so), and the line now says both.
        Path recordings = dir.resolve("rec");
        Files.createDirectories(recordings);
        Path two = dir.resolve("two.json");
        Files.write(two, ("{\"serverMetrics\":{\"components\":{\"kafka\":{\"type\":\"custom\"},\"db\":{\"type\":\"custom\"}},"
                + "\"asserts\":[{\"id\":\"k\",\"component\":\"kafka\",\"query\":\"lag\",\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gt\":1}},"
                + "{\"id\":\"d\",\"component\":\"db\",\"query\":\"lag\",\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gt\":1}}]}}")
                .getBytes(StandardCharsets.UTF_8));
        Streams s = new Streams();
        assertEquals(0, WirCollect.run(collect(PLAIN_JTL.getPath(), two.toString(), dir.resolve("two-out.json").toString(),
                recordings), s.outStream(), s.errStream(), Collections.<String, String>emptyMap()), s.out() + s.err());
        assertTrue(s.out().contains("wir-collect: EMPTY kafka/lag"), s.out());
        assertTrue(s.out().contains("wir-collect: EMPTY db/lag"), s.out());
        assertFalse(s.out().contains("wir-collect: EMPTY lag"), s.out());
    }

    /** A no-break space, by code point: a literal one would be invisible in this file too. */
    private static final String NBSP = String.valueOf((char) 0x00a0);

    /** What {@link WirCollect#ascii} prints for it: a backslash, then u00a0. */
    private static final String NBSP_ESCAPED = (char) 92 + "u00a0";

    private static final String PADDING_WARNING = "the query has an invisible character at its start (U+00A0)"
            + " that is not trimmed like a plain space, so no series will match it - delete it";

    @Test
    void aQueryPaddedWithAnInvisibleCharacterIsVisibleOnTheConsoleAndSaysWhyItMatchedNothing() throws Exception {
        // SF1 review. The no-break space was printed as a plain space, so the EMPTY line read
        // exactly like the padding a query is now trimmed of, and the console - the one read right
        // after the query is written - did not say why: the warning reached only step 3.
        Path recordings = dir.resolve("rec");
        Files.createDirectories(recordings);
        Path written = dir.resolve("nbsp.json");
        Files.write(written, ("{\"serverMetrics\":{\"components\":{\"bus\":{\"type\":\"custom\"}},"
                + "\"asserts\":[{\"id\":\"lag\",\"component\":\"bus\",\"query\":\"" + NBSP + "lag\","
                + "\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gt\":100}}]}}").getBytes(StandardCharsets.UTF_8));
        Streams s = new Streams();
        assertEquals(0, WirCollect.run(collect(PLAIN_JTL.getPath(), written.toString(),
                dir.resolve("nbsp-out.json").toString(), recordings), s.outStream(), s.errStream(),
                Collections.<String, String>emptyMap()), s.out() + s.err());
        assertTrue(s.out().contains("wir-collect: EMPTY bus/" + NBSP_ESCAPED + "lag"), s.out());
        assertTrue(s.out().contains("wir-collect: WARNING bus/" + NBSP_ESCAPED + "lag: " + PADDING_WARNING), s.out());
        assertFalse(s.out().contains("interpolates"), "the file wrote it, so no value is blamed: " + s.out());

        // The same character arriving through ${env.*}: every check on the file passes (it wrote
        // "${env.q}"), so the collector is the one place that can say it - and says where it is.
        Path viaEnv = dir.resolve("nbsp-env.json");
        Files.write(viaEnv, ("{\"serverMetrics\":{\"environments\":{\"dev\":{\"vars\":{\"q\":\"" + NBSP + "lag\"}}},"
                + "\"defaultEnv\":\"dev\",\"components\":{\"bus\":{\"type\":\"custom\"}},"
                + "\"asserts\":[{\"id\":\"lag\",\"component\":\"bus\",\"query\":\"${env.q}\","
                + "\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gt\":100}}]}}").getBytes(StandardCharsets.UTF_8));
        s = new Streams();
        assertEquals(0, WirCollect.run(collect(PLAIN_JTL.getPath(), viaEnv.toString(),
                dir.resolve("nbsp-env-out.json").toString(), recordings), s.outStream(), s.errStream(),
                Collections.<String, String>emptyMap()), s.out() + s.err());
        assertTrue(s.out().contains("wir-collect: WARNING bus/" + NBSP_ESCAPED + "lag: " + PADDING_WARNING
                + " (it comes from a value ${env.q} interpolates)"), s.out());

        // A plain query says nothing of the kind.
        s = new Streams();
        WirCollect.run(collect(PLAIN_JTL.getPath(), annotations(recordings).getPath(),
                dir.resolve("plain-out.json").toString(), recordings), s.outStream(), s.errStream(),
                Collections.<String, String>emptyMap());
        assertFalse(s.out().contains("WARNING"), s.out());
        assertEquals(NBSP_ESCAPED + "lag", WirCollect.ascii(NBSP + "lag"), "a no-break space is shown, not spelt as a space");
    }

    @Test
    void aPaddedQueryWhoseSeriesCameBackGetsNoPaddingWarning() throws Exception {
        // F8 review. A source whose name carries the same character returns the series for the
        // text as written - a CSV cell padded the same way, a recording keyed by it, or a visible
        // character that ends in a variation selector, as an emoji does - and the line above it
        // said OK. "No series will match it - delete it" beside that was false, and following it
        // would have broken the match. The warning explains a series that returned no points.
        ReportData run = new JtlParser().parse(PLAIN_JTL, WirCollect.DEFAULT_TEST_NAME);
        long start = run.getMetadata().getStartTime().toEpochMilli();
        long end = run.getMetadata().getEndTime().toEpochMilli();
        Path recordings = dir.resolve("rec-padded");
        Files.createDirectories(recordings);
        Files.write(recordings.resolve(FileSource.filenameFor(NBSP + "lag")),
                matrix(start / 1000 - 1, 1, (int) ((end - start) / 1000) + 3).getBytes(StandardCharsets.UTF_8));
        Path written = dir.resolve("nbsp-matched.json");
        Files.write(written, ("{\"serverMetrics\":{\"components\":{\"bus\":{\"type\":\"custom\"}},"
                + "\"asserts\":[{\"id\":\"lag\",\"component\":\"bus\",\"query\":\"" + NBSP + "lag\","
                + "\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gt\":100}}]}}").getBytes(StandardCharsets.UTF_8));
        Streams s = new Streams();
        assertEquals(0, WirCollect.run(collect(PLAIN_JTL.getPath(), written.toString(),
                dir.resolve("nbsp-matched-out.json").toString(), recordings), s.outStream(), s.errStream(),
                Collections.<String, String>emptyMap()), s.out() + s.err());
        assertTrue(s.out().contains("wir-collect: OK bus/" + NBSP_ESCAPED + "lag coverage=100%"), s.out());
        assertFalse(s.out().contains("WARNING"), s.out());
    }

    @Test
    void aPayloadWithNoSeriesBecauseEveryAssertIsDisabledDoesNotBlameTheCatalogue() throws Exception {
        // SF6 review. The empty-payload clause blamed "the declared components' catalogue metrics"
        // on a file with one custom component and its only assert disabled: no catalogue component
        // existed, and nothing was asked for.
        Path recordings = dir.resolve("rec");
        Files.createDirectories(recordings);
        Path disabled = dir.resolve("disabled.json");
        Files.write(disabled, ("{\"serverMetrics\":{\"components\":{\"db\":{\"type\":\"custom\"}},"
                + "\"asserts\":[{\"id\":\"lag\",\"component\":\"db\",\"query\":\"lag\",\"enabled\":false,"
                + "\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gt\":100}}]}}").getBytes(StandardCharsets.UTF_8));
        Streams s = new Streams();
        assertEquals(0, WirCollect.run(collect(PLAIN_JTL.getPath(), disabled.toString(),
                dir.resolve("disabled-out.json").toString(), recordings), s.outStream(), s.errStream(),
                Collections.<String, String>emptyMap()), s.out() + s.err());
        assertTrue(s.out().contains("0 series, 0 not judgeable - no enabled server assert has a query to collect,"
                + " so nothing was asked for"), s.out());
        assertFalse(s.out().contains("catalogue metrics"), s.out());
    }

    @Test
    void aDifferentTestNameIsADifferentRunAndTheReportRefusesThePayload() throws Exception {
        Path recordings = dir.resolve("rec");
        Files.createDirectories(recordings);
        File out = dir.resolve("sm.json").toFile();
        Streams s = new Streams();
        WirCollect.run(collect(PLAIN_JTL.getPath(), annotations(recordings).getPath(), out.getPath(), recordings,
                "--test-name", "Checkout", "--rampup", "5000"),
                s.outStream(), s.errStream(), Collections.<String, String>emptyMap());

        ReportData underDefaultName = new JtlParser().parse(PLAIN_JTL, WirCollect.DEFAULT_TEST_NAME);
        ServerMetricsPayloadLoader.Result loaded = new ServerMetricsPayloadLoader().read(out, RunIdentity.of(underDefaultName));
        assertNull(loaded.payload(), "RunIdentity hashes the test name; --test-name must match the report title");
        assertTrue(loaded.problem().contains("belongs to run"), loaded.problem());

        ReportData underCheckout = new JtlParser().parse(PLAIN_JTL, "Checkout");
        ServerMetricsPayloadLoader.Result matched = new ServerMetricsPayloadLoader().read(out, RunIdentity.of(underCheckout));
        assertNull(matched.problem());
        assertEquals(underCheckout.getMetadata().getStartTime().toEpochMilli() + 5000,
                (long) matched.payload().getWindow().getRampUpEnd());
    }

    @Test
    void aRampUpThatSwallowsTheWholeRunIsAUsageErrorNotAPayloadThatJudgesNothing() throws Exception {
        // L9-5. --rampup at or beyond the run's length left an empty judged window: every KPI
        // NO_VALUE, not a config error, so the report exited 0 having judged nothing. The usual
        // cause is a unit mix-up, and the message has to say which unit is meant.
        ReportData run = new JtlParser().parse(PLAIN_JTL, WirCollect.DEFAULT_TEST_NAME);
        long runMillis = run.getMetadata().getEndTime().toEpochMilli() - run.getMetadata().getStartTime().toEpochMilli();
        Path recordings = dir.resolve("rec");
        Files.createDirectories(recordings);
        File annotations = annotations(recordings);

        for (long rampUp : new long[] {runMillis, runMillis + 1, 600_000}) {
            File out = dir.resolve("sm-" + rampUp + ".json").toFile();
            Streams s = new Streams();
            int exit = WirCollect.run(collect(PLAIN_JTL.getPath(), annotations.getPath(), out.getPath(), recordings,
                    "--rampup", String.valueOf(rampUp)), s.outStream(), s.errStream(), Collections.<String, String>emptyMap());
            assertEquals(1, exit, "--rampup " + rampUp + " of a " + runMillis + " ms run: " + s.out() + s.err());
            assertTrue(s.err().contains("--rampup " + rampUp + " ms is at or beyond the end of the run"), s.err());
            assertTrue(s.err().contains("MILLISECONDS"), "the unit is the usual mistake: " + s.err());
            assertTrue(s.err().contains("usage:"), s.err());
            assertFalse(out.exists(), "a usage error writes no payload");
        }

        // One millisecond short of the run is still a window - a single instant - and is collected.
        File out = dir.resolve("sm-ok.json").toFile();
        Streams s = new Streams();
        WirCollect.run(collect(PLAIN_JTL.getPath(), annotations.getPath(), out.getPath(), recordings,
                "--rampup", String.valueOf(runMillis - 1)), s.outStream(), s.errStream(), Collections.<String, String>emptyMap());
        assertTrue(out.isFile(), s.out() + s.err());

        // A negative ramp-up used to be read as none, which judged the ramp-up the author asked
        // to exclude.
        IllegalArgumentException negative = assertThrows(IllegalArgumentException.class, () -> WirCollect.parseArgs(
                new String[] {"--jtl", "a", "--annotations", "b", "--out", "c", "--recording", "r", "--rampup", "-5000"}));
        assertTrue(negative.getMessage().contains("0 or more"), negative.getMessage());
    }

    @Test
    void aFileWithCatalogueComponentsAndNoAssertYetIsCollectedSoThePickerHasThisRunsValues() throws Exception {
        // SF6 / D1. "+ Add your first component", save, collect, then pick metrics with real values:
        // the collect step exited 1 on a file with components and no asserts ("declares no
        // serverMetrics.asserts"), so the loop stopped at step 2 and the picker said "no data" for
        // everything until an assert was written blind. The one input whose exit code moved (D27:
        // 0 whenever the payload was written). E27's committed CSV over the goldens JTL, as E27 is
        // collected (test-plans/server-catalogue/README.md).
        File csv = new File("../test-plans/server-catalogue/e27-metrics.csv");
        assertTrue(csv.isFile(), csv.getAbsolutePath());
        Path componentsOnly = dir.resolve("components-only.json");
        Files.write(componentsOnly, ("{\"serverMetrics\":{\"components\":{"
                + "\"orders-db\":{\"type\":\"postgres\",\"description\":\"Orders DB\"},"
                + "\"events\":{\"type\":\"kafka\",\"description\":\"Event bus\"}},\"asserts\":[]}}")
                .getBytes(StandardCharsets.UTF_8));
        File out = dir.resolve("server-metrics.json").toFile();
        Streams s = new Streams();
        int exit = WirCollect.run(new String[] {"--jtl", PLAIN_JTL.getPath(), "--annotations", componentsOnly.toString(),
                "--out", out.getPath(), "--csv", csv.getPath(), "--step", "1s", "--no-wait"},
                s.outStream(), s.errStream(), Collections.<String, String>emptyMap());
        assertEquals(0, exit, s.out() + s.err());

        ServerMetricsPayload p = new ServerMetricsPayloadLoader()
                .read(out, RunIdentity.of(new JtlParser().parse(PLAIN_JTL, WirCollect.DEFAULT_TEST_NAME))).payload();
        assertNotNull(p, "the report accepts it as this run's");
        java.util.Set<String> collected = new java.util.TreeSet<>();
        for (ServerMetricsPayload.SeriesRecord series : p.getSeries()) {
            assertNotNull(series.getMetric(), "every series is a catalogue metric: " + series.getQuery());
            assertEquals("OK", series.getStatus(), series.getComponent() + "/" + series.getMetric());
            collected.add(series.getComponent() + "/" + series.getMetric());
        }
        // Every catalogue metric the CSV carries, of both components - nothing else is asked for.
        assertEquals(new java.util.TreeSet<>(java.util.Arrays.asList(
                "events/brokers", "events/consumer.lag.max", "events/messages.in.rate",
                "orders-db/cache.hit.pct", "orders-db/connections.used.pct", "orders-db/database.size.growth",
                "orders-db/deadlocks", "orders-db/transaction.longest.seconds", "orders-db/up")), collected);
        assertTrue(s.out().contains("wir-collect: OK orders-db/up"), s.out());
        assertTrue(s.out().contains(p.getSeries().size() + " series, 0 not judgeable"), s.out());

        // A source holding none of them still writes the payload - and says so.
        Path otherCsv = dir.resolve("unrelated.csv");
        Files.write(otherCsv, "time,component,metric,instance,value\n1700000000000,billing,x,,1\n"
                .getBytes(StandardCharsets.UTF_8));
        s = new Streams();
        assertEquals(0, WirCollect.run(new String[] {"--jtl", PLAIN_JTL.getPath(), "--annotations", componentsOnly.toString(),
                "--out", dir.resolve("empty.json").toString(), "--csv", otherCsv.toString(), "--step", "1s", "--no-wait"},
                s.outStream(), s.errStream(), Collections.<String, String>emptyMap()), s.out() + s.err());
        assertTrue(s.out().contains("0 series, 0 not judgeable - the source returned none of the declared"
                + " components' catalogue metrics"), s.out());

        // A custom component alone has no catalogue, so nothing to ask for: still exit 1.
        Path customOnly = dir.resolve("custom-only.json");
        Files.write(customOnly, "{\"serverMetrics\":{\"components\":{\"bus\":{\"type\":\"custom\"}},\"asserts\":[]}}"
                .getBytes(StandardCharsets.UTF_8));
        s = new Streams();
        File none = dir.resolve("none.json").toFile();
        assertEquals(1, WirCollect.run(new String[] {"--jtl", PLAIN_JTL.getPath(), "--annotations", customOnly.toString(),
                "--out", none.getPath(), "--csv", csv.getPath(), "--step", "1s", "--no-wait"},
                s.outStream(), s.errStream(), Collections.<String, String>emptyMap()), s.out());
        assertTrue(s.err().contains("declares no serverMetrics.asserts and no component of a catalogue type"), s.err());
        assertFalse(none.exists(), "nothing written");
    }

    @Test
    void fromNamesTheToolACsvWasExportedFromAndGoesWithCsvOnly() throws Exception {
        String[] base = {"--jtl", "a", "--annotations", "b", "--out", "c"};
        // Only a CSV has a metric column to read by another tool's names.
        IllegalArgumentException withoutCsv = assertThrows(IllegalArgumentException.class, () -> WirCollect.parseArgs(
                concat(base, new String[] {"--recording", "r", "--from", "datadog"})));
        assertEquals("--from goes with --csv", withoutCsv.getMessage());
        assertEquals("--from goes with --csv", assertThrows(IllegalArgumentException.class, () -> WirCollect.parseArgs(
                concat(base, new String[] {"--prometheus-url", "https://p/api/v1", "--from", "otel"}))).getMessage());
        // A tool the catalogue does not name is refused with the four it does.
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class, () -> WirCollect.parseArgs(
                concat(base, new String[] {"--csv", "m.csv", "--from", "grafana"})));
        assertEquals("--from 'grafana' is not a tool the catalogue names - one of telegraf, datadog, otel, cloudwatch",
                unknown.getMessage());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> WirCollect.parseArgs(
                concat(base, new String[] {"--csv", "m.csv", "--from="}))).getMessage()
                .startsWith("--from needs a tool - one of telegraf, datadog, otel, cloudwatch"));
        // A usage error, exit 1, through run() as well.
        Streams s = new Streams();
        assertEquals(1, WirCollect.run(concat(base, new String[] {"--recording", "r", "--from", "datadog"}),
                s.outStream(), s.errStream(), Collections.<String, String>emptyMap()));
        assertTrue(s.err().contains("wir-collect: --from goes with --csv"), s.err());
        assertTrue(s.err().contains("usage:"), s.err());

        // ...and a good one reaches the source, case aside: the tool is written as the catalogue names it.
        for (String tool : new String[] {"datadog", "Datadog", " datadog "}) {
            assertEquals("datadog", WirCollect.collectionSource(WirCollect.parseArgs(
                    concat(base, new String[] {"--csv", "m.csv", "--from", tool}))).fromTool(), tool);
        }
        assertNull(WirCollect.collectionSource(WirCollect.parseArgs(concat(base, new String[] {"--csv", "m.csv"})))
                .fromTool(), "no --from: the file holds the catalogue's own names");

        // --help says what it does.
        s = new Streams();
        WirCollect.run(new String[] {"--help"}, s.outStream(), s.errStream(), Collections.<String, String>emptyMap());
        assertTrue(s.out().contains("  --from TOOL "), s.out());
        assertTrue(s.out().contains("converted to the catalogue's unit"), s.out());
        assertTrue(WirCollect.USAGE.contains("--csv FILE [--from TOOL]"), WirCollect.USAGE);
    }

    @Test
    void aCsvCollectedFromAToolsExportSaysSoInThePayloadAndTheReportReadsIt() throws Exception {
        // E27's CSV holds the catalogue's own names, so under --from datadog every series is read
        // under our name (the escape hatch) and none is read as Datadog's: the payload records the
        // tool on the source, and no series carries a readAs.
        File csv = new File("../test-plans/server-catalogue/e27-metrics.csv");
        Path componentsOnly = dir.resolve("components-only.json");
        Files.write(componentsOnly, ("{\"serverMetrics\":{\"components\":{"
                + "\"orders-db\":{\"type\":\"postgres\"}},\"asserts\":[]}}").getBytes(StandardCharsets.UTF_8));
        File out = dir.resolve("from.json").toFile();
        Streams s = new Streams();
        assertEquals(0, WirCollect.run(new String[] {"--jtl", PLAIN_JTL.getPath(), "--annotations", componentsOnly.toString(),
                "--out", out.getPath(), "--csv", csv.getPath(), "--from", "datadog", "--step", "1s", "--no-wait"},
                s.outStream(), s.errStream(), Collections.<String, String>emptyMap()), s.out() + s.err());

        ServerMetricsPayload p = new ServerMetricsPayloadLoader()
                .read(out, RunIdentity.of(new JtlParser().parse(PLAIN_JTL, WirCollect.DEFAULT_TEST_NAME))).payload();
        assertNotNull(p);
        assertEquals("datadog", p.getSources().get(0).getFrom());
        assertFalse(p.getSeries().isEmpty());
        for (ServerMetricsPayload.SeriesRecord series : p.getSeries()) {
            assertNull(series.getReadAs(), series.getMetric());
        }
        String text = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);
        assertTrue(text.contains("\"from\" : \"datadog\""), text);

        // Without it, the payload has no such key at all.
        File plain = dir.resolve("plain.json").toFile();
        assertEquals(0, WirCollect.run(new String[] {"--jtl", PLAIN_JTL.getPath(), "--annotations", componentsOnly.toString(),
                "--out", plain.getPath(), "--csv", csv.getPath(), "--step", "1s", "--no-wait"},
                new Streams().outStream(), new Streams().errStream(), Collections.<String, String>emptyMap()));
        assertFalse(new String(Files.readAllBytes(plain.toPath()), StandardCharsets.UTF_8).contains("\"from\""));
    }

    // ---- --layout wide, --columns, --time-zone: Grafana's export --------------------------------

    @Test
    void layoutColumnsAndTimeZoneAreCsvFlagsAndAreRefusedWhenTheyCannotBeRead() throws Exception {
        String[] base = {"--jtl", "a", "--annotations", "b", "--out", "c"};
        java.util.function.Function<String[], String> refusal = more -> assertThrows(IllegalArgumentException.class,
                () -> WirCollect.parseArgs(concat(base, more))).getMessage();
        // Each is about how a CSV is read, so each goes with --csv.
        assertEquals("--layout goes with --csv", refusal.apply(new String[] {"--recording", "r", "--layout", "wide"}));
        assertEquals("--time-zone goes with --csv",
                refusal.apply(new String[] {"--prometheus-url", "https://p/api/v1", "--time-zone", "UTC"}));
        // Two layouts, named when another is given.
        assertEquals("--layout 'diagonal' is not a layout - one of long, wide",
                refusal.apply(new String[] {"--csv", "m.csv", "--layout", "diagonal"}));
        assertEquals("--layout needs a layout - one of long, wide", refusal.apply(new String[] {"--csv", "m.csv", "--layout="}));
        // A map of column headers exists only for a file whose columns are series.
        // On a long file, the slip is the long reader's own flag: the sentence names it.
        assertEquals("--columns goes with --layout wide - a long file's column names are --csv-columns",
                refusal.apply(new String[] {"--csv", "m.csv", "--columns", "map.csv"}));
        assertEquals("--columns goes with --layout wide - a long file's column names are --csv-columns",
                refusal.apply(new String[] {"--csv", "m.csv", "--layout", "long", "--columns", "map.csv"}));
        assertEquals("--columns goes with --layout wide", refusal.apply(new String[] {"--recording", "r", "--columns", "map.csv"}));
        assertEquals("--columns needs a file, e.g. columns.csv",
                refusal.apply(new String[] {"--csv", "m.csv", "--layout", "wide", "--columns="}));
        // A zone java.time cannot read is refused, never guessed at.
        assertEquals("--time-zone 'Mars/Olympus' is not a zone java.time can read - an IANA id such as Europe/Berlin,"
                + " UTC, or an offset such as +03:00", refusal.apply(new String[] {"--csv", "m.csv", "--time-zone", "Mars/Olympus"}));
        assertEquals("--time-zone needs a zone - an IANA id such as Europe/Berlin, UTC, or an offset such as +03:00",
                refusal.apply(new String[] {"--csv", "m.csv", "--time-zone="}));
        // A usage error, exit 1, through run() as well.
        Streams s = new Streams();
        assertEquals(1, WirCollect.run(concat(base, new String[] {"--csv", "m.csv", "--layout", "diagonal"}),
                s.outStream(), s.errStream(), Collections.<String, String>emptyMap()));
        assertTrue(s.err().contains("wir-collect: --layout 'diagonal' is not a layout"), s.err());

        // Good ones reach the source.
        com.jmeterwebinsightreport.core.servermetrics.ServerSource wide = WirCollect.collectionSource(WirCollect.parseArgs(
                concat(base, new String[] {"--csv", "m.csv", "--layout", " Wide ", "--columns", "map.csv",
                        "--time-zone", "Europe/Berlin"})));
        assertEquals(com.jmeterwebinsightreport.core.servermetrics.ServerSource.CsvLayout.WIDE, wide.csvLayout());
        assertEquals("map.csv", wide.csvColumns());
        assertEquals(java.time.ZoneId.of("Europe/Berlin"), wide.timeZone());
        for (String zone : new String[] {"UTC", "+03:00", "Z"}) {
            assertEquals(java.time.ZoneId.of(zone), WirCollect.collectionSource(WirCollect.parseArgs(
                    concat(base, new String[] {"--csv", "m.csv", "--time-zone", zone}))).timeZone(), zone);
        }
        com.jmeterwebinsightreport.core.servermetrics.ServerSource plain = WirCollect.collectionSource(
                WirCollect.parseArgs(concat(base, new String[] {"--csv", "m.csv"})));
        assertEquals(com.jmeterwebinsightreport.core.servermetrics.ServerSource.CsvLayout.LONG, plain.csvLayout());
        assertNull(plain.csvColumns());
        assertNull(plain.timeZone());

        // --help says what each does.
        s = new Streams();
        WirCollect.run(new String[] {"--help"}, s.outStream(), s.errStream(), Collections.<String, String>emptyMap());
        assertTrue(s.out().contains("  --layout long|wide "), s.out());
        assertTrue(s.out().contains("Series joined by time"), s.out());
        assertTrue(s.out().contains("  --columns MAP "), s.out());
        assertTrue(s.out().contains("  --time-zone ZONE "), s.out());
        // (--csv-columns and --component stand between the two since batch 4b; their own test pins the whole.)
        assertTrue(WirCollect.USAGE.contains("--csv FILE [--from TOOL] [--layout wide [--columns MAP]] "), WirCollect.USAGE);
        assertTrue(WirCollect.USAGE.contains(" [--time-zone ZONE])"), WirCollect.USAGE);
    }

    /** A wide export over plain.jtl's window (22:13:20 to 22:14:08 UTC), a row every {@code everySeconds}. */
    private Path grafanaExport(String name, int everySeconds) throws Exception {
        StringBuilder sb = new StringBuilder("\"Time\",\"orders-db/connections.used.pct/pg-1\","
                + "\"orders-db:5432 pg_stat_activity_count\",\"Free text\"\r\n");
        for (int t = 0; t <= 48; t += everySeconds) {
            sb.append(1_700_000_000_000L + t * 1000L).append(',').append(40 + t % 7).append(",41,9\r\n");
        }
        Path p = dir.resolve(name);
        Files.write(p, sb.toString().getBytes(StandardCharsets.UTF_8));
        return p;
    }

    @Test
    void aWideExportIsCollectedAndItsUnmappedColumnsAndASpacingCoarserThanTheStepAreSaid() throws Exception {
        Path componentsOnly = dir.resolve("components-only.json");
        Files.write(componentsOnly, ("{\"serverMetrics\":{\"components\":{"
                + "\"orders-db\":{\"type\":\"postgres\"}},\"asserts\":[]}}").getBytes(StandardCharsets.UTF_8));
        Path csv = grafanaExport("grafana.csv", 12);
        File out = dir.resolve("wide.json").toFile();
        Streams s = new Streams();
        assertEquals(0, WirCollect.run(new String[] {"--jtl", PLAIN_JTL.getPath(), "--annotations", componentsOnly.toString(),
                "--out", out.getPath(), "--csv", csv.toString(), "--layout", "wide", "--time-zone", "UTC",
                "--step", "5s", "--no-wait"}, s.outStream(), s.errStream(), Collections.<String, String>emptyMap()),
                s.out() + s.err());
        // A column neither the convention nor a map names is ignored, and said - never guessed at.
        assertTrue(s.out().contains("wir-collect: 2 columns not mapped to a component and metric:"
                + " \"orders-db:5432 pg_stat_activity_count\", \"Free text\""), s.out());
        // The export's interval against the coverage grid: 12 s rows counted in 5 s slots.
        assertTrue(s.out().contains("wir-collect: orders-db/connections.used.pct points are 12s apart but --step is 5s"
                + " - coverage counts 5s slots; pass --step 12s"), s.out());

        ServerMetricsPayload p = new ServerMetricsPayloadLoader()
                .read(out, RunIdentity.of(new JtlParser().parse(PLAIN_JTL, WirCollect.DEFAULT_TEST_NAME))).payload();
        assertNotNull(p);
        assertEquals("wide", p.getSources().get(0).getLayout());
        assertEquals("UTC", p.getSources().get(0).getTimeZone());
        ServerMetricsPayload.SeriesRecord conns = p.getSeries().get(0);
        assertEquals("connections.used.pct", conns.getMetric());
        assertEquals(5, conns.getInstances().get(0).getPoints().size(), conns.getInstances().toString());
        assertEquals(40.0, conns.getInstances().get(0).getPoints().get(0).getValue());

        // At the export's own spacing there is nothing to say. A --columns map names "Free text";
        // its entry for a column the export does not have maps nothing, and is said.
        Path map = dir.resolve("columns.csv");
        Files.write(map, ("column,component,metric,instance\nFree text,orders-db,deadlocks,pg-1\n"
                + "Replication lag,orders-db,replication.lag.seconds,pg-1\n").getBytes(StandardCharsets.UTF_8));
        Streams matched = new Streams();
        File mapped = dir.resolve("matched.json").toFile();
        assertEquals(0, WirCollect.run(new String[] {"--jtl", PLAIN_JTL.getPath(), "--annotations", componentsOnly.toString(),
                "--out", mapped.getPath(), "--csv", csv.toString(), "--layout", "wide", "--columns", map.toString(),
                "--step", "12s", "--no-wait"}, matched.outStream(), matched.errStream(), Collections.<String, String>emptyMap()),
                matched.out() + matched.err());
        assertFalse(matched.out().contains("points are"), matched.out());
        assertTrue(matched.out().contains("wir-collect: 1 column not mapped to a component and metric:"
                + " \"orders-db:5432 pg_stat_activity_count\""), matched.out());
        assertTrue(matched.out().contains("wir-collect: columns.csv names 1 column that grafana.csv does not have,"
                + " so it maps nothing: \"Replication lag\""), matched.out());
        ServerMetricsPayload m = new ServerMetricsPayloadLoader()
                .read(mapped, RunIdentity.of(new JtlParser().parse(PLAIN_JTL, WirCollect.DEFAULT_TEST_NAME))).payload();
        assertNotNull(m);
        assertNull(m.getSources().get(0).getTimeZone(), "no --time-zone, none recorded");
        boolean deadlocks = false;
        for (ServerMetricsPayload.SeriesRecord series : m.getSeries()) {
            deadlocks |= "deadlocks".equals(series.getMetric());
        }
        assertTrue(deadlocks, "the mapped column was collected: " + m.getSeries());

        // ...and a long file says nothing of columns - having read it, so the silence means something.
        Streams longFile = new Streams();
        assertEquals(0, WirCollect.run(new String[] {"--jtl", PLAIN_JTL.getPath(), "--annotations", componentsOnly.toString(),
                "--out", dir.resolve("long.json").toString(), "--csv", "../test-plans/server-catalogue/e27-metrics.csv",
                "--step", "1s", "--no-wait"}, longFile.outStream(), longFile.errStream(), Collections.<String, String>emptyMap()),
                longFile.out() + longFile.err());
        assertTrue(longFile.out().contains("wir-collect: OK orders-db/connections.used.pct coverage="), longFile.out());
        assertFalse(longFile.out().contains("not mapped") || longFile.out().contains("do not ask for")
                || longFile.out().contains("points are"), longFile.out());
    }

    /**
     * The committed payloads are the collector's output, frozen on the day they were collected, and
     * every browser fixture and golden reads them. Without this, a change to what the collector
     * writes - a reason's words, a status, a point - could never fail anything: the fixtures kept
     * showing the old collector's work (research UI-10). Re-collected here with today's code and
     * compared series by series; only the collection's identity (collectionId, collectedAt) and the
     * CSV's recorded path may differ. When it fails, re-collect the file with the command in its
     * README and commit it with the change that moved it.
     */
    @Test
    void theCommittedPayloadsAreWhatTodaysCollectorWritesFromTheirCsv() throws Exception {
        String[][] cases = {
                {"../test-plans/server-metrics/e26-server.json", "../test-plans/server-metrics/e26-metrics.csv",
                        "../test-plans/server-metrics/server-metrics.json"},
                {"../test-plans/server-catalogue/e27-server.json", "../test-plans/server-catalogue/e27-metrics.csv",
                        "../test-plans/server-catalogue/server-metrics.json"},
                {"../test-plans/goldens/gate-server.json", "../test-plans/goldens/server-metrics.csv",
                        "../test-plans/goldens/server-metrics.json"},
        };
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        for (String[] c : cases) {
            Path out = dir.resolve(new File(c[2]).getParentFile().getName() + ".json");
            Streams s = new Streams();
            assertEquals(0, WirCollect.run(new String[] {"--jtl", PLAIN_JTL.getPath(), "--annotations", c[0],
                    "--csv", c[1], "--step", "1s", "--out", out.toString(), "--no-wait"},
                    s.outStream(), s.errStream(), Collections.<String, String>emptyMap()), s.out() + s.err());
            com.fasterxml.jackson.databind.JsonNode now = json.readTree(out.toFile());
            com.fasterxml.jackson.databind.JsonNode committed = json.readTree(new File(c[2]));
            assertEquals(committed.get("runId"), now.get("runId"), c[2]);
            assertEquals(committed.get("window"), now.get("window"), c[2]);
            assertEquals(committed.get("series"), now.get("series"), c[2] + " is not what today's collector writes from "
                    + c[1] + " - re-collect it with the command in its README and commit it with the change");
            for (int i = 0; i < committed.get("sources").size(); i++) {
                com.fasterxml.jackson.databind.node.ObjectNode a =
                        ((com.fasterxml.jackson.databind.node.ObjectNode) committed.get("sources").get(i)).deepCopy();
                com.fasterxml.jackson.databind.node.ObjectNode b =
                        ((com.fasterxml.jackson.databind.node.ObjectNode) now.get("sources").get(i)).deepCopy();
                a.remove("resolvedUrl");
                b.remove("resolvedUrl");
                assertEquals(a, b, c[2] + " sources[" + i + "]");
            }
        }
    }

    @Test
    void aComponentWhoseEveryCatalogueMetricCameBackEmptyIsSaidOnceAboutTheComponent() throws Exception {
        // Only events' rows: orders-db's one asserted metric comes back EMPTY, and the cause is the
        // component (its match, or an exporter nobody scrapes), so the collector says so about it.
        Path csv = dir.resolve("only-events.csv");
        StringBuilder rows = new StringBuilder("time,component,metric,instance,value\n");
        for (String line : Files.readAllLines(new File("../test-plans/server-catalogue/e27-metrics.csv").toPath())) {
            if (line.contains(",events,")) {
                rows.append(line).append('\n');
            }
        }
        Files.write(csv, rows.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Streams s = new Streams();
        assertEquals(0, WirCollect.run(new String[] {"--jtl", PLAIN_JTL.getPath(), "--annotations",
                "../test-plans/server-catalogue/e27-server.json", "--csv", csv.toString(), "--step", "1s",
                "--out", dir.resolve("p.json").toString(), "--no-wait"},
                s.outStream(), s.errStream(), Collections.<String, String>emptyMap()), s.out() + s.err());
        assertTrue(s.out().contains("wir-collect: WARNING component orders-db: every catalogue metric it asks for (1)"
                + " came back empty - this source has none of its postgres series, or its exporter is not collected"),
                s.out());
        assertFalse(s.out().contains("component events:"), s.out());
    }

    @Test
    void aLongFileNamesItsUnaskedSeriesTheRunWindowAndRefusesAMissingFile() throws Exception {
        Path ann = dir.resolve("custom.json");
        Files.write(ann, ("{\"serverMetrics\":{\"components\":{\"app\":{\"type\":\"custom\"}},"
                + "\"asserts\":[{\"id\":\"cpu\",\"component\":\"app\",\"query\":\"cpu.pct\",\"fail\":{\"gt\":90}}]}}")
                .getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder("time,component,metric,instance,value\n");
        for (int t = 0; t <= 48; t++) {
            sb.append(1_700_000_000_000L + t * 1000L).append(",App,cpu.pct,,41\n");
        }
        Path csv = dir.resolve("case.csv");
        Files.write(csv, sb.toString().getBytes(StandardCharsets.UTF_8));
        Streams s = new Streams();
        assertEquals(0, WirCollect.run(new String[] {"--jtl", PLAIN_JTL.getPath(), "--annotations", ann.toString(),
                "--out", dir.resolve("case.json").toString(), "--csv", csv.toString(), "--step", "1s", "--no-wait"},
                s.outStream(), s.errStream(), Collections.<String, String>emptyMap()), s.out() + s.err());
        // The near-miss fails the asked series by name, and the rows' own name is listed.
        assertTrue(s.out().contains("wir-collect: FAILED app/cpu.pct - no row names component app, metric cpu.pct;"
                + " 49 rows name App/cpu.pct"), s.out());
        assertTrue(s.out().contains("wir-collect: 1 series in the file is not asked for by the annotations"
                + " (component/metric must match exactly): \"App/cpu.pct\""), s.out());
        assertTrue(s.out().contains("wir-collect: run window 2023-11-14T22:13:20.026Z .. 2023-11-14T22:14:08.490Z ("),
                s.out());

        // A --csv that does not exist is a usage fault, as a missing --jtl is: nothing written, exit 1.
        Streams missing = new Streams();
        assertEquals(1, WirCollect.run(new String[] {"--jtl", PLAIN_JTL.getPath(), "--annotations", ann.toString(),
                "--out", dir.resolve("missing.json").toString(), "--csv", dir.resolve("nope.csv").toString(),
                "--no-wait"}, missing.outStream(), missing.errStream(), Collections.<String, String>emptyMap()));
        assertTrue(missing.err().contains("wir-collect: CSV file does not exist: "), missing.err());
        assertFalse(Files.exists(dir.resolve("missing.json")));
    }

    @Test
    void aWideColumnNamingASeriesTheAnnotationsDoNotAskForIsSaid() throws Exception {
        // A legend with a typo in the component is component/metric/instance to the convention, so it
        // is not "not mapped" - and nothing asks for order-db. Without this line the only trace is an
        // EMPTY orders-db series, with nothing pointing at the column that held its data.
        Path componentsOnly = dir.resolve("components-only.json");
        Files.write(componentsOnly, ("{\"serverMetrics\":{\"components\":{"
                + "\"orders-db\":{\"type\":\"postgres\"}},\"asserts\":[]}}").getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder("\"Time\",\"order-db/connections.used.pct/pg-1\","
                + "\"orders-db/connections.active/pg-1\"\r\n");
        for (int t = 0; t <= 48; t++) {
            sb.append(1_700_000_000_000L + t * 1000L).append(",41,7\r\n");
        }
        Path csv = dir.resolve("typo.csv");
        Files.write(csv, sb.toString().getBytes(StandardCharsets.UTF_8));
        Streams s = new Streams();
        assertEquals(0, WirCollect.run(new String[] {"--jtl", PLAIN_JTL.getPath(), "--annotations", componentsOnly.toString(),
                "--out", dir.resolve("typo.json").toString(), "--csv", csv.toString(), "--layout", "wide",
                "--step", "1s", "--no-wait"}, s.outStream(), s.errStream(), Collections.<String, String>emptyMap()),
                s.out() + s.err());
        assertTrue(s.out().contains("wir-collect: 1 column names a component and metric the annotations do not ask for:"
                + " \"order-db/connections.used.pct/pg-1\""), s.out());
        assertFalse(s.out().contains("not mapped"), s.out());
        assertTrue(s.out().contains("wir-collect: OK orders-db/connections.active coverage=100%"), s.out());
    }

    @Test
    void theNotAskedForLineCountsAndCutsShortAsTheUnmappedOneDoes() {
        assertEquals("2 columns name a component and metric the annotations do not ask for: \"a/b\", \"c/d/e\"",
                WirCollect.unaskedColumnsLine(java.util.Arrays.asList("a/b", "c/d/e")));
        assertNull(WirCollect.unaskedColumnsLine(Collections.<String>emptyList()));
    }

    @Test
    void theSpacingWarningIsForASpacingMoreThanHalfAStepAboveTheStepOrOneThatLeftTheSeriesShortOfTheFloor() {
        ServerMetricsPayload.SeriesRecord s = new ServerMetricsPayload.SeriesRecord();
        s.setComponent("orders-db");
        s.setMetric("up");
        s.setStatus("OK");
        java.util.function.IntFunction<ServerMetricsPayload.SeriesRecord> every = ms -> {
            ServerMetricsPayload.InstanceRecord inst = new ServerMetricsPayload.InstanceRecord();
            List<ServerMetricsPayload.Point> pts = new java.util.ArrayList<>();
            for (int i = 0; i < 5; i++) {
                pts.add(new ServerMetricsPayload.Point(1_700_000_000_000L + (long) i * ms, 1));
            }
            inst.setPoints(pts);
            s.setInstances(Collections.singletonList(inst));
            return s;
        };
        assertEquals("orders-db/up points are 60s apart but --step is 15s - coverage counts 15s slots; pass --step 60s",
                WirCollect.spacingWarning("orders-db/up", every.apply(60_000), 15_000));
        // 1.5 x the step is still the step's grid, near enough: nothing to say.
        assertNull(WirCollect.spacingWarning("orders-db/up", every.apply(22_500), 15_000));
        assertNotNull(WirCollect.spacingWarning("orders-db/up", every.apply(22_501), 15_000));
        assertNull(WirCollect.spacingWarning("orders-db/up", every.apply(15_000), 15_000));
        // Finer than the step is never a coverage problem.
        assertNull(WirCollect.spacingWarning("orders-db/up", every.apply(1_000), 15_000));
        assertEquals("orders-db/up points are 7.5s apart but --step is 1s - coverage counts 1s slots; pass --step 7.5s",
                WirCollect.spacingWarning("orders-db/up", every.apply(7_500), 1_000));

        // Above the step and within 1.5 x it, said when it left the series short of the coverage
        // floor: 20 s rows, a common panel interval, in 15 s slots cover three slots in four - 75%,
        // INSUFFICIENT under the default 0.9, for a reason no other line gives.
        assertNull(WirCollect.spacingWarning("orders-db/up", every.apply(20_000), 15_000), "the floor was met");
        s.setStatus("INSUFFICIENT");
        assertEquals("orders-db/up points are 20s apart but --step is 15s - coverage counts 15s slots; pass --step 20s",
                WirCollect.spacingWarning("orders-db/up", every.apply(20_000), 15_000));
        // ...and never at or under the step: short of the floor there is missing data, not a coarse export.
        assertNull(WirCollect.spacingWarning("orders-db/up", every.apply(15_000), 15_000));
        assertNull(WirCollect.spacingWarning("orders-db/up", every.apply(1_000), 15_000));
        s.setInstances(Collections.<ServerMetricsPayload.InstanceRecord>emptyList());
        assertNull(WirCollect.spacingWarning("orders-db/up", s, 15_000), "no points, no spacing");
    }

    // ---- --csv-columns, --component: a long export another tool named -----------------------------

    @Test
    void csvColumnsAndComponentAreLongCsvFlagsAndAreRefusedWhenTheyCannotBeRead() throws Exception {
        String[] base = {"--jtl", "a", "--annotations", "b", "--out", "c"};
        java.util.function.Function<String[], String> refusal = more -> assertThrows(IllegalArgumentException.class,
                () -> WirCollect.parseArgs(concat(base, more))).getMessage();
        // Each is about how a CSV is read, so each goes with --csv...
        assertEquals("--csv-columns goes with --csv", refusal.apply(new String[] {"--recording", "r", "--csv-columns", "time=_time"}));
        assertEquals("--component goes with --csv",
                refusal.apply(new String[] {"--prometheus-url", "https://p/api/v1", "--component", "orders-db"}));
        // ...and with the long layout: a wide file's columns are series, named by their headers or --columns.
        assertEquals("--csv-columns goes with the long layout - a wide file's columns are mapped by --columns",
                refusal.apply(new String[] {"--csv", "m.csv", "--layout", "wide", "--csv-columns", "time=_time"}));
        assertEquals("--component goes with the long layout - a wide file names each column's component in its header"
                + " or --columns", refusal.apply(new String[] {"--csv", "m.csv", "--layout", "wide", "--component", "db"}));
        // The spec's own refusals, naming the key.
        assertEquals("--csv-columns key 'metrc' is not one of time, component, metric, instance, value",
                refusal.apply(new String[] {"--csv", "m.csv", "--csv-columns", "time=_time,metrc=_field"}));
        assertEquals("--csv-columns gives value twice",
                refusal.apply(new String[] {"--csv", "m.csv", "--csv-columns", "value=_value,value=v"}));
        assertEquals("--csv-columns time= names no column", refusal.apply(new String[] {"--csv", "m.csv", "--csv-columns", "time="}));
        assertEquals("--csv-columns needs key=column pairs, e.g. time=_time,value=_value",
                refusal.apply(new String[] {"--csv", "m.csv", "--csv-columns="}));
        assertEquals("--component needs a component id, e.g. orders-db",
                refusal.apply(new String[] {"--csv", "m.csv", "--component", " "}));
        // A row's component is said in one place.
        assertEquals("--component and --csv-columns component= both say which component a row is in - give one",
                refusal.apply(new String[] {"--csv", "m.csv", "--csv-columns", "component=svc", "--component", "orders-db"}));
        // A usage error, exit 1, through run() as well.
        Streams s = new Streams();
        assertEquals(1, WirCollect.run(concat(base, new String[] {"--csv", "m.csv", "--csv-columns", "metrc=_field"}),
                s.outStream(), s.errStream(), Collections.<String, String>emptyMap()));
        assertTrue(s.err().contains("wir-collect: --csv-columns key 'metrc' is not one of"), s.err());
        assertTrue(s.err().contains("usage:"), s.err());

        // Good ones reach the source: the spec normalised, the component trimmed; --layout long is the long layout.
        com.jmeterwebinsightreport.core.servermetrics.ServerSource mapped = WirCollect.collectionSource(WirCollect.parseArgs(
                concat(base, new String[] {"--csv", "m.csv", "--layout", "long", "--csv-columns",
                        "value=_value, metric=_measurement+_field ,time=_time", "--component", " orders-db "})));
        assertEquals("time=_time,metric=_measurement+_field,value=_value", mapped.csvColumnSpec());
        assertEquals("orders-db", mapped.csvComponent());
        com.jmeterwebinsightreport.core.servermetrics.ServerSource plain = WirCollect.collectionSource(
                WirCollect.parseArgs(concat(base, new String[] {"--csv", "m.csv"})));
        assertNull(plain.csvColumnSpec());
        assertNull(plain.csvComponent());

        // --help says what each does.
        s = new Streams();
        WirCollect.run(new String[] {"--help"}, s.outStream(), s.errStream(), Collections.<String, String>emptyMap());
        assertTrue(s.out().contains("  --csv-columns SPEC "), s.out());
        assertTrue(s.out().contains("metric=_measurement+_field"), s.out());
        assertTrue(s.out().contains("  --component ID "), s.out());
        assertTrue(WirCollect.USAGE.contains("--csv FILE [--from TOOL] [--layout wide [--columns MAP]] [--csv-columns SPEC]"
                + " [--component ID] [--time-zone ZONE]"), WirCollect.USAGE);
    }

    /**
     * An InfluxDB v2 annotated CSV of {@code orders-db}'s connections over plain.jtl's window, one row a
     * second, in two blocks whose headers differ: the second has a region tag and its columns in
     * another order. No component column: InfluxDB knows nothing of components.
     */
    private Path influxExport(String name) throws Exception {
        StringBuilder sb = new StringBuilder("#group,false,false,false,false,true,true\n"
                + "#datatype,string,long,dateTime:RFC3339,double,string,string\n"
                + "#default,_result,,,,,\n"
                + ",result,table,_time,_value,_field,host\n");
        for (int t = 0; t <= 24; t++) {
            sb.append(",,0,").append(java.time.Instant.ofEpochMilli(1_700_000_000_000L + t * 1000L))
                    .append(',').append(40 + t % 7).append(",connections.used.pct,pg-1\n");
        }
        sb.append("\n#group,false,false,true,false,false,true,false\n"
                + "#datatype,string,long,string,double,dateTime:RFC3339,string,string\n"
                + "#default,_result,,,,,,\n"
                + ",result,table,host,_value,_time,_field,region\n");
        for (int t = 25; t <= 48; t++) {
            sb.append(",,1,pg-1,").append(40 + t % 7).append(',')
                    .append(java.time.Instant.ofEpochMilli(1_700_000_000_000L + t * 1000L)).append(",connections.used.pct,east\n");
        }
        Path p = dir.resolve(name);
        Files.write(p, sb.toString().getBytes(StandardCharsets.UTF_8));
        return p;
    }

    @Test
    void anInfluxAnnotatedExportIsCollectedIntoTheComponentTheFlagNamesAndThePayloadSaysHow() throws Exception {
        Path annotations = dir.resolve("components-only.json");
        Files.write(annotations, ("{\"serverMetrics\":{\"components\":{\"orders-db\":{\"type\":\"postgres\"},"
                + "\"events\":{\"type\":\"kafka\"}},\"asserts\":[]}}").getBytes(StandardCharsets.UTF_8));
        Path csv = influxExport("influx.csv");
        File out = dir.resolve("influx.json").toFile();
        Streams s = new Streams();
        assertEquals(0, WirCollect.run(new String[] {"--jtl", PLAIN_JTL.getPath(), "--annotations", annotations.toString(),
                "--out", out.getPath(), "--csv", csv.toString(), "--csv-columns", "time=_time,value=_value,metric=_field,instance=host",
                "--component", "orders-db", "--step", "1s", "--no-wait"}, s.outStream(), s.errStream(),
                Collections.<String, String>emptyMap()), s.out() + s.err());
        assertTrue(s.out().contains("wir-collect: OK orders-db/connections.used.pct coverage=100%"), s.out());

        ServerMetricsPayload p = new ServerMetricsPayloadLoader()
                .read(out, RunIdentity.of(new JtlParser().parse(PLAIN_JTL, WirCollect.DEFAULT_TEST_NAME))).payload();
        assertNotNull(p);
        assertEquals("time=_time,metric=_field,instance=host,value=_value", p.getSources().get(0).getColumns());
        assertEquals("orders-db", p.getSources().get(0).getComponent());
        assertEquals(1, p.getSeries().size(), p.getSeries().toString());
        ServerMetricsPayload.SeriesRecord conns = p.getSeries().get(0);
        assertEquals("orders-db", conns.getComponent());
        assertEquals(Collections.singletonMap("instance", "pg-1"), conns.getInstances().get(0).getLabels());
        // Both blocks, one series: 49 points, the first block's first and the second block's last.
        List<ServerMetricsPayload.Point> pts = conns.getInstances().get(0).getPoints();
        assertEquals(49, pts.size());
        assertEquals(40.0, pts.get(0).getValue());
        assertEquals(1_700_000_048_000L, pts.get(48).getTs());
        assertEquals(40.0 + 48 % 7, pts.get(48).getValue());

        // A component the annotations do not declare would put every row where nothing asks: refused,
        // exit 1, nothing written.
        File typo = dir.resolve("typo.json").toFile();
        Streams t = new Streams();
        assertEquals(1, WirCollect.run(new String[] {"--jtl", PLAIN_JTL.getPath(), "--annotations", annotations.toString(),
                "--out", typo.getPath(), "--csv", csv.toString(), "--csv-columns", "time=_time,value=_value,metric=_field",
                "--component", "ordrs-db", "--no-wait"}, t.outStream(), t.errStream(), Collections.<String, String>emptyMap()));
        assertTrue(t.err().contains("wir-collect: --component 'ordrs-db' is not declared under serverMetrics.components"
                + " (declared: orders-db, events)"), t.err());
        assertFalse(typo.exists());

        // A plain long collection writes neither key on its source.
        File plain = dir.resolve("plain.json").toFile();
        assertEquals(0, WirCollect.run(new String[] {"--jtl", PLAIN_JTL.getPath(), "--annotations", annotations.toString(),
                "--out", plain.getPath(), "--csv", "../test-plans/server-catalogue/e27-metrics.csv", "--step", "1s", "--no-wait"},
                new Streams().outStream(), new Streams().errStream(), Collections.<String, String>emptyMap()));
        JsonNode source = new ObjectMapper().readTree(plain).get("sources").get(0);
        assertFalse(source.has("columns") || source.has("component"), source.toString());
    }

    @Test
    void aLongListOfUnmappedColumnsIsCutShort() {
        assertEquals("1 column not mapped to a component and metric: \"Free text\"",
                WirCollect.unmappedColumnsLine(Collections.singletonList("Free text")));
        List<String> many = new java.util.ArrayList<>();
        for (int i = 1; i <= 14; i++) {
            many.add("h" + i);
        }
        assertEquals("14 columns not mapped to a component and metric: \"h1\", \"h2\", \"h3\", \"h4\", \"h5\", \"h6\","
                + " \"h7\", \"h8\", \"h9\", \"h10\" and 4 more", WirCollect.unmappedColumnsLine(many));
        assertNull(WirCollect.unmappedColumnsLine(Collections.<String>emptyList()));
    }

    @Test
    void usageAndInputFaultsExitOneAndSayWhy() throws Exception {
        Streams s = new Streams();
        assertEquals(1, WirCollect.run(new String[] {"--jtl", "x"}, s.outStream(), s.errStream(),
                Collections.<String, String>emptyMap()));
        assertTrue(s.err().contains("--annotations is required"), s.err());
        assertTrue(s.err().contains("usage:"), s.err());

        s = new Streams();
        assertEquals(1, WirCollect.run(new String[] {"--jtl", dir.resolve("nope.jtl").toString(), "--annotations", "a",
                "--out", "o", "--recording", "r"}, s.outStream(), s.errStream(), Collections.<String, String>emptyMap()));
        assertTrue(s.err().contains("JTL does not exist"), s.err());

        // Annotations with no serverMetrics: nothing to collect is a usage error, not an empty payload.
        Path plain = dir.resolve("plain.json");
        Files.write(plain, "{\"testNotes\":\"n\"}".getBytes(StandardCharsets.UTF_8));
        s = new Streams();
        assertEquals(1, WirCollect.run(collect(PLAIN_JTL.getPath(), plain.toString(), dir.resolve("o.json").toString(),
                dir.resolve("rec")), s.outStream(), s.errStream(), Collections.<String, String>emptyMap()));
        assertTrue(s.err().contains("declares no serverMetrics.asserts and no component of a catalogue type"
                + " (postgres, kafka, redis, host-linux, aws-ec2, oracle, influxdb2, influxdb3, jvm, http-service,"
                + " kubernetes-pod, mysql) - nothing to collect"),
                s.err());

        Map<String, String> parsed = WirCollect.parseArgs(new String[] {"--jtl", "a", "--annotations", "b", "--out", "c",
                "--recording", "r", "--no-wait", "--env", "TEST"});
        assertEquals("TEST", parsed.get("env"));
        assertEquals("true", parsed.get("no-wait"));
        assertEquals("TEST", WirCollect.parseArgs(new String[] {"--jtl", "a", "--annotations", "b", "--out", "c",
                "--recording", "r", "--env=TEST"}).get("env"), "--key=value is the same option");

        // A misspelt option is refused, never kept: --test_name would hash the run under the
        // default name and the payload would be refused two steps later as another run's.
        s = new Streams();
        assertEquals(1, WirCollect.run(new String[] {"--jtl", PLAIN_JTL.getPath(), "--annotations", "a", "--out", "o",
                "--test_name", "Checkout"}, s.outStream(), s.errStream(), Collections.<String, String>emptyMap()));
        assertTrue(s.err().contains("unknown option --test_name"), s.err());
        assertTrue(s.err().contains("usage:"), s.err());
        assertThrows(IllegalArgumentException.class, () -> WirCollect.parseArgs(new String[] {"--jtl", "a",
                "--annotations", "b", "--out", "c", "--maxwait", "5m"}));
        assertThrows(IllegalArgumentException.class, () -> WirCollect.parseArgs(new String[] {"--jtl", "a",
                "--annotations", "b", "--out", "c", "--env", "A", "--env", "B"}), "given twice");
    }

    @Test
    void aClockOffsetIsASignedDurationAndReachesTheSource() {
        assertEquals(Long.valueOf(-30_000L), WirCollect.clockOffsetMillis("-30s"));
        assertEquals(Long.valueOf(2_500L), WirCollect.clockOffsetMillis("+2500ms"));
        assertEquals(Long.valueOf(60_000L), WirCollect.clockOffsetMillis("1m"));
        assertEquals(Long.valueOf(0L), WirCollect.clockOffsetMillis("0"));
        assertNull(WirCollect.clockOffsetMillis("-30"), "a bare number is ambiguous between s and ms");
        assertNull(WirCollect.clockOffsetMillis("soon"));
        String[] base = {"--jtl", "a", "--annotations", "b", "--out", "c", "--csv", "m.csv"};
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> WirCollect.parseArgs(
                concat(base, new String[] {"--clock-offset", "30"})));
        assertTrue(e.getMessage().startsWith("--clock-offset must be a signed duration"), e.getMessage());
        assertEquals(-30_000L, WirCollect.collectionSource(WirCollect.parseArgs(
                concat(base, new String[] {"--clock-offset", "-30s"}))).clockOffsetMs());
        assertEquals(0L, WirCollect.collectionSource(WirCollect.parseArgs(base)).clockOffsetMs());
    }

    private static String[] concat(String[] a, String[] b) {
        String[] out = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    @Test
    void thePayloadIsWrittenWithLfOnEveryOs() throws Exception {
        com.jmeterwebinsightreport.core.servermetrics.collect.ServerMetricsPayload p =
                new com.jmeterwebinsightreport.core.servermetrics.collect.ServerMetricsPayload();
        p.setRunId("abcdef0123456789");
        String text = WirCollect.payloadText(p);
        assertFalse(text.contains("\r"), "a CR made the same collection a different file on Windows");
        assertTrue(text.startsWith("{\n  \"schema\" : "), text);
    }

    @Test
    void whereTheMetricsComeFromIsExactlyOneFlagAndCredentialsAreByReference() {
        // C8: the connection is the collector's to be told, never the file's.
        String[] base = {"--jtl", "a", "--annotations", "b", "--out", "c"};
        java.util.function.Function<String[], String> refusal = more -> {
            String[] args = new String[base.length + more.length];
            System.arraycopy(base, 0, args, 0, base.length);
            System.arraycopy(more, 0, args, base.length, more.length);
            return assertThrows(IllegalArgumentException.class, () -> WirCollect.parseArgs(args)).getMessage();
        };
        assertTrue(refusal.apply(new String[0]).contains("--prometheus-url URL, --recording DIR, or --csv FILE"));
        assertTrue(refusal.apply(new String[] {"--prometheus-url", "https://p/api/v1", "--recording", "r"})
                .contains("give one"));
        assertEquals("--recording and --csv are 2 places to fetch from - give one",
                refusal.apply(new String[] {"--recording", "r", "--csv", "m.csv"}));
        assertTrue(refusal.apply(new String[] {"--csv", " "}).contains("--csv needs a file"));
        assertTrue(refusal.apply(new String[] {"--recording", "r", "--prometheus-token-env", "T"})
                .contains("goes with --prometheus-url"));
        assertTrue(refusal.apply(new String[] {"--prometheus-url", "https://alice:pw@p/api/v1"})
                .contains("carries credentials"));
        assertTrue(refusal.apply(new String[] {"--prometheus-url", "https://p/api/v1?api_key=abc"})
                .contains("${secret.NAME}"));
        assertTrue(refusal.apply(new String[] {"--recording", "r", "--step", "15"}).contains("--step must be"));

        // ...and a good set becomes the collection's one source, named for every assert.
        Map<String, String> prom = WirCollect.parseArgs(new String[] {"--jtl", "a", "--annotations", "b", "--out", "c",
                "--prometheus-url", "https://p/api/v1?api_key=${secret.KEY}", "--prometheus-token-env", "PROM_TOKEN",
                "--step", "30s"});
        com.jmeterwebinsightreport.core.servermetrics.ServerSource s = WirCollect.collectionSource(prom);
        assertEquals(com.jmeterwebinsightreport.core.servermetrics.ServerSource.COLLECTION_ID, s.id());
        assertEquals(com.jmeterwebinsightreport.core.servermetrics.ServerSource.Type.PROMETHEUS, s.type());
        assertEquals(30_000L, s.stepMillis());
        assertEquals("PROM_TOKEN", s.authEnv().get("bearerTokenEnv"));
        com.jmeterwebinsightreport.core.servermetrics.ServerSource rec = WirCollect.collectionSource(
                WirCollect.parseArgs(new String[] {"--jtl", "a", "--annotations", "b", "--out", "c", "--recording", "rec/"}));
        assertEquals(com.jmeterwebinsightreport.core.servermetrics.ServerSource.Type.FILE, rec.type());
        assertEquals("rec/", rec.path(), "as typed: no local path is made absolute and published");
        assertEquals(com.jmeterwebinsightreport.core.servermetrics.ServerSource.DEFAULT_STEP_MILLIS, rec.stepMillis());
        com.jmeterwebinsightreport.core.servermetrics.ServerSource csv = WirCollect.collectionSource(
                WirCollect.parseArgs(new String[] {"--jtl", "a", "--annotations", "b", "--out", "c",
                        "--csv", "m.csv", "--step", "1s"}));
        assertEquals(com.jmeterwebinsightreport.core.servermetrics.ServerSource.Type.CSV, csv.type());
        assertEquals("m.csv", csv.path(), "as typed: no local path is made absolute and published");
        assertEquals(1_000L, csv.stepMillis());
    }
}
