import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.apache.jmeter.reporters.ResultCollector;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.save.CSVSaveService;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.visualizers.SamplingStatCalculator;
import org.apache.jmeter.visualizers.Visualizer;

/**
 * JMeter's Aggregate Report, computed by JMeter's own classes from a results file: R07's reference.
 *
 * <p>This is what the Aggregate Report listener shows when a results file is opened in it ("Browse..."):
 * {@code CSVSaveService.processSamples} turns every row into a {@code SampleResult} and hands it to a
 * {@code Visualizer}; {@code StatVisualizer.add} keeps one {@code SamplingStatCalculator} per label plus
 * one for the TOTAL row, and never looks inside a sample's sub-results. The figures come out of the
 * getters the table reads ({@code StatGraphVisualizer.createObjectTableModel}).
 *
 * <p>Every row counts. Which rows a file holds is the writer's business, and R07 reads two files:
 * <ul>
 *   <li>a run's {@code results.jtl}, written with sub-results: a transaction's requests are rows of their
 *       own, so each label's row counts every sample of that name, as the report's Samplers row does;</li>
 *   <li>the file the plan's own Simple Data Writer wrote with "Save sub-results" off
 *       ({@code in-test-plan.jtl}): one row per sample a listener in the test plan received, which is
 *       the population of the report's whole-run figures.</li>
 * </ul>
 * There is no "top-level" mode. One was tried (campaign 2026-10): it rebuilt a listener's population from
 * a file with sub-results by the "Number of samples in transaction" message, and JMeter writes a
 * transaction cut off at the test end without that message, so it counted that transaction's requests
 * too (E02: 7,264 where a listener in the plan receives 7,224). The plan's own writer has no such gap.
 *
 * <p>usage: {@code java AggregateReportReferee <jmeterHome> <results.jtl> <out.csv>}. Prints one summary
 * line: rows read, transaction rows, labels, TOTAL samples and errors.
 */
public class AggregateReportReferee {

    private static final String TRANSACTION_MARKER = "Number of samples in transaction : ";

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("usage: AggregateReportReferee <jmeterHome> <results.jtl> <out.csv>");
            System.exit(2);
        }
        String home = args[0];
        String jtl = args[1];
        String out = args[2];
        // Properties first: SampleResult reads sampleresult.timestamp.start when the class loads.
        JMeterUtils.loadJMeterProperties(home + "/bin/jmeter.properties");
        JMeterUtils.setJMeterHome(home);
        JMeterUtils.initLocale();

        final Map<String, SamplingStatCalculator> rows = new LinkedHashMap<>();
        final SamplingStatCalculator total = new SamplingStatCalculator("TOTAL");
        final long[] seen = new long[2]; // rows read, transaction rows
        Visualizer aggregateReport = new Visualizer() {
            @Override public void add(SampleResult res) {
                seen[0]++;
                String message = res.getResponseMessage();
                if (message != null && message.startsWith(TRANSACTION_MARKER)) {
                    seen[1]++;
                }
                // StatVisualizer.add(): one calculator per label, and the TOTAL row gets every sample.
                rows.computeIfAbsent(res.getSampleLabel(), SamplingStatCalculator::new).addSample(res);
                total.addSample(res);
            }
            @Override public boolean isStats() { return true; }
        };
        CSVSaveService.processSamples(jtl, aggregateReport, new ResultCollector());

        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(Paths.get(out), StandardCharsets.UTF_8))) {
            w.println("label,samples,errors");
            for (SamplingStatCalculator c : rows.values()) {
                writeRow(w, c.getLabel(), c);
            }
            writeRow(w, "TOTAL", total);
        }
        System.out.println(String.format(Locale.US,
                "%s: %d rows read, %d transaction rows, %d labels, TOTAL samples %d, errors %d",
                Paths.get(jtl).getFileName(), seen[0], seen[1], rows.size(), total.getCount(),
                total.getErrorCount()));
    }

    private static void writeRow(PrintWriter w, String label, SamplingStatCalculator c) {
        w.println(csv(label) + "," + c.getCount() + "," + c.getErrorCount());
    }

    private static String csv(String s) {
        if (s == null) return "";
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
