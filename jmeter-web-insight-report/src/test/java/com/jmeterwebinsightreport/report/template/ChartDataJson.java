package com.jmeterwebinsightreport.report.template;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.report.gate.GateEvaluation;

/**
 * The chart data for a test about what the payload carries rather than what a gate decided.
 *
 * <p>Through the serializer's one entry point, as a run that declared nothing builds it: no
 * annotations, no comparison, no server data, and a gate that judged nothing. The serializer used
 * to offer shorter forms that left the gate out altogether — a payload no report ships (GAP-6).
 */
final class ChartDataJson {

    private ChartDataJson() {
    }

    static String of(ReportData data) throws JsonProcessingException {
        return new ReportDataSerializer().serializeChartData(data, null, null, GateEvaluation.none(),
                null, null);
    }
}
