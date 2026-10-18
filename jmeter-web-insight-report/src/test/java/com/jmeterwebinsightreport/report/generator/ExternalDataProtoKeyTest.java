package com.jmeterwebinsightreport.report.generator;

import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.model.SamplerStatistics;
import com.jmeterwebinsightreport.core.model.TestMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A key named {@code __proto__} survives the external-data sidecar, on both of the generator's
 * paths to it (N2).
 *
 * <p>The sidecar is {@code window.__WIR_DATA__={...};}, which the browser evaluates as a JavaScript
 * object literal, not as JSON — and in an object literal {@code "__proto__": v} sets the object's
 * prototype instead of adding an own key. So a component named {@code __proto__} lost its entry:
 * the Server tab judged its asserts as naming an undeclared component, and a Download wrote them
 * without it, which the next run refuses. The inline payload is unaffected (the template parses it
 * with JSON.parse, where {@code __proto__} is an ordinary key). What the browser makes of the
 * rewritten literal is test-plans/js-unit/external-data-literal.test.js.
 */
class ExternalDataProtoKeyTest {

    @TempDir
    Path dir;

    /** A bare key token: {@code "__proto__":} not preceded by the computed form's {@code [}. */
    private static final Pattern BARE_KEY = Pattern.compile("(?<!\\[)\"__proto__\":");

    private static final String ANNOTATIONS = "{\"serverMetrics\":{"
            + "\"components\":{\"__proto__\":{\"type\":\"custom\",\"description\":\"named __proto__\"}},"
            + "\"asserts\":[{\"id\":\"up\",\"component\":\"__proto__\",\"query\":\"up\","
            + "\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gt\":1}}]}}";

    private static ReportData run() {
        ReportData d = new ReportData();
        List<SamplerStatistics> stats = new ArrayList<>();
        SamplerStatistics s = new SamplerStatistics();
        s.setSamplerName("GET /a");
        s.setSampleCount(100);
        s.setMeanResponseTime(100);
        s.setPercentile95(150);
        s.setThroughput(5);
        stats.add(s);
        d.setSamplerStatistics(stats);
        TestMetadata m = new TestMetadata();
        m.setTestName("Proto key");
        m.setStartTime(Instant.ofEpochMilli(1_700_000_000_000L));
        m.setEndTime(Instant.ofEpochMilli(1_700_000_060_000L));
        m.setDurationMillis(60_000);
        m.setTotalThreads(10);
        m.setTotalSamples(100);
        d.setMetadata(m);
        return d;
    }

    private String sidecar(String name, boolean forced) throws Exception {
        Path annotations = dir.resolve(name + "-annotations.json");
        Files.write(annotations, ANNOTATIONS.getBytes(StandardCharsets.UTF_8));
        ReportConfiguration c = new ReportConfiguration();
        c.setOutputDirectory(dir.resolve(name).toFile());
        c.setAnnotationsFile(annotations.toFile());
        if (forced) {
            c.setExternalDataMode(true);
        } else {
            c.setExternalDataThreshold(1);   // any payload is over it: the threshold path
        }
        ReportArtifacts a = new WebInsightReportGenerator(c).generateReport(run());
        File data = a.file(ReportArtifacts.Kind.DATA);
        assertTrue(data.exists(), "precondition: the payload went to a sidecar");
        return new String(Files.readAllBytes(data.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    void theThresholdPathWritesTheKeyInItsComputedForm() throws Exception {
        String js = sidecar("threshold", false);
        assertTrue(js.contains("[\"__proto__\"]:"), "the key is written as a computed key");
        assertFalse(BARE_KEY.matcher(js).find(), "a bare \"__proto__\": sets the prototype");
        assertTrue(js.contains("\"component\":\"__proto__\""), "a string VALUE is left as it was");
    }

    @Test
    void theForcedPathWritesTheKeyInItsComputedForm() throws Exception {
        String js = sidecar("forced", true);
        assertTrue(js.contains("[\"__proto__\"]:"), "the key is written as a computed key");
        assertFalse(BARE_KEY.matcher(js).find(), "a bare \"__proto__\": sets the prototype");
        assertTrue(js.contains("\"component\":\"__proto__\""), "a string VALUE is left as it was");
    }
}
