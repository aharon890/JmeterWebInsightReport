package com.jmeterwebinsightreport.core.comparison;

import com.jmeterwebinsightreport.core.model.OverallMetrics;
import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.parser.JtlParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Compare baseline is a JMeter results file or run data of any tool, and which one it is is read from the file's
 * first bytes, never from its name (spec 004, User Story 3, FR-001).
 */
class BaselineReaderTest {

    private static final long T = 1_700_000_000_000L;

    private static final String JTL_HEADER = "timeStamp,elapsed,label,responseCode,responseMessage,threadName,"
            + "dataType,success,failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect";
    private static final String[] JTL_ROWS = {
            T + ",120,Login,200,OK,Users 1-1,text,true,,512,128,1,1,http://shop.test/login,100,0,5",
            (T + 1000) + ",80,Browse,500,Server Error,Users 1-1,text,false,,256,64,1,1,http://shop.test/browse,70,0,0",
            (T + 2000) + ",60,Browse,200,OK,Users 1-1,text,true,,256,64,1,1,http://shop.test/browse,50,0,0",
    };

    private static final String JMETER_RUN = "{\"type\":\"run\",\"formatVersion\":\"1.1\",\"tool\":{\"name\":"
            + "\"jmeter\",\"version\":\"5.6.3\"},\"testName\":\"checkout\",\"producer\":{\"name\":"
            + "\"jmeter-web-insight-report\"}}";
    private static final String K6_RUN = "{\"type\":\"run\",\"formatVersion\":\"1.0\",\"tool\":{\"name\":\"k6\","
            + "\"version\":\"2.4.0\"},\"testName\":\"checkout (k6)\",\"producer\":{\"name\":"
            + "\"jmeter-web-insight-report\"},\"notMeasured\":[\"bytes\",\"sentBytes\",\"latency\",\"connect\"]}";
    private static final String[] SAMPLES = {
            "{\"type\":\"sample\",\"name\":\"Login\",\"start\":" + T + ",\"elapsed\":40,\"success\":true,\"code\":\"200\"}",
            "{\"type\":\"sample\",\"name\":\"Browse\",\"start\":" + (T + 1000) + ",\"elapsed\":80,\"success\":false,"
                    + "\"code\":\"500\"}",
            "{\"type\":\"sample\",\"name\":\"Browse\",\"start\":" + (T + 2000) + ",\"elapsed\":60,\"success\":true,"
                    + "\"code\":\"200\"}",
    };

    @TempDir
    Path tmp;

    /** A file of these lines, gzipped or not whatever its name says. */
    private File file(String name, boolean gzip, String first, String... more) throws IOException {
        StringBuilder text = new StringBuilder(first).append('\n');
        for (String line : more) {
            text.append(line).append('\n');
        }
        Path p = tmp.resolve(name);
        byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
        if (gzip) {
            try (OutputStream os = new GZIPOutputStream(Files.newOutputStream(p))) {
                os.write(bytes);
            }
        } else {
            Files.write(p, bytes);
        }
        return p.toFile();
    }

    private static long samples(ReportData data) {
        return OverallMetrics.of(data).getTotalSamples();
    }

    @Test
    void aJMeterResultsFileIsReadByTheJtlParserAsToday() throws IOException {
        File jtl = file("baseline.jtl", false, JTL_HEADER, JTL_ROWS);
        assertEquals(BaselineReader.Kind.JMETER_RESULTS, BaselineReader.kindOf(jtl));
        ReportData read = BaselineReader.read(jtl);
        ReportData today = new JtlParser().parse(jtl, "Baseline");
        assertEquals(samples(today), samples(read));
        assertEquals(today.getSamplerStatistics().size(), read.getSamplerStatistics().size());
        assertNull(read.getMetadata().getToolName(), "a JMeter results file is a JMeter run");
    }

    @Test
    void anXmlResultsFileGoesToTheJtlParserWhichRefusesItAsToday() throws IOException {
        File xml = file("baseline.jtl", false, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
                "<testResults version=\"1.2\">", "<httpSample t=\"120\" lb=\"Login\"/>", "</testResults>");
        assertEquals(BaselineReader.Kind.JMETER_RESULTS, BaselineReader.kindOf(xml));
        IOException e = assertThrows(IOException.class, () -> BaselineReader.read(xml));
        assertTrue(e.getMessage().contains("XML-format JTL is not supported"), e.getMessage());
    }

    @Test
    void aHeaderlessCsvGoesToTheJtlParserWhichDecidesAsToday() throws IOException {
        File csv = file("baseline.csv", false, JTL_ROWS[0], JTL_ROWS[1]);
        assertEquals(BaselineReader.Kind.JMETER_RESULTS, BaselineReader.kindOf(csv));
        IOException e = assertThrows(IOException.class, () -> BaselineReader.read(csv));
        assertTrue(e.getMessage().contains("Missing required JTL column"), e.getMessage());
    }

    @Test
    void runDataIsReadByTheRunDataReaderPlainAndGzipped() throws IOException {
        for (boolean gzip : new boolean[]{false, true}) {
            File runData = file(gzip ? "run-data.jsonl.gz" : "run-data.jsonl", gzip, JMETER_RUN, SAMPLES);
            assertEquals(BaselineReader.Kind.RUN_DATA, BaselineReader.kindOf(runData), runData.getName());
            ReportData read = BaselineReader.read(runData);
            assertEquals(3, samples(read), runData.getName());
            assertEquals(2, read.getSamplerStatistics().size(), runData.getName());
            assertNull(read.getMetadata().getToolName(), "JMeter's run data is a JMeter run");
            assertEquals("5.6.3", read.getMetadata().getJmeterVersion());
        }
    }

    @Test
    void aK6RunsRunDataNamesItsToolAndWhatItDoesNotMeasure() throws IOException {
        File runData = file("k6-run-data.jsonl.gz", true, K6_RUN, SAMPLES);
        ReportData read = BaselineReader.read(runData);
        assertEquals("k6", read.getMetadata().getToolName());
        assertEquals("2.4.0", read.getMetadata().getToolVersion());
        assertTrue(read.getMetadata().getNotMeasured().contains("bytes"), "k6 records no bytes");
        assertEquals(3, samples(read));
    }

    @Test
    void theKindIsReadFromTheContentNeverFromTheName() throws IOException {
        File runDataNamedJtl = file("baseline.jtl", false, K6_RUN, SAMPLES);
        assertEquals(BaselineReader.Kind.RUN_DATA, BaselineReader.kindOf(runDataNamedJtl));
        assertEquals("k6", BaselineReader.read(runDataNamedJtl).getMetadata().getToolName());

        File gzippedRunDataNamedDat = file("baseline.dat", true, K6_RUN, SAMPLES);
        assertEquals(BaselineReader.Kind.RUN_DATA, BaselineReader.kindOf(gzippedRunDataNamedDat));
        assertEquals(3, samples(BaselineReader.read(gzippedRunDataNamedDat)));

        File jtlNamedRunData = file("baseline.jsonl.gz", false, JTL_HEADER, JTL_ROWS);
        assertEquals(BaselineReader.Kind.JMETER_RESULTS, BaselineReader.kindOf(jtlNamedRunData));
        assertEquals(3, samples(BaselineReader.read(jtlNamedRunData)));
    }

    @Test
    void aFileThatIsNeitherIsRefusedWithASentenceNamingItAndWhatABaselineIs() throws IOException {
        File[] neither = {
                file("notes.txt", false, "hello, world", "nothing here"),
                file("page.html", false, "<!doctype html>", "<html><body>a report</body></html>"),
                file("zipped.jsonl.gz", true, "hello, world"),
                file("other.jsonl", false, "{\"type\":\"sample\",\"name\":\"Login\"}"),
                file("empty.jtl", false, ""),
        };
        for (File f : neither) {
            assertEquals(BaselineReader.Kind.NEITHER, BaselineReader.kindOf(f), f.getName());
            BaselineReader.NotABaseline e = assertThrows(BaselineReader.NotABaseline.class,
                    () -> BaselineReader.read(f), f.getName());
            assertTrue(e.getMessage().contains(f.getName()), e.getMessage());
            assertTrue(e.getMessage().contains("neither a JMeter results file"), e.getMessage());
            assertTrue(e.getMessage().contains("nor run data"), e.getMessage());
        }
    }
}
