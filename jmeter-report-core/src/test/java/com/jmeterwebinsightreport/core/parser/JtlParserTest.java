package com.jmeterwebinsightreport.core.parser;

import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.model.SamplerStatistics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class JtlParserTest {

    @TempDir
    Path tempDir;

    /** The column layout JMeter writes by default. */
    private static final String HEADER = "timeStamp,elapsed,label,responseCode,responseMessage,"
            + "threadName,dataType,success,failureMessage,bytes,sentBytes,grpThreads,allThreads,"
            + "URL,Latency,IdleTime,Connect";

    private File writeJtl(String name, String content) throws IOException {
        Path p = tempDir.resolve(name);
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
        return p.toFile();
    }

    private File getResourceFile(String name) {
        URL url = getClass().getClassLoader().getResource("jtl/" + name);
        assertNotNull(url, "Test fixture file not found: " + name);
        // toURI, not getFile: getFile keeps the URL's %20, so a checkout under a path with a space
        // (C:\Users\First Last\...) found no fixture at all.
        try {
            return new File(url.toURI());
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private SamplerStatistics findSampler(ReportData data, String name) {
        return data.getSamplerStatistics().stream()
                .filter(s -> s.getSamplerName().equals(name))
                .findFirst()
                .orElse(null);
    }

    @Test
    void parseValidJtl_returnsCorrectSampleCount() throws IOException {
        JtlParser parser = new JtlParser();
        ReportData data = parser.parse(getResourceFile("valid-small.jtl"), "TestRun");

        int totalSamples = data.getSamplerStatistics().stream()
                .mapToInt(s -> (int) s.getSampleCount())
                .sum();
        assertEquals(50, totalSamples);
    }

    @Test
    void parseValidJtl_returnsCorrectSamplerNames() throws IOException {
        JtlParser parser = new JtlParser();
        ReportData data = parser.parse(getResourceFile("valid-small.jtl"), "TestRun");

        Set<String> names = data.getSamplerStatistics().stream()
                .map(SamplerStatistics::getSamplerName)
                .collect(Collectors.toSet());

        assertEquals(3, names.size());
        assertTrue(names.contains("GET /api/users"));
        assertTrue(names.contains("POST /api/orders"));
        assertTrue(names.contains("GET /api/products"));
    }

    @Test
    void parseValidJtl_calculatesCorrectErrorCount() throws IOException {
        JtlParser parser = new JtlParser();
        ReportData data = parser.parse(getResourceFile("valid-small.jtl"), "TestRun");

        long totalErrors = data.getSamplerStatistics().stream()
                .mapToLong(SamplerStatistics::getErrorCount)
                .sum();
        assertEquals(3, totalErrors);
    }

    @Test
    void parseValidJtl_calculatesCorrectErrorRate() throws IOException {
        JtlParser parser = new JtlParser();
        ReportData data = parser.parse(getResourceFile("valid-small.jtl"), "TestRun");

        // GET /api/users: 1 error out of 20 = 5%
        SamplerStatistics users = findSampler(data, "GET /api/users");
        assertNotNull(users);
        assertEquals(5.0, users.getErrorRate(), 0.01);

        // POST /api/orders: 2 errors out of 15 = 13.33%
        SamplerStatistics orders = findSampler(data, "POST /api/orders");
        assertNotNull(orders);
        assertEquals(13.33, orders.getErrorRate(), 0.01);

        // GET /api/products: 0 errors out of 15 = 0%
        SamplerStatistics products = findSampler(data, "GET /api/products");
        assertNotNull(products);
        assertEquals(0.0, products.getErrorRate(), 0.01);
    }

    @Test
    void parseValidJtl_capturesResponseTimes() throws IOException {
        JtlParser parser = new JtlParser();
        ReportData data = parser.parse(getResourceFile("valid-small.jtl"), "TestRun");

        // GET /api/products: values are 50,60,70,80,90,100,110,120,130,140,150,55,65,75,85
        // min=50, max=150, mean = sum/15 = 1380/15 = 92.0
        SamplerStatistics products = findSampler(data, "GET /api/products");
        assertNotNull(products);
        assertEquals(50.0, products.getMinResponseTime(), 0.01);
        assertEquals(150.0, products.getMaxResponseTime(), 0.01);
        assertEquals(92.0, products.getMeanResponseTime(), 0.01);
    }

    /**
     * X1, the -g -o path. A sampler whose every sample took 0 ms reported a max of 4.9E-324
     * (Double.MIN_VALUE, the smallest positive double), beside a min of 0. Compared exactly.
     */
    @Test
    void parse_allZeroSamplerHasMaxZero() throws IOException {
        File jtl = writeJtl("zero.jtl", HEADER + "\n"
                + "1700000000000,0,ZERO,200,OK,T 1-1,text,true,,100,50,1,1,null,0,0,0\n"
                + "1700000000100,0,ZERO,200,OK,T 1-1,text,true,,100,50,1,1,null,0,0,0\n"
                + "1700000000200,0,ZERO,200,OK,T 1-1,text,true,,100,50,1,1,null,0,0,0\n"
                + "1700000000300,25,REAL,200,OK,T 1-1,text,true,,100,50,1,1,null,20,0,1\n");
        ReportData data = new JtlParser().parse(jtl, "TestRun");

        SamplerStatistics zero = findSampler(data, "ZERO");
        assertNotNull(zero);
        assertEquals(0.0, zero.getMaxResponseTime(), "no sample took 4.9E-324 ms");
        assertEquals(0.0, zero.getMinResponseTime());
        assertEquals(25.0, findSampler(data, "REAL").getMaxResponseTime());
    }

    @Test
    void parseValidJtl_capturesBytesMetrics() throws IOException {
        JtlParser parser = new JtlParser();
        ReportData data = parser.parse(getResourceFile("valid-small.jtl"), "TestRun");

        // GET /api/users: 20 samples, each 500 bytes received, 100 bytes sent
        SamplerStatistics users = findSampler(data, "GET /api/users");
        assertNotNull(users);
        assertEquals(20 * 500.0, users.getTotalReceivedBytes(), 0.01);
        assertEquals(20 * 100.0, users.getTotalSentBytes(), 0.01);
    }

    @Test
    void parseValidJtl_capturesConnectAndLatency() throws IOException {
        JtlParser parser = new JtlParser();
        ReportData data = parser.parse(getResourceFile("valid-small.jtl"), "TestRun");

        // GET /api/users: all Connect=5, Latency=10
        SamplerStatistics users = findSampler(data, "GET /api/users");
        assertNotNull(users);
        assertEquals(5.0, users.getMeanConnectTime(), 0.01);
        assertEquals(10.0, users.getMeanLatency(), 0.01);

        // POST /api/orders: all Connect=10, Latency=20
        SamplerStatistics orders = findSampler(data, "POST /api/orders");
        assertNotNull(orders);
        assertEquals(10.0, orders.getMeanConnectTime(), 0.01);
        assertEquals(20.0, orders.getMeanLatency(), 0.01);
    }

    @Test
    void parseEmptyJtl_returnsEmptyReportData() throws IOException {
        JtlParser parser = new JtlParser();
        // header-only.jtl has only the header line and no data rows.
        // The parser creates a LiveStatisticsEngine and calls buildReportData even with 0 samples,
        // resulting in a ReportData with empty sampler statistics.
        ReportData data = parser.parse(getResourceFile("header-only.jtl"), "EmptyTest");
        assertNotNull(data);
        assertTrue(data.getSamplerStatistics().isEmpty());
        assertEquals(0, data.getMetadata().getTotalSamples());
    }

    @Test
    void parseMissingOptionalColumns_stillWorks() throws IOException {
        JtlParser parser = new JtlParser();
        ReportData data = parser.parse(getResourceFile("missing-optional-columns.jtl"), "MinimalTest");

        assertNotNull(data);
        int totalSamples = data.getSamplerStatistics().stream()
                .mapToInt(s -> (int) s.getSampleCount())
                .sum();
        assertEquals(10, totalSamples);
    }

    @Test
    void parseQuotedFields_handlesCorrectly() throws IOException {
        JtlParser parser = new JtlParser();
        ReportData data = parser.parse(getResourceFile("quoted-fields.jtl"), "QuotedTest");

        assertNotNull(data);
        Set<String> names = data.getSamplerStatistics().stream()
                .map(SamplerStatistics::getSamplerName)
                .collect(Collectors.toSet());

        // The file has 3 unique samplers with commas in the names:
        // "GET /api/search?q=hello,world", "POST /api/data,upload", "GET /api/report?fields=a,b,c"
        assertEquals(3, names.size());
        assertTrue(names.contains("GET /api/search?q=hello,world"));
        assertTrue(names.contains("POST /api/data,upload"));
        assertTrue(names.contains("GET /api/report?fields=a,b,c"));
    }

    @Test
    void parseMalformedRows_skipsAndContinues() throws IOException {
        JtlParser parser = new JtlParser();
        ReportData data = parser.parse(getResourceFile("malformed-rows.jtl"), "MalformedTest");

        assertNotNull(data);
        int totalSamples = data.getSamplerStatistics().stream()
                .mapToInt(s -> (int) s.getSampleCount())
                .sum();
        assertEquals(7, totalSamples);
    }

    @Test
    void parseSingleSampler_works() throws IOException {
        JtlParser parser = new JtlParser();
        ReportData data = parser.parse(getResourceFile("single-sampler.jtl"), "SingleTest");

        assertNotNull(data);
        assertEquals(1, data.getSamplerStatistics().size());

        SamplerStatistics stat = data.getSamplerStatistics().get(0);
        assertEquals("GET /api/health", stat.getSamplerName());
        assertEquals(100, stat.getSampleCount());
        assertEquals(0, stat.getErrorCount());
    }

    @Test
    void parseJtl_buildsTimeBuckets() throws IOException {
        JtlParser parser = new JtlParser();
        ReportData data = parser.parse(getResourceFile("valid-small.jtl"), "TestRun");

        assertNotNull(data.getTimeSeries());
        assertFalse(data.getTimeSeries().isEmpty());
        // The data spans from 1700000000000 to 1700000005000 (5 seconds),
        // so there should be multiple 1-second buckets
        assertTrue(data.getTimeSeries().size() >= 2);
    }

    @Test
    void parseTransactionControllers_noHierarchyFromNamesAlone() throws IOException {
        // JTL rows carry no sub-result structure, so transaction grouping cannot be reliably
        // reconstructed. Transaction hierarchy is now derived only from real sub-result
        // relationships (listener mode), never guessed from name prefixes — this avoids falsely
        // nesting/hiding REST-style samplers (audit finding B8).
        JtlParser parser = new JtlParser();
        ReportData data = parser.parse(getResourceFile("transaction-controllers.jtl"), "TCTest");

        assertNotNull(data.getTransactionHierarchy());
        assertTrue(data.getTransactionHierarchy().isEmpty(),
                "JTL parsing must not infer transaction hierarchy from names");

        // Every sampler is treated as top-level; none is falsely marked as a controller or child.
        for (SamplerStatistics s : data.getSamplerStatistics()) {
            assertNull(s.getParentSamplerName(),
                    "sampler '" + s.getSamplerName() + "' should have no parent");
            assertFalse(s.isTransactionController(),
                    "sampler '" + s.getSamplerName() + "' should not be a transaction controller");
        }
    }

    // ---- RFC 4180 / robustness (CORE-7, SEC-5, CORE-16) ----

    private static String row(long ts, String label, String responseMessage) {
        return ts + ",100," + label + ",200," + responseMessage
                + ",Thread Group 1-1,text,true,,500,100,5,5,null,10,0,5";
    }

    @Test
    void parse_quotedFieldSpanningLines_doesNotInventPhantomSamplers() throws IOException {
        // JMeter writes multi-line assertion text and response messages routinely. Reading
        // line-by-line split one record into two, and the tail parsed as its own sample with
        // every column shifted.
        String jtl = HEADER + "\n"
                + row(1700000000000L, "GET /api/users", "\"Assertion failed:\nexpected 200\nbut was 500\"") + "\n"
                + row(1700000001000L, "POST /api/login", "OK") + "\n";
        File file = writeJtl("multiline.jtl", jtl);

        ReportData data = new JtlParser().parse(file, "Multiline");

        Set<String> names = data.getSamplerStatistics().stream()
                .map(SamplerStatistics::getSamplerName).collect(Collectors.toSet());
        assertEquals(Set.of("GET /api/users", "POST /api/login"), names,
                "the embedded newlines must not create a third, invented sampler");
        assertEquals(2, data.getMetadata().getTotalSamples());
    }

    /**
     * An engine failure is not a malformed row (CORE-3).
     *
     * <p>One try used to cover parsing AND feeding the engine, and its catch returned "could not
     * parse". So an engine that threw after {@code recordSample} — in {@code recordError}, say —
     * left the sample in the statistics, called the row unparseable, and, on a record joined from
     * several physical lines, handed each line back to the engine on its own: the same sample
     * recorded twice, and the run reported as if nothing had happened.
     */
    @Test
    void parse_anEngineFailureAfterRecordSample_propagatesAndIsNeverReFed() throws IOException {
        // responseMessage last, so the first physical line of the joined record still carries
        // every column and the fallback parses it as a record of its own.
        String jtl = "timeStamp,elapsed,label,responseCode,success,responseMessage\n"
                + "1700000000000,100,GET /ok,200,true,OK\n"
                + "1700000001000,100,GET /broken,500,false,\"Server error:\nstack trace\"\n";
        File file = writeJtl("engine-failure.jtl", jtl);
        java.util.concurrent.atomic.AtomicInteger recorded = new java.util.concurrent.atomic.AtomicInteger();
        com.jmeterwebinsightreport.core.stats.LiveStatisticsEngine failing =
                new com.jmeterwebinsightreport.core.stats.LiveStatisticsEngine() {
                    @Override
                    public void recordSample(String samplerName, long responseTime, boolean isSuccess,
                                             long bytesReceived, long bytesSent, long timestamp,
                                             long connectTime, long latency) {
                        recorded.incrementAndGet();
                        super.recordSample(samplerName, responseTime, isSuccess, bytesReceived,
                                bytesSent, timestamp, connectTime, latency);
                    }

                    @Override
                    public void recordError(String samplerName, String responseCode,
                                            String responseMessage, long timestamp, String threadName,
                                            String requestUrl, String requestHeaders,
                                            String responseBody) {
                        throw new IllegalStateException("engine broke");
                    }
                };

        RuntimeException thrown = null;
        try {
            new JtlParser().parse(file, "EngineFailure", failing);
        } catch (RuntimeException e) {
            thrown = e;
        }
        assertEquals(2, recorded.get(), "each row reaches the engine once — the failing one was re-fed");
        assertNotNull(thrown, "an engine failure must not be swallowed as an unparseable row");
        assertTrue(String.valueOf(thrown.getMessage()).contains("engine broke"),
                "the failure says what broke: " + thrown.getMessage());
    }

    @Test
    void parse_escapedQuotesBecomeLiteralQuotes() throws IOException {
        // RFC 4180: "" inside a quoted field is one literal quote. Toggling on every quote
        // dropped them, so a label no longer matched its baseline counterpart.
        String jtl = HEADER + "\n"
                + row(1700000000000L, "\"say \"\"hi\"\"\"", "OK") + "\n";
        File file = writeJtl("escaped-quotes.jtl", jtl);

        ReportData data = new JtlParser().parse(file, "Quotes");

        assertEquals(1, data.getSamplerStatistics().size());
        assertEquals("say \"hi\"", data.getSamplerStatistics().get(0).getSamplerName());
    }

    @Test
    void parse_leadingByteOrderMarkIsIgnored() throws IOException {
        // A GUI-saved JTL starts with a BOM, which used to make the first header read
        // "﻿timeStamp" and fail with a misleading missing-column error.
        String bom = String.valueOf((char) 0xFEFF);  // explicit, so source encoding can't matter
        String jtl = bom + HEADER + "\n" + row(1700000000000L, "GET /api/users", "OK") + "\n";
        File file = writeJtl("bom.jtl", jtl);

        ReportData data = new JtlParser().parse(file, "BOM");

        assertEquals(1, data.getSamplerStatistics().size());
        assertEquals("GET /api/users", data.getSamplerStatistics().get(0).getSamplerName());
    }

    @Test
    void parse_xmlJtl_failsWithAnActionableMessage() throws IOException {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testResults version=\"1.2\">\n"
                + "<httpSample t=\"100\" lb=\"GET /api\" s=\"true\"/>\n</testResults>\n";
        File file = writeJtl("results.xml.jtl", xml);

        IOException e = assertThrows(IOException.class, () -> new JtlParser().parse(file, "XML"));
        assertTrue(e.getMessage().contains("XML-format JTL is not supported"), e.getMessage());
        assertTrue(e.getMessage().contains("output_format=csv"), e.getMessage());
    }

    @Test
    void parse_nonEpochTimestamps_failLoudlyInsteadOfProducingABlankReport() throws IOException {
        // With jmeter.save.saveservice.timestamp_format=yyyy/MM/dd HH:mm:ss every row throws.
        // Each one used to be caught and logged at WARN, and parse() returned an empty
        // ReportData — a blank report that looks like a plugin bug.
        String jtl = HEADER + "\n"
                + "\"2026/07/25 00:00:01\",100,GET /api/users,200,OK,T1,text,true,,500,100,5,5,null,10,0,5\n"
                + "\"2026/07/25 00:00:02\",120,GET /api/users,200,OK,T1,text,true,,500,100,5,5,null,10,0,5\n";
        File file = writeJtl("non-epoch.jtl", jtl);

        IOException e = assertThrows(IOException.class,
                () -> new JtlParser().parse(file, "NonEpoch"));
        assertTrue(e.getMessage().contains("No usable samples"), e.getMessage());
        assertTrue(e.getMessage().contains("timestamp_format=ms"), e.getMessage());
    }

    @Test
    void parse_someBadRows_stillReturnsTheGoodOnes() throws IOException {
        String jtl = HEADER + "\n"
                + row(1700000000000L, "GET /api/users", "OK") + "\n"
                + "not,a,valid,row\n"
                + row(1700000001000L, "GET /api/users", "OK") + "\n";
        File file = writeJtl("partial.jtl", jtl);

        ReportData data = new JtlParser().parse(file, "Partial");

        assertEquals(2, data.getMetadata().getTotalSamples(),
                "a few malformed rows must not abort the whole parse");
    }

    @Test
    void parse_isReusableAcrossFilesWithDifferentColumnCounts() throws IOException {
        // Parsing state used to live on the instance, so a second file with a different
        // column count pre-sized its arrays from the first and dropped rows.
        File wide = writeJtl("wide.jtl", HEADER + "\n" + row(1700000000000L, "wide", "OK") + "\n");
        File narrow = writeJtl("narrow.jtl",
                "timeStamp,elapsed,label,responseCode,success\n"
                + "1700000000000,100,narrow,200,true\n"
                + "1700000001000,110,narrow,200,true\n");

        JtlParser parser = new JtlParser();
        ReportData first = parser.parse(wide, "Wide");
        ReportData second = parser.parse(narrow, "Narrow");

        assertEquals(1, first.getMetadata().getTotalSamples());
        assertEquals(2, second.getMetadata().getTotalSamples());
        assertEquals("narrow", second.getSamplerStatistics().get(0).getSamplerName());
    }

    @Test
    void parseCsvLine_rfc4180Cases() {
        assertArrayEquals(new String[]{"a", "b", "c"}, JtlParser.parseCsvLine("a,b,c"));
        assertArrayEquals(new String[]{"a,b", "c"}, JtlParser.parseCsvLine("\"a,b\",c"));
        assertArrayEquals(new String[]{"a\"b", "c"}, JtlParser.parseCsvLine("\"a\"\"b\",c"));
        assertArrayEquals(new String[]{"a\nb", "c"}, JtlParser.parseCsvLine("\"a\nb\",c"));
        assertArrayEquals(new String[]{"", "", ""}, JtlParser.parseCsvLine(",,"));
        assertArrayEquals(new String[]{"trailing", ""}, JtlParser.parseCsvLine("trailing,"));
    }

    @Test
    void parse_strayQuoteDoesNotSwallowTheFollowingValidRows() throws IOException {
        // One row carries a single unbalanced quote. Joining lines purely by quote parity
        // absorbs every following well-formed row until the next odd-quote line, so a stray
        // quote destroyed up to 999 samples while being counted as ONE skipped record.
        StringBuilder jtl = new StringBuilder(HEADER).append("\n");
        jtl.append(row(1700000000000L, "GET /before", "OK")).append("\n");
        jtl.append("1700000001000,100,GET /stray\",200,OK,T1,text,true,,500,100,5,5,null,10,0,5\n");
        for (int i = 0; i < 20; i++) {
            jtl.append(row(1700000002000L + i, "GET /after", "OK")).append("\n");
        }
        File file = writeJtl("stray-quote.jtl", jtl.toString());

        ReportData data = new JtlParser().parse(file, "StrayQuote");

        SamplerStatistics after = findSampler(data, "GET /after");
        assertNotNull(after, "rows following the stray quote must survive");
        assertEquals(20, after.getSampleCount(), "every following valid row must be recovered");
        assertNotNull(findSampler(data, "GET /before"));
    }

    @Test
    void parse_genuineMultilineFieldIsStillOneRecord() throws IOException {
        // The recovery path must not break the case the line joining exists for.
        String jtl = HEADER + "\n"
                + row(1700000000000L, "GET /api/users", "\"line one\nline two\nline three\"") + "\n"
                + row(1700000001000L, "POST /api/login", "OK") + "\n";
        File file = writeJtl("multiline-ok.jtl", jtl);

        ReportData data = new JtlParser().parse(file, "Multiline");

        assertEquals(2, data.getMetadata().getTotalSamples());
        assertEquals(2, data.getSamplerStatistics().size());
    }

    @Test
    void endStampedRowsArePlacedAtTheirStart() throws IOException {
        // sampleresult.timestamp.start=false: each row's timeStamp is its END (B3). A: 1,000 ms ending at
        // ...001000 (started ...000000) and 500 ms ending at ...003000 (started ...002500).
        File jtl = writeJtl("end-stamped.jtl", HEADER + "\n"
                + "1700000001000,1000,A,200,OK,T 1-1,text,true,,100,10,1,1,http://x/a,900,0,5\n"
                + "1700000003000,500,A,200,OK,T 1-1,text,true,,100,10,1,1,http://x/a,400,0,5\n");

        JtlParser parser = new JtlParser();
        parser.setTimestampIsStart(false);
        ReportData data = parser.parse(jtl, "end-stamped");

        assertEquals(1700000000000L, data.getMetadata().getStartTime().toEpochMilli(), "the first start");
        assertEquals(3000, data.getMetadata().getDurationMillis(), "first start to last end, as JMeter reads the file");
        assertEquals(2 / 3.0, findSampler(data, "A").getThroughput(), 1e-9);
        assertEquals(1700000000000L, data.getTimeSeries().get(0).getTimestamp().toEpochMilli(), "bucketed by start");
    }

    @Test
    void startStampedRowsAreReadAsTheyAlwaysWere() throws IOException {
        File jtl = writeJtl("start-stamped.jtl", HEADER + "\n"
                + "1700000001000,1000,A,200,OK,T 1-1,text,true,,100,10,1,1,http://x/a,900,0,5\n"
                + "1700000003000,500,A,200,OK,T 1-1,text,true,,100,10,1,1,http://x/a,400,0,5\n");

        ReportData data = new JtlParser().parse(jtl, "start-stamped");

        assertEquals(1700000001000L, data.getMetadata().getStartTime().toEpochMilli());
        assertEquals(2500, data.getMetadata().getDurationMillis());
    }
}
