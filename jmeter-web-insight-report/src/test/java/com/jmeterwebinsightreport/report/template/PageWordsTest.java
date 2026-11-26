package com.jmeterwebinsightreport.report.template;

import com.jmeterwebinsightreport.core.assertion.SamplerWords;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The page's words per tool (spec 001, decision 7, contracts/page-words.md, tasks T056 and T058): one table decides
 * every label, a JMeter run keeps today's words, and a k6 run uses k6's.
 */
class PageWordsTest {

    @Test
    void everyKeyHasEveryToolsWord() {
        for (String column : PageWords.columns()) {
            Map<String, String> words = PageWords.column(column);
            assertEquals(PageWords.keys(), words.keySet(), column + ": every key, and no other");
            words.forEach((k, v) -> assertFalse(v == null || v.trim().isEmpty(), column + "." + k + " is empty"));
        }
    }

    @Test
    void aJMeterRunKeepsTodaysWords() {
        Map<String, String> w = PageWords.forTool(null);
        assertEquals(w, PageWords.forTool("jmeter"), "a JMeter run's report names no tool, and is jmeter's");
        assertEquals("JMeter", w.get("tool"));
        assertEquals("Samplers", w.get("Samplers"));
        assertEquals("Sampler", w.get("Sampler"));
        assertEquals("Thread", w.get("Thread"));
        assertEquals("Max Threads", w.get("maxThreads"));
        assertEquals("Max Active Threads", w.get("maxActiveThreads"));
        assertEquals("Transaction Controller", w.get("transactionController"));
        assertEquals("Show transaction details", w.get("showTransactionDetails"));
    }

    @Test
    void aK6RunUsesK6sWordsAndNoJMeterWordOutsideTheExceptions() {
        Map<String, String> w = PageWords.forTool("k6");
        assertEquals("k6", w.get("tool"));
        assertEquals("Requests", w.get("Samplers"));
        assertEquals("VU", w.get("Thread"));
        assertEquals("Max VUs", w.get("maxThreads"));
        assertEquals("Group", w.get("transactionController"));
        assertEquals("Show group details", w.get("showTransactionDetails"));
        assertEquals("not measured", w.get("notMeasured"));
        Pattern jmeterWord = Pattern.compile("(?i)sampler|\\bthreads?\\b|transaction|JMeter");
        for (Map.Entry<String, String> e : w.entrySet()) {
            // exception 2, the one sentence about JMeter's numbers; exception 1, the product's (and its JAR's) name
            String rest = e.getValue().replace("Numbers follow JMeter's Aggregate Report rules.", "")
                    .replace("jmeter-web-insight-report", "");
            assertFalse(jmeterWord.matcher(rest).find(), "k6." + e.getKey() + " says a JMeter word: " + e.getValue());
        }
    }

    @Test
    void anyOtherToolGetsTheNeutralWordsWithItsName() {
        Map<String, String> w = PageWords.forTool("demo");
        assertEquals("demo", w.get("tool"));
        assertEquals("Requests", w.get("Samplers"));
        assertTrue(w.get("bytesNotMeasured").startsWith("demo "), w.get("bytesNotMeasured"));
        w.values().forEach(v -> assertFalse(v.contains("{tool}"), v));
    }

    /**
     * The judge's word for a sampler is this table's (contracts/page-words.md, done 2026-11-26): core's default is the
     * jmeter column's, so a JMeter run's sentences cannot drift from the page, and every other tool reads its own.
     */
    @Test
    void theJudgesWordForASamplerIsTheTables() {
        SamplerWords jmeter = PageWords.samplerWords((String) null);
        assertEquals(SamplerWords.JMETER.one(), PageWords.column("jmeter").get(SamplerWords.ONE_KEY));
        assertEquals(SamplerWords.JMETER.many(), PageWords.column("jmeter").get(SamplerWords.MANY_KEY));
        assertEquals("sampler/samplers", jmeter.toString());
        assertEquals("request/requests", PageWords.samplerWords("k6").toString());
        assertEquals("request/requests", PageWords.samplerWords("gatling").toString());
        assertEquals("sampler/samplers", PageWords.samplerWords((com.jmeterwebinsightreport.core.model.ReportData) null)
                .toString(), "no run in hand is JMeter's");
    }

    @Test
    void reportJsAndTheTemplateUseOnlyWordsTheTableHas() throws IOException {
        Set<String> used = new TreeSet<>();
        collect(read("/static/js/report.js"), Pattern.compile("\\bW\\('([A-Za-z]+)'\\)"), used);
        int fromJs = used.size();
        collect(read("/templates/report.html"), Pattern.compile("words\\['([A-Za-z]+)'\\]"), used);
        assertTrue(fromJs > 10, "report.js reads the table: " + fromJs);
        assertTrue(used.size() > fromJs, "and so does the template");
        Set<String> missing = new TreeSet<>(used);
        missing.removeAll(PageWords.keys());
        assertEquals(Set.of(), missing, "words the page asks for and the table does not have");
    }

    @Test
    void theWordsReachThePageAsJson() {
        String json = PageWords.json(PageWords.forTool("k6"));
        assertTrue(json.startsWith("{") && json.contains("\"Samplers\":\"Requests\""), json);
    }

    private static void collect(String text, Pattern p, Set<String> into) {
        Matcher m = p.matcher(text);
        while (m.find()) {
            into.add(m.group(1));
        }
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = PageWordsTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
