package com.jmeterwebinsightreport.report.listener;

import com.jmeterwebinsightreport.report.generator.ReportConfiguration;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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

        listener.testStarted("");

        assertNull(listener.seenMidBuild, "a reader mid-build saw a half-built configuration: "
                + listener.seenMidBuild);
        Object published = configurationField(listener);
        assertNotNull(published, "the configuration is published once the build is done");
        assertEquals("Probe title", ((ReportConfiguration) published).getReportTitle());
    }
}
