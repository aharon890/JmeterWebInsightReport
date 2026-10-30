package com.jmeterwebinsightreport.report.template;

import com.jmeterwebinsightreport.core.comparison.ComparisonResult;
import com.jmeterwebinsightreport.core.model.OverallMetrics;
import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.model.SamplerStatistics;
import com.jmeterwebinsightreport.report.gate.GateEvaluation;
import com.jmeterwebinsightreport.report.annotation.ReportAnnotations;
import com.jmeterwebinsightreport.core.util.FormatUtil;

import java.util.Comparator;
import java.util.Map;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * Renders the HTML report using Thymeleaf template engine.
 */
public class ThymeleafReportRenderer {

    private static final Logger log = LoggerFactory.getLogger(ThymeleafReportRenderer.class);

    private final TemplateEngine templateEngine;

    public ThymeleafReportRenderer() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");

        this.templateEngine = new TemplateEngine();
        this.templateEngine.setTemplateResolver(resolver);
    }

    /**
     * Render the report template with the given data and inline resources.
     */
    public String render(ReportData reportData, String chartDataJson,
                         String cssContent, String jsContent) {
        return render(reportData, chartDataJson, cssContent, jsContent, "", null, null, null);
    }

    /**
     * Backward-compatible render without inline resources.
     */
    public String render(ReportData reportData, String chartDataJson) {
        return render(reportData, chartDataJson, "", "");
    }

    /**
     * Render with annotations only.
     */
    public String render(ReportData reportData, String chartDataJson,
                         String cssContent, String jsContent,
                         ReportAnnotations annotations) {
        return render(reportData, chartDataJson, cssContent, jsContent, "", annotations, null, null);
    }

    /**
     * Render with annotations and comparison (legacy overload without echartsJs).
     */
    public String render(ReportData reportData, String chartDataJson,
                         String cssContent, String jsContent,
                         ReportAnnotations annotations, ComparisonResult comparison) {
        return render(reportData, chartDataJson, cssContent, jsContent, "", annotations, comparison, null);
    }

    /**
     * Full render with all options including inlined ECharts and external data mode.
     */
    public String render(ReportData reportData, String chartDataJson,
                         String cssContent, String jsContent,
                         String echartsJs,
                         ReportAnnotations annotations, ComparisonResult comparison,
                         String externalDataFilename) {
        return render(reportData, chartDataJson, cssContent, jsContent, echartsJs, annotations,
                comparison, externalDataFilename, null);
    }

    /**
     * @param evaluation the gate, already judged. Null makes this renderer judge it itself,
     *        which is right for a caller that has no other use for the verdict and wrong for
     *        the generator — which needs the same verdict for the CI/CD JSON and would
     *        otherwise re-run the whole gate and log every finding a second time.
     */
    public String render(ReportData reportData, String chartDataJson,
                         String cssContent, String jsContent,
                         String echartsJs,
                         ReportAnnotations annotations, ComparisonResult comparison,
                         String externalDataFilename, GateEvaluation evaluation) {
        return render(reportData, chartDataJson, cssContent, jsContent, echartsJs, annotations, comparison,
                externalDataFilename, evaluation, null);
    }

    /**
     * @param server the server gates, or null. Decides {@code assertsDeclared} together with the
     *        client gate: a file that declares only server KPIs has declared asserts (D8), and
     *        the header must not say "NO ASSERTS DECLARED" over a server gate that FAILED. The
     *        server chip itself is M5's.
     */
    public String render(ReportData reportData, String chartDataJson,
                         String cssContent, String jsContent,
                         String echartsJs,
                         ReportAnnotations annotations, ComparisonResult comparison,
                         String externalDataFilename, GateEvaluation evaluation,
                         com.jmeterwebinsightreport.report.gate.ServerGateEvaluation server) {
        Context context = buildFullContext(reportData, chartDataJson, cssContent, jsContent,
                echartsJs, annotations, externalDataFilename, evaluation);
        if (server != null && server.declared()) {
            context.setVariable("assertsDeclared", true);
        }
        // Whether the SERVER side ran, which the badge needs to avoid saying something false. With
        // assertsDeclared alone, a run declaring only server KPIs took the "declared but not
        // evaluated" arm and rendered ASSERTS NOT EVALUATED — over a .json saying
        // assertsEvaluated:true and a server gate that FAILED. The badge now withholds the claim
        // instead of contradicting it; the verdict itself arrives with M5's server chip (D17).
        context.setVariable("serverEvaluated", server != null && server.evaluated());
        // The header chip (D17). Null only when no server assert is declared, and the template
        // renders nothing at all — a run with no server block must look exactly as it did before
        // M5. Declared and judged against nothing, it is Server · NOT COLLECTED or REFUSED (SF47).
        context.setVariable("serverChip", server == null ? null : server.headerChip());

        if (comparison != null) {
            context.setVariable("comparison", comparison);
            context.setVariable("hasComparison", true);
            context.setVariable("comparisonDiffs", comparison.getSamplerDiffs());
            context.setVariable("hasRegressions", comparison.hasRegressions());
        }

        log.debug("Rendering report template");
        return templateEngine.process("report", context);
    }

    private Context buildFullContext(ReportData reportData, String chartDataJson,
                                     String cssContent, String jsContent,
                                     String echartsJs,
                                     ReportAnnotations annotations,
                                     String externalDataFilename,
                                     GateEvaluation preEvaluated) {
        boolean externalDataMode = externalDataFilename != null;
        Context context = new Context();
        context.setVariable("report", reportData);
        context.setVariable("metadata", reportData.getMetadata());
        context.setVariable("samplerStats", reportData.getSamplerStatistics());
        context.setVariable("timeSeries", reportData.getTimeSeries());
        context.setVariable("errorSummaries", reportData.getErrorSummaries());
        context.setVariable("errorRecords", reportData.getErrorRecords());
        context.setVariable("inlineCss", cssContent);
        context.setVariable("inlineJs", jsContent);
        context.setVariable("inlineEchartsJs", echartsJs != null ? echartsJs : "");
        context.setVariable("externalDataMode", externalDataMode);
        // The template hardcoded "web-insight-data.js" in its <script src>, which is half of why
        // two reports in one directory could share a payload. It is a variable now.
        context.setVariable("externalDataFilename", externalDataFilename);
        // The same id the payload carries, derived from the same ReportData, so the page can tell
        // whether the payload it loaded actually belongs to it.
        context.setVariable("runId",
                com.jmeterwebinsightreport.core.util.RunIdentity.of(reportData));
        // The same name the payload carries as `runTestName`, so a mismatch can be reported as
        // "expected X, found Y" instead of two opaque hex strings. Passed as its own variable
        // rather than read off `metadata` in the template because the identity check runs inside
        // an inlined <script>, where a null would become the literal `null`.
        context.setVariable("runTestName", reportData.getMetadata() != null
                && reportData.getMetadata().getTestName() != null
                ? reportData.getMetadata().getTestName() : "");
        context.setVariable("fmt", new FormatUtilHelper());

        // Chart data: inline or external
        if (externalDataMode) {
            context.setVariable("chartData", "{}");
        } else {
            context.setVariable("chartData", chartDataJson);
        }

        // Annotations
        if (annotations != null) {
            context.setVariable("testNotes", annotations.getTestNotes());
            context.setVariable("timelineMarkers", annotations.getTimelineMarkers());
            context.setVariable("samplerNotes", annotations.getSamplerNotes());

            // The same evaluation the CI/CD JSON and the JUnit XML get. Three call sites
            // used to construct the evaluator independently; nothing forced them to agree, and
            // "the report says PASS, the pipeline says FAIL" is the least debuggable bug a
            // reporting tool can produce.
            // The fallback is a test affordance. It is no longer a hazard: an evaluation is a
            // pure function of the run and the annotations, so a second one reaches the same
            // verdict as the first. It stopped being a hazard when tag selection was deleted —
            // before that, re-evaluating here judged a DIFFERENT set of rules than the pipeline
            // artifacts did, and the HTML could say PASS while the JUnit XML said FAIL.
            GateEvaluation evaluation = preEvaluated != null
                    ? preEvaluated
                    : GateEvaluation.of(reportData, annotations);
            // Declared, whatever it decided. The badge needs all three states: nothing
            // declared, declared-but-nothing-judged, and a real verdict. Without this a run
            // whose every rule went unmatched rendered "NO ASSERTS DECLARED", which is false —
            // rules WERE declared, they just had nothing to judge.
            context.setVariable("assertsDeclared", evaluation.hasGate());
            if (evaluation.hasGate()) {
                context.setVariable("assertResults", evaluation.verdicts());
                context.setVariable("overallAssertStatus", evaluation.overallStatus());
                // Worst verdict per metric, so a summary card's mini-badge comes from the gate
                // instead of re-deriving one in a template expression.
                context.setVariable("metricVerdicts", evaluation.metricVerdicts());
            }
        }

        // Computed summary values. All summary tiles use the SAME top-level population (transaction
        // children excluded) so the headline counts and the derived metrics are consistent (B7).
        List<SamplerStatistics> stats = reportData.getSamplerStatistics();
        if (stats != null && !stats.isEmpty()) {
            // One definition of "the run as a whole", shared with the CI/CD JSON, the JUnit XML
            // and the assertion engine. These numbers used to be derived here and separately
            // wherever else they were needed, which was fine while they were only displayed and
            // is not fine now that a rule can gate a build on scope:"overall" — a verdict
            // computed from one definition and rendered beside another is indefensible.
            OverallMetrics overall = OverallMetrics.of(reportData);

            context.setVariable("avgP95", overall.getP95());
            context.setVariable("avgP99", overall.getP99());
            context.setVariable("totalThroughput", overall.getThroughput());
            // B7: header "Total Samples"/"Error Rate" cards use the same top-level totals as the tiles.
            context.setVariable("topLevelTotalSamples", overall.getTotalSamples());
            context.setVariable("topLevelTotalErrors", overall.getTotalErrors());
            List<SamplerStatistics> top5Slowest = stats.stream()
                    .filter(s -> s.getParentSamplerName() == null) // exclude transaction children
                    .sorted(Comparator.comparingDouble(SamplerStatistics::getPercentile95).reversed())
                    .limit(5)
                    .collect(Collectors.toList());
            context.setVariable("top5Slowest", top5Slowest);
            // Which samples the totals count, said where they are read (B5): JMeter's three tools
            // count a transaction run three ways, and this report counts it as the listener does.
            context.setVariable("countsTransactionsOnce", stats.stream()
                    .anyMatch(SamplerStatistics::isTransactionController));

            if (overall.getTotalSamples() > 0) {
                context.setVariable("overallApdex", overall.getApdex());
            }
        }

        return context;
    }

    /**
     * Helper class to expose FormatUtil methods to Thymeleaf templates.
     */
    public static class FormatUtilHelper {
        public String duration(long millis) {
            return FormatUtil.formatDuration(millis);
        }

        public String number(double value) {
            return FormatUtil.formatNumber(value);
        }

        /** Response times in whole milliseconds, JMeter-style (no decimals, no grouping). */
        public String millis(double value) {
            return FormatUtil.formatMillis(value);
        }

        public String percentage(double value) {
            return FormatUtil.formatPercentage(value);
        }

        public String bytes(long value) {
            return FormatUtil.formatBytes(value);
        }
    }
}
