import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.swing.JLabel;
import javax.swing.table.TableCellRenderer;

import org.apache.jmeter.reporters.ResultCollector;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.save.CSVSaveService;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.visualizers.SamplingStatCalculator;
import org.apache.jmeter.visualizers.Visualizer;
import org.apache.jorphan.gui.NumberRenderer;
import org.apache.jorphan.gui.ObjectTableModel;

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
 * <p>A row read from a file ends at start + elapsed, where the {@code SampleResult} a listener in the plan
 * receives ends at start + elapsed + idle; the referee puts the file's IdleTime back, so a Transaction
 * Controller's Throughput and KB/sec windows run to its real end, as the report measures them (B7).
 *
 * <p>Beside the counts, each row carries the text the Aggregate Report's table shows in every cell
 * ({@code shown_*}), drawn by JMeter's own table model and cell renderers
 * ({@code StatGraphVisualizer.createObjectTableModel} and {@code getRenderers}), and the exact mean, which
 * the table cuts to a whole number. R07 holds the report's Samplers rows to that text. Run it under the
 * locale JMeter's launcher gives an English machine ({@code -Duser.language=en -Duser.country=US}): the
 * table's decimal columns follow the JVM's locale, as they do in JMeter.
 *
 * <p>usage: {@code java AggregateReportReferee <jmeterHome> <results.jtl> <out.csv>}. Prints one summary
 * line: rows read, transaction rows, labels, TOTAL samples and errors.
 */
public class AggregateReportReferee {

    private static final String TRANSACTION_MARKER = "Number of samples in transaction : ";
    private static final String[] SHOWN = {"shown_samples", "shown_average", "shown_median", "shown_p90",
            "shown_p95", "shown_p99", "shown_min", "shown_max", "shown_error_pct", "shown_throughput",
            "shown_received_kb", "shown_sent_kb"};

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
                // The end a listener in the plan sees: a SampleResult's own end is start + elapsed + idle,
                // and a row loaded from a file ends at start + elapsed, losing the IdleTime column, so a
                // Transaction Controller (whose pauses are idle time) would be measured short of its real
                // end. The report measures to the real end, as the Aggregate Report listener does (B7);
                // only the windows of Throughput and KB/sec move, never a count.
                if (res.getIdleTime() > 0) {
                    res.setEndTime(res.getEndTime() + res.getIdleTime());
                }
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

        Table table = new Table();
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(Paths.get(out), StandardCharsets.UTF_8))) {
            w.println("label,samples,errors,mean," + String.join(",", SHOWN));
            for (SamplingStatCalculator c : rows.values()) {
                writeRow(w, c.getLabel(), c, table);
            }
            writeRow(w, "TOTAL", total, table);
        }
        System.out.println(String.format(Locale.US,
                "%s: %d rows read, %d transaction rows, %d labels, TOTAL samples %d, errors %d",
                Paths.get(jtl).getFileName(), seen[0], seen[1], rows.size(), total.getCount(),
                total.getErrorCount()));
    }

    private static void writeRow(PrintWriter w, String label, SamplingStatCalculator c, Table table) throws Exception {
        StringBuilder line = new StringBuilder(csv(label) + "," + c.getCount() + "," + c.getErrorCount() + ","
                + Double.toString(c.getMean()));
        for (String cell : table.shown(c)) {
            line.append(',').append(csv(cell));
        }
        w.println(line);
    }

    /**
     * The Aggregate Report's own table, one row at a time: its model reads the calculator through the
     * columns' functors, and each cell is the text its renderer draws (a column without one is drawn by
     * Swing's default renderer for a number, which writes {@code toString()}). Both are package-private in
     * JMeter, so they are reached by reflection, not copied: a pattern JMeter changes moves the reference.
     */
    private static final class Table {
        private final ObjectTableModel model;
        private final TableCellRenderer[] renderers;

        Table() throws Exception {
            Class<?> visualizer = Class.forName("org.apache.jmeter.visualizers.StatGraphVisualizer");
            Method create = visualizer.getDeclaredMethod("createObjectTableModel");
            create.setAccessible(true);
            Method draw = visualizer.getDeclaredMethod("getRenderers");
            draw.setAccessible(true);
            model = (ObjectTableModel) create.invoke(null);
            renderers = (TableCellRenderer[]) draw.invoke(null);
        }

        /** The text of every cell after the Label one. */
        List<String> shown(SamplingStatCalculator c) {
            model.clearData();
            model.addRow(c);
            List<String> cells = new ArrayList<>();
            for (int col = 1; col < model.getColumnCount(); col++) {
                Object value = model.getValueAt(0, col);
                TableCellRenderer r = renderers[col];
                if (r == null) {
                    cells.add(value == null ? "" : value.toString());
                } else {
                    // What the table calls on the renderer for this cell (DefaultTableCellRenderer skips it
                    // when handed no table, so it is called directly)
                    ((NumberRenderer) r).setValue(value);
                    cells.add(((JLabel) r).getText());
                }
            }
            if (cells.size() != SHOWN.length) {
                throw new IllegalStateException("the Aggregate Report has " + cells.size() + " value columns, expected "
                        + SHOWN.length);
            }
            return cells;
        }
    }

    private static String csv(String s) {
        if (s == null) return "";
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
