package com.jmeterwebinsightreport.report.listener;

import com.jmeterwebinsightreport.report.listener.rebuild.FieldValue;
import com.jmeterwebinsightreport.report.listener.rebuild.RebuildRequest;
import org.apache.jmeter.reporters.ResultCollector;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The panel's fields round-trip through the element - the Server data field with the rest - and
 * {@code clearGui} leaves nothing for the next listener to inherit. Headless: Swing's lightweight
 * components need no display, and nothing here opens a window. Clicking the button is not automated
 * (a Swing button; checked by hand on each OS before a release, see {@code EXPLORATORY-LOG.md}).
 */
class WebInsightReportListenerGuiTest {

    @BeforeAll
    static void initJMeter() throws IOException {
        System.setProperty("java.awt.headless", "true");
        if (JMeterUtils.getJMeterProperties() == null) {
            File empty = File.createTempFile("wir-test-", ".properties");
            empty.deleteOnExit();
            JMeterUtils.loadJMeterProperties(empty.getAbsolutePath());
        }
    }

    @Test
    void theTwoSecondCheckRunsOnlyWhileThePanelIsInAWindow() {
        // Started in the constructor, it ticked for the JVM's life and nothing could stop it.
        WebInsightReportListenerGui gui = new WebInsightReportListenerGui();
        assertFalse(gui.ticking(), "a panel no window holds runs no timer");
        try {
            gui.addNotify();
        } catch (java.awt.HeadlessException noPeers) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "no component peers in this headless JVM");
        }
        assertTrue(gui.ticking(), "put in a window, it checks every two seconds");
        gui.removeNotify();
        assertFalse(gui.ticking(), "taken out again, it stops");
    }

    @Test
    void everyFieldRoundTripsThroughTheElementAndClearGuiForgetsThemAll() {
        WebInsightReportListenerGui gui = new WebInsightReportListenerGui();

        WebInsightReportListener saved = new WebInsightReportListener();
        saved.setProperty(WebInsightReportListener.REPORT_TITLE, "Checkout");
        saved.setProperty(WebInsightReportListener.REPORT_FILENAME, "checkout-${timestamp}.html");
        saved.setProperty(WebInsightReportListener.REPORT_OUTPUT_DIR, "C:/runs/checkout/report");
        saved.setProperty(WebInsightReportListener.ANNOTATIONS_FILE, "C:/runs/checkout/report-annotations.json");
        saved.setProperty(ResultCollector.FILENAME, "C:/runs/checkout/results.jtl");
        saved.setProperty(WebInsightReportListener.SERVER_DATA_FILE, "${__P(runs.dir)}/server.csv");
        saved.setProperty(WebInsightReportListener.GENERATE_JUNIT_XML, true);

        gui.configure(saved);
        TestElement written = gui.createTestElement();
        gui.modifyTestElement(written);
        for (String key : new String[]{WebInsightReportListener.REPORT_TITLE, WebInsightReportListener.REPORT_FILENAME,
                WebInsightReportListener.REPORT_OUTPUT_DIR, WebInsightReportListener.ANNOTATIONS_FILE,
                ResultCollector.FILENAME, WebInsightReportListener.SERVER_DATA_FILE}) {
            assertEquals(saved.getPropertyAsString(key), written.getPropertyAsString(key), key);
        }
        assertTrue(written.getPropertyAsBoolean(WebInsightReportListener.GENERATE_JUNIT_XML));

        // A trailing space typed into the field is not part of a path.
        WebInsightReportListener padded = new WebInsightReportListener();
        padded.setProperty(WebInsightReportListener.SERVER_DATA_FILE, " C:/runs/server.csv ");
        gui.configure(padded);
        gui.modifyTestElement(padded);
        assertEquals("C:/runs/server.csv", padded.getPropertyAsString(WebInsightReportListener.SERVER_DATA_FILE));

        gui.configure(saved);
        gui.clearGui();
        TestElement fresh = gui.createTestElement();
        assertEquals("", fresh.getPropertyAsString(WebInsightReportListener.SERVER_DATA_FILE),
                "a leaked server data path would rebuild the next test against another test's data");
        assertEquals("", fresh.getPropertyAsString(WebInsightReportListener.ANNOTATIONS_FILE));
        assertEquals("", fresh.getPropertyAsString(ResultCollector.FILENAME));
        assertEquals("JMeter Web Insight Report", fresh.getPropertyAsString(WebInsightReportListener.REPORT_TITLE));
        assertFalse(fresh.getPropertyAsBoolean(WebInsightReportListener.GENERATE_JUNIT_XML));
    }

    @Test
    void theButtonReadsThePanelAsItIsNowAndTheElementItWasGiven() {
        WebInsightReportListenerGui gui = new WebInsightReportListenerGui();
        WebInsightReportListener element = new WebInsightReportListener();
        element.setProperty(WebInsightReportListener.SERVER_DATA_FILE, "");
        gui.configure(element);

        // Nothing saved yet to the element, the panel still empty: refused, and why.
        RebuildRequest request = gui.currentRequest(FieldValue::evaluate);
        assertTrue(request.isRefused());
        assertTrue(request.warnings().stream().anyMatch(w -> w.startsWith("No report from a run in this JMeter session")),
                request.warnings().toString());
        gui.refreshServerDataStatus();   // headless, and it must not throw
    }

    /** Drain the event queue: whatever a returned check posted to it has run. */
    private static void flushEventThread() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
        });
    }

    @Test
    void theStatusLineIsCheckedOffTheEventThreadOneCheckAtATime() throws Exception {
        // The checks are held, as a stat on a share whose host sleeps would hold them.
        List<Runnable> held = new ArrayList<>();
        WebInsightReportListenerGui gui = new WebInsightReportListenerGui(held::add);
        assertEquals(1, held.size(), "the first check was handed to the status thread, not run here");

        // While it hangs, every tick and keystroke asks; none queues a second check behind it.
        gui.refreshServerDataStatus();
        gui.refreshServerDataStatus();
        assertEquals(1, held.size(), "a hung check piled up others behind it");

        // It returns: the panel was asked about meanwhile, so it is checked once more, and only once.
        held.remove(0).run();
        flushEventThread();
        assertEquals(1, held.size());
        held.remove(0).run();
        flushEventThread();
        assertTrue(held.isEmpty(), "nothing was asked meanwhile, so nothing more is checked");
    }

    @Test
    void aCheckOfAPanelThatHasChangedSinceIsDroppedAndThePanelAsItIsNowChecked() throws Exception {
        List<Runnable> held = new ArrayList<>();
        WebInsightReportListenerGui gui = new WebInsightReportListenerGui(held::add);
        held.remove(0).run();
        flushEventThread();

        WebInsightReportListener a = new WebInsightReportListener();
        a.setProperty(WebInsightReportListener.SERVER_DATA_FILE, "C:/runs/a-server.csv");
        WebInsightReportListener b = new WebInsightReportListener();
        b.setProperty(WebInsightReportListener.SERVER_DATA_FILE, "C:/runs/b-server.csv");
        gui.configure(a);
        gui.configure(b);   // shown before a's check came back
        assertEquals(1, held.size());

        held.remove(0).run();   // a's check returns
        flushEventThread();
        assertFalse(gui.statusText().contains("a-server.csv"), "a check of the element no longer shown was shown: "
                + gui.statusText());
        assertEquals(1, held.size(), "the panel as it is now was not checked");
        held.remove(0).run();
        flushEventThread();
        assertTrue(gui.statusText().contains("b-server.csv"), gui.statusText());
    }

    @Test
    void anEvaluationIsTrustedForLessThanATickSoTheLineNamesWhatAClickNowWouldRead() {
        Map<String, WebInsightReportListenerGui.Evaluation> cache = new HashMap<>();
        AtomicInteger runs = new AtomicInteger();
        Function<String, FieldValue> evaluate = text -> FieldValue.resolved("server-" + runs.incrementAndGet() + ".csv");
        String timed = "server-${__time(yyyyMMdd-HHmm)}.csv";

        assertEquals("server-1.csv", WebInsightReportListenerGui.cachedEvaluation(cache, timed, 0, evaluate).value());
        // Within a burst of keystrokes in another field: the function is not run again.
        assertEquals("server-1.csv", WebInsightReportListenerGui.cachedEvaluation(cache, timed, 300, evaluate).value());
        // By the next tick it is: a __time has moved on, and so has the file a click would read.
        assertTrue(WebInsightReportListenerGui.EVALUATION_LIFETIME_MILLIS < WebInsightReportListenerGui.STATUS_TICK_MILLIS);
        assertEquals("server-2.csv", WebInsightReportListenerGui.cachedEvaluation(cache, timed,
                WebInsightReportListenerGui.STATUS_TICK_MILLIS, evaluate).value());

        // Text with nothing to evaluate reads the same however old.
        String plain = "C:/runs/server.csv";
        WebInsightReportListenerGui.cachedEvaluation(cache, plain, 0, evaluate);
        WebInsightReportListenerGui.cachedEvaluation(cache, plain, 60_000, evaluate);
        assertEquals(3, runs.get());
    }
}
