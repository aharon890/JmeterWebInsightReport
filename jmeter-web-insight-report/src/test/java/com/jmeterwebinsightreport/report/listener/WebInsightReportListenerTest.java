package com.jmeterwebinsightreport.report.listener;

import com.jmeterwebinsightreport.report.generator.NonZeroExit;
import com.jmeterwebinsightreport.report.generator.ReportConfiguration;
import com.jmeterwebinsightreport.report.listener.rebuild.LastRun;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The listener publishes its configuration once, complete (RPT-13).
 *
 * <p>{@code configuration} is volatile and read without the lock, as {@code statsEngine} is. The
 * engine was built in a local and published last; the configuration was assigned first and then
 * populated, so a reader between the two saw a default title, no output directory and no
 * annotations file. Race-free to observe: the build reads the GUI fields through
 * {@code getPropertyAsString}, so a probe there sees the field as any other thread would at that
 * moment.
 */
class WebInsightReportListenerTest {

    /** Reads the field the moment the build asks for the GUI title — mid-build. */
    private static final class Probe extends WebInsightReportListener {
        private static final long serialVersionUID = 1L;
        transient Object seenMidBuild = "not asked";

        @Override
        public String getPropertyAsString(String key) {
            if (REPORT_TITLE.equals(key)) {
                seenMidBuild = configurationField(this);
            }
            return super.getPropertyAsString(key);
        }
    }

    private static Object configurationField(WebInsightReportListener listener) {
        try {
            Field f = WebInsightReportListener.class.getDeclaredField("configuration");
            f.setAccessible(true);
            return f.get(listener);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("the listener has no configuration field", e);
        }
    }

    @Test
    void theConfigurationIsNotVisibleUntilItIsComplete() {
        Probe listener = new Probe();
        listener.setProperty(WebInsightReportListener.REPORT_TITLE, "Probe title");
        // Ended below, so the run leaves the in-flight count as it found it: the report goes here.
        listener.setProperty(WebInsightReportListener.REPORT_OUTPUT_DIR, work.toString());

        listener.testStarted("");

        assertNull(listener.seenMidBuild, "a reader mid-build saw a half-built configuration: "
                + listener.seenMidBuild);
        Object published = configurationField(listener);
        assertNotNull(published, "the configuration is published once the build is done");
        assertEquals("Probe title", ((ReportConfiguration) published).getReportTitle());
        endQuietly(listener);
    }

    // ---- the panel's Rebuild button: what the run leaves for it (R1, R2) ----

    @TempDir
    Path work;

    @BeforeAll
    static void initJMeterProperties() throws IOException {
        if (JMeterUtils.getJMeterProperties() == null) {
            File empty = File.createTempFile("wir-test-", ".properties");
            empty.deleteOnExit();
            JMeterUtils.loadJMeterProperties(empty.getAbsolutePath());
        }
    }

    /**
     * A run's testEnded with failonerror off: a report this test cannot make complete must not arrange
     * a {@code halt(1)} on the test JVM.
     */
    private static void endQuietly(WebInsightReportListener listener) {
        String before = JMeterUtils.getProperty(NonZeroExit.FAIL_ON_ERROR);
        JMeterUtils.setProperty(NonZeroExit.FAIL_ON_ERROR, "false");
        try {
            listener.testEnded("");
        } finally {
            if (before == null) {
                JMeterUtils.getJMeterProperties().remove(NonZeroExit.FAIL_ON_ERROR);
            } else {
                JMeterUtils.setProperty(NonZeroExit.FAIL_ON_ERROR, before);
            }
        }
    }

    private static void sample(WebInsightReportListener listener) {
        SampleResult result = new SampleResult();
        result.setSampleLabel("GET /checkout");
        result.setStampAndTime(System.currentTimeMillis() - 120, 120);
        result.setSuccessful(true);
        listener.sampleOccurred(new SampleEvent(result, "Thread Group"));
    }

    @SuppressWarnings("unchecked")
    private static WebInsightReportListener sourceOf(WebInsightReportListener listener) {
        try {
            Field f = WebInsightReportListener.class.getDeclaredField("source");
            f.setAccessible(true);
            WeakReference<WebInsightReportListener> ref = (WeakReference<WebInsightReportListener>) f.get(listener);
            return ref == null ? null : ref.get();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("the listener has no source field", e);
        }
    }

    @Test
    void aCloneKnowsItsDirectSourceAndNeverTheSourcesSource() {
        WebInsightReportListener original = new WebInsightReportListener();
        WebInsightReportListener pasted = (WebInsightReportListener) original.clone();
        WebInsightReportListener runClone = (WebInsightReportListener) pasted.clone();

        assertNull(sourceOf(original), "an element loaded or created is nobody's clone");
        assertSame(original, sourceOf(pasted));
        assertSame(pasted, sourceOf(runClone), "the pasted copy's run belongs to the copy, not to what it was copied from");
    }

    @Test
    void theRunsLastRunLandsOnTheElementThePanelShowsAndNowhereElse() {
        WebInsightReportListener original = new WebInsightReportListener();
        WebInsightReportListener shown = (WebInsightReportListener) original.clone();
        shown.setProperty(WebInsightReportListener.REPORT_TITLE, "Checkout");
        shown.setProperty(WebInsightReportListener.REPORT_OUTPUT_DIR, work.toString());
        shown.setProperty(WebInsightReportListener.SERVER_DATA_FILE, "C:/runs/server.csv");
        WebInsightReportListener runClone = (WebInsightReportListener) shown.clone();

        int inFlight = WebInsightReportListener.runsInFlight();
        runClone.testStarted("");
        assertEquals(inFlight + 1, WebInsightReportListener.runsInFlight(), "a run in flight while its report is to come");
        sample(runClone);
        endQuietly(runClone);
        assertEquals(inFlight, WebInsightReportListener.runsInFlight(), "out of flight once the report is written");

        LastRun last = shown.getLastRun();
        assertNotNull(last, "the run's LastRun did not reach the element the panel shows");
        assertNull(runClone.getLastRun(), "kept on the clone, it is in nothing the panel can read");
        assertNull(original.getLastRun(), "chained to the copy's source");

        assertEquals("Checkout", last.title());
        assertEquals("web-insight-report.html", last.htmlFilename());
        assertEquals(work.toFile().getAbsoluteFile(), last.outputDirectory());
        assertTrue(new File(last.outputDirectory(), last.htmlFilename()).isFile());
        assertNull(last.jtl(), "no Results file was set: the run wrote no JTL");
        assertEquals(16, last.runId().length());
        assertEquals("C:/runs/server.csv", last.serverDataRaw());
        assertEquals("C:/runs/server.csv", last.serverDataResolved());
        assertTrue(last.endedAt() > 0);
    }

    @Test
    void aRunWhoseReportFailsLeavesThePanelNoRunRatherThanTheOneBefore() throws IOException {
        WebInsightReportListener shown = new WebInsightReportListener();
        shown.setProperty(WebInsightReportListener.REPORT_OUTPUT_DIR, work.toString());

        // Run 1 writes its report: the panel can rebuild it.
        WebInsightReportListener first = (WebInsightReportListener) shown.clone();
        first.testStarted("");
        sample(first);
        endQuietly(first);
        assertNotNull(shown.getLastRun(), "run 1 wrote its report");

        // Run 2's output directory is a file, so its report cannot be written.
        File notADirectory = work.resolve("not-a-directory").toFile();
        assertTrue(notADirectory.createNewFile());
        shown.setProperty(WebInsightReportListener.REPORT_OUTPUT_DIR, notADirectory.getPath());
        WebInsightReportListener second = (WebInsightReportListener) shown.clone();
        second.testStarted("");
        assertNull(shown.getLastRun(), "run 2 started: run 1 is no longer the last run");
        sample(second);
        endQuietly(second);
        assertNull(shown.getLastRun(),
                "a failed run left run 1's LastRun, and Rebuild would rebuild run 1 without a word");
    }

    @Test
    void aRunThatWasNotClonedKeepsItsLastRunItself() {
        // jmeter -n: no panel, no clone; the element itself ran.
        WebInsightReportListener listener = new WebInsightReportListener();
        listener.setProperty(WebInsightReportListener.REPORT_OUTPUT_DIR, work.toString());
        listener.testStarted("");
        sample(listener);
        endQuietly(listener);
        assertNotNull(listener.getLastRun());
    }

    /** A results filename that is a function: a new value on every read, as {@code ${__time(HHmmssSSS)}} would be. */
    private static final class TickingFilename extends WebInsightReportListener {
        private static final long serialVersionUID = 1L;
        transient Path dir;
        transient int reads;

        @Override
        public String getPropertyAsString(String key) {
            if (FILENAME.equals(key)) {
                return dir.resolve("results-" + (++reads) + ".jtl").toString();
            }
            return super.getPropertyAsString(key);
        }
    }

    @Test
    void theResultsFilenameIsReadOnceWhileRunningSoTheJtlTheNextLineAndLastRunAgree() {
        TickingFilename listener = new TickingFilename();
        listener.dir = work;
        listener.setProperty(WebInsightReportListener.REPORT_OUTPUT_DIR, work.toString());

        // Not running (the panel's element): the raw text, read as asked.
        assertNotEquals(listener.getFilename(), listener.getFilename());

        listener.setRunningVersion(true);
        String stale = listener.getFilename();
        listener.testStarted("");   // forgets the name from before the run, then opens the JTL through it
        String thisRun = listener.getFilename();
        assertNotEquals(stale, thisRun, "the memo was not cleared when the run started");
        assertEquals(thisRun, listener.getFilename());
        assertTrue(new File(thisRun).isFile(), "ResultCollector opened the file under the memoised name");
        sample(listener);
        endQuietly(listener);

        LastRun last = listener.getLastRun();
        assertNotNull(last);
        assertEquals(new File(thisRun).getAbsoluteFile(), last.jtl(), "the JTL the run wrote, not a fresh evaluation");
    }

    @Test
    void aTransactionThatExcludesItsTimersIsWindowedToItsRealEnd() throws Exception {
        // B7: TransactionSampler with "include timers" off sets idle = end - start - children's time
        // and then the end, so getTime() is 200 ms and getEndTime() a second after the start. Two
        // back to back ran 2 s; the row's window stopped at start + elapsed (P11: 0.86/s on the row
        // against 0.76/s on the tile for the same 50 transactions).
        WebInsightReportListener listener = new WebInsightReportListener();
        com.jmeterwebinsightreport.core.stats.LiveStatisticsEngine engine =
                new com.jmeterwebinsightreport.core.stats.LiveStatisticsEngine();
        Field f = WebInsightReportListener.class.getDeclaredField("statsEngine");
        f.setAccessible(true);
        f.set(listener, engine);

        for (long start : new long[]{1_700_000_000_000L, 1_700_000_001_000L}) {
            SampleResult tx = SampleResult.createTestSample(start, start + 1000);
            tx.setIdleTime(800);
            tx.setEndTime(start + 1000);
            tx.setSampleLabel("Checkout");
            tx.setSuccessful(true);
            assertEquals(200, tx.getTime());
            listener.sampleOccurred(new SampleEvent(tx, "Thread Group"));
        }

        com.jmeterwebinsightreport.core.model.ReportData data = engine.buildReportData("t");
        assertEquals(1.0, data.getSamplerStatistics().get(0).getThroughput(), 1e-9);
        assertEquals(2000, data.getMetadata().getDurationMillis());
    }

    @Test
    void aTransactionsOwnResultIsNoRequestOfTheErrorsTab() throws Exception {
        // E3: a parent sample with a failed child. JMeter's HTML report lists the child's failure and
        // leaves the transaction's own result out of its Errors table and its "% of all samples".
        WebInsightReportListener listener = new WebInsightReportListener();
        com.jmeterwebinsightreport.core.stats.LiveStatisticsEngine engine =
                new com.jmeterwebinsightreport.core.stats.LiveStatisticsEngine();
        Field f = WebInsightReportListener.class.getDeclaredField("statsEngine");
        f.setAccessible(true);
        f.set(listener, engine);

        SampleResult pay = SampleResult.createTestSample(1_700_000_000_000L, 1_700_000_000_200L);
        pay.setSampleLabel("Pay");
        pay.setSuccessful(false);
        pay.setResponseCode("500");
        pay.setResponseMessage("Internal Server Error");
        SampleResult tx = SampleResult.createTestSample(1_700_000_000_000L, 1_700_000_000_200L);
        tx.setSampleLabel("Checkout");
        tx.setSuccessful(false);
        tx.setResponseCode("500");
        tx.setResponseMessage("Number of samples in transaction : 1, number of failing samples : 1");
        tx.addSubResult(pay, false);   // as TransactionSampler adds it: the label kept
        listener.sampleOccurred(new SampleEvent(tx, "Thread Group"));

        com.jmeterwebinsightreport.core.model.ReportData data = engine.buildReportData("t");
        assertEquals(1, data.getRequestSampleCount());
        // T5: the response codes count the request, not the transaction
        assertEquals(java.util.Map.of("500", 1L), data.getTimeSeries().stream()
                .filter(b -> !b.getResponseCodes().isEmpty()).findFirst().get().getResponseCodes());
        assertEquals(1, data.requestErrorSummaries().size());
        assertEquals("Pay", data.requestErrorSummaries().get(0).getSamplerName());
        assertEquals(1, data.getErrorRecords().size());
    }

    @Test
    void aFailedAssertionsMessageIsTheTypeOfErrorOfA200() throws Exception {
        // E1: what CSVSaveService writes into failureMessage, read off the result itself
        WebInsightReportListener listener = new WebInsightReportListener();
        com.jmeterwebinsightreport.core.stats.LiveStatisticsEngine engine =
                new com.jmeterwebinsightreport.core.stats.LiveStatisticsEngine();
        Field f = WebInsightReportListener.class.getDeclaredField("statsEngine");
        f.setAccessible(true);
        f.set(listener, engine);

        SampleResult search = SampleResult.createTestSample(1_700_000_000_000L, 1_700_000_000_100L);
        search.setSampleLabel("Search");
        search.setResponseCode("200");
        search.setResponseMessage("OK");
        search.setSuccessful(false);
        org.apache.jmeter.assertions.AssertionResult failed =
                new org.apache.jmeter.assertions.AssertionResult("Search Assert");
        failed.setFailure(true);
        failed.setFailureMessage("Test failed: text expected to contain /order-id/");
        search.addAssertionResult(failed);
        listener.sampleOccurred(new SampleEvent(search, "Thread Group"));

        com.jmeterwebinsightreport.core.model.ReportData data = engine.buildReportData("t");
        assertEquals("Test failed: text expected to contain /order-id/",
                data.getErrorSummaries().get(0).getErrorType());
    }

    @Test
    void aStatisticalResultCountsItsSamplesAndItsErrors() throws Exception {
        // B6: a remote engine in Statistical mode sends one StatisticalSampleResult for many samples.
        WebInsightReportListener listener = new WebInsightReportListener();
        com.jmeterwebinsightreport.core.stats.LiveStatisticsEngine engine =
                new com.jmeterwebinsightreport.core.stats.LiveStatisticsEngine();
        Field f = WebInsightReportListener.class.getDeclaredField("statsEngine");
        f.setAccessible(true);
        f.set(listener, engine);

        org.apache.jmeter.samplers.StatisticalSampleResult batch = null;
        for (int i = 0; i < 4; i++) {
            SampleResult one = SampleResult.createTestSample(1_700_000_000_000L + i * 100, 1_700_000_000_000L + i * 100 + 50);
            one.setSampleLabel("GET /a");
            one.setSuccessful(i != 2);
            if (batch == null) {
                batch = new org.apache.jmeter.samplers.StatisticalSampleResult(one);   // counts nothing yet
            }
            batch.add(one);
        }
        assertEquals(4, batch.getSampleCount());
        listener.sampleOccurred(new SampleEvent(batch, "Thread Group"));

        com.jmeterwebinsightreport.core.model.SamplerStatistics s = engine.buildReportData("t").getSamplerStatistics().get(0);
        assertEquals(4, s.getSampleCount());
        assertEquals(batch.getErrorCount(), s.getErrorCount());
        assertEquals(batch.getTime() / 4, s.getMaxResponseTime(), 0.0);
    }

    @Test
    void aSampleIsRecordedAtItsStartWhateverItsTimeStampSays() throws Exception {
        // B3: with sampleresult.timestamp.start=false a SampleResult's time stamp is its END. The report
        // placed and windowed every sample by it (S46: 3 m 10 s where JMeter's Aggregate Report says 3 m 4 s).
        WebInsightReportListener listener = new WebInsightReportListener();
        com.jmeterwebinsightreport.core.stats.LiveStatisticsEngine engine =
                new com.jmeterwebinsightreport.core.stats.LiveStatisticsEngine();
        Field f = WebInsightReportListener.class.getDeclaredField("statsEngine");
        f.setAccessible(true);
        f.set(listener, engine);

        SampleResult result = SampleResult.createTestSample(1_700_000_000_000L, 1_700_000_001_000L);
        result.setSampleLabel("GET /checkout");
        result.setSuccessful(false);
        result.setTimeStamp(1_700_000_001_000L);   // stamped at its end
        listener.sampleOccurred(new SampleEvent(result, "Thread Group"));

        com.jmeterwebinsightreport.core.model.ReportData data = engine.buildReportData("t");
        assertEquals(1_700_000_000_000L, data.getMetadata().getStartTime().toEpochMilli());
        assertEquals(1000, data.getMetadata().getDurationMillis());
        assertEquals(1_700_000_000_000L, data.getTimeSeries().get(0).getTimestamp().toEpochMilli());
        assertEquals(1_700_000_000_000L, data.getErrorRecords().get(0).getTimestamp());
    }
}
