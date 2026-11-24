package com.jmeterwebinsightreport.core.rundata;

import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.model.TimeSeriesBucket;
import com.jmeterwebinsightreport.core.stats.LiveStatisticsEngine;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one feed (spec 001, research R3). The JMeter paths are held to their pre-change report data by
 * {@code PreChangeBaselineTest}; this pins what only another tool's samples can show.
 */
class SampleFeedTest {

    private static final long T = 1_700_000_000_000L;

    /** A k6 group pass: a transaction with no JMeter message, holding a failed request. */
    private static ReportData groupPassWithAFailedRequest(RunSample.Kind passKind) {
        LiveStatisticsEngine engine = new LiveStatisticsEngine();
        RunSample pass = new RunSample("Complete Checkout Flow", T, 300, false);
        pass.kind = passKind;
        pass.code = "";
        SampleFeed.feed(engine, pass, null);
        RunSample login = new RunSample("Login", T, 100, true);
        login.code = "200";
        login.topLevel = false;
        SampleFeed.feed(engine, login, "Complete Checkout Flow");
        RunSample pay = new RunSample("Pay", T + 100, 200, false);
        pay.code = "500";
        pay.message = "Internal Server Error";
        pay.topLevel = false;
        SampleFeed.feed(engine, pay, "Complete Checkout Flow");
        return engine.buildReportData("k6");
    }

    @Test
    void aTransactionIsKnownByItsKindNotByAJMeterMessage() {
        // Finding I2: the engine knew a transaction row only by "Number of samples in transaction". A k6
        // group pass has no message, so its failure was a request's in the Errors tab, and its empty code
        // a series in Codes per Second (EMPTY_RES_CODE) that the JMeter twin never draws.
        ReportData data = groupPassWithAFailedRequest(RunSample.Kind.TRANSACTION);

        assertEquals(2, data.getRequestSampleCount(), "Login and Pay; the pass is a transaction row");
        assertEquals(1, data.getErrorRecords().size(), "the Errors tab lists Pay, not the pass");
        assertEquals("Pay", data.getErrorRecords().get(0).getSamplerName());
        Map<String, Long> codes = new HashMap<>();
        for (TimeSeriesBucket b : data.getTimeSeries()) {
            b.getResponseCodes().forEach((k, v) -> codes.merge(k, v, Long::sum));
        }
        assertEquals(Map.of("200", 1L, "500", 1L), codes, "the pass draws no code of its own");
        assertEquals(1, data.getTopLevelSampleCount(), "the whole run counts the pass once");
        assertEquals(1, data.getTopLevelErrorCount());
    }

    @Test
    void aCutOffPassIsARequestThatStillNestsItsRequests() {
        // JMeter writes a controller the test end cut off without its message: it stays a request, as the
        // listener counts it, and still holds its requests (TransactionRows). A k6 pass cut off the same way is fed
        // the same way.
        ReportData data = groupPassWithAFailedRequest(RunSample.Kind.REQUEST);

        assertEquals(3, data.getRequestSampleCount());
        assertEquals("Complete Checkout Flow",
                data.getSamplerStatistics().stream().filter(s -> s.getSamplerName().equals("Pay")).findFirst()
                        .orElseThrow().getParentSamplerName());
    }

    @Test
    void aSampleReachesTheEngineWithItsElapsedTimeAndSuccessKnown() {
        RunSample unresolved = new RunSample();
        unresolved.name = "Complete Checkout Flow";
        unresolved.kind = RunSample.Kind.TRANSACTION;
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SampleFeed.feed(new LiveStatisticsEngine(), unresolved, null));
        assertTrue(e.getMessage().contains("Complete Checkout Flow"), e.getMessage());
    }
}
