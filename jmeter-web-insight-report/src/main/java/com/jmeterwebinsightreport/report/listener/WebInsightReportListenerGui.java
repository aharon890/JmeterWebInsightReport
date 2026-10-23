package com.jmeterwebinsightreport.report.listener;

import com.jmeterwebinsightreport.report.listener.rebuild.FieldValue;
import com.jmeterwebinsightreport.report.listener.rebuild.GuiRebuild;
import com.jmeterwebinsightreport.report.listener.rebuild.LastRun;
import com.jmeterwebinsightreport.report.listener.rebuild.RebuildRequest;
import com.jmeterwebinsightreport.report.listener.rebuild.RebuildResult;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.services.FileServer;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.visualizers.gui.AbstractListenerGui;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.lang.ref.WeakReference;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;

/**
 * GUI configuration panel for the Web Insight Report Listener.
 *
 * <p>Besides the fields a run reads, the panel carries <b>Server data (CSV)</b> and the
 * <b>Rebuild report with server data</b> button: point it at the CSV your export tool wrote for the
 * run (or a {@code server-metrics.json}), click, and the report the run wrote is rebuilt with the
 * Server tab judged - the same rebuild {@code jmeter -g} runs, and the collector's own lines shown in
 * a dialog rather than only on the page. The logic is in {@code report.listener.rebuild}, without
 * Swing; this class reads the fields, runs it on a {@link SwingWorker} and shows what came back.
 */
public class WebInsightReportListenerGui extends AbstractListenerGui {

    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(WebInsightReportListenerGui.class);

    static final String REBUILD = "Rebuild report with server data";
    private static final String REBUILDING = "Rebuilding\u2026";

    private JTextField outputDirectoryField;
    private JTextField annotationsFileField;
    private JTextField resultsFileField;
    private JTextField serverDataField;
    private JButton browseServerData;
    private JLabel serverDataStatus;
    private JButton rebuildButton;
    private JTextField reportTitleField;
    private JTextField reportFilenameField;
    private JCheckBox generateJunitXmlCheckbox;

    /**
     * The element {@link #configure} last showed. JMeter keeps ONE instance of this panel per
     * component type and hands it each listener in turn, so the element's {@link LastRun} - which its
     * run-clone published into it - is read from here, never from the panel. Weak: a deleted element
     * is not kept alive by the panel that once showed it.
     */
    private transient WeakReference<TestElement> shown = new WeakReference<>(null);

    /** True while a rebuild runs; the button stays disabled, whichever listener the panel shows. */
    private transient boolean rebuilding;

    /** How often the status line is checked while the panel shows: it notices the CSV appear and a test end. */
    static final int STATUS_TICK_MILLIS = 2000;

    /**
     * How long the status line trusts an evaluation of a field that has a {@code ${...}} in it: long
     * enough that a burst of keystrokes in one field does not re-run a function in another on every
     * refresh, and shorter than a tick, so every tick evaluates afresh. Kept for a whole edit, a
     * {@code server-${__time(yyyyMMdd-HHmm)}.csv} went on naming the minute it was typed in as
     * "found" while the click, which evaluates at the click, read or refused another file - and the line
     * exists so that the result is never a surprise.
     */
    static final long EVALUATION_LIFETIME_MILLIS = 1000;

    /** A field's evaluation for the status line, and when it was made. */
    static final class Evaluation {
        final FieldValue value;
        final long at;

        Evaluation(FieldValue value, long at) {
            this.value = value;
            this.at = at;
        }
    }

    /**
     * Field evaluations for the status line, by text: see {@link #EVALUATION_LIFETIME_MILLIS}. Replaced,
     * not cleared, when the panel shows another element, so a check still running for the previous one
     * writes into a map nobody reads again. The click never reads it: it evaluates afresh.
     */
    private transient volatile Map<String, Evaluation> evaluated = newCache();

    /**
     * The one thread every status check runs on, for every panel in this JVM. A check stats each file the
     * fields name - the JTL, the server data, beside the JTL when it is relative - and on Windows a stat
     * on a share whose host is asleep or gone blocks for the SMB timeout, many seconds. On the event
     * thread, every two seconds, that froze JMeter's whole GUI for as long as the panel was selected.
     * Daemon: a check hung on a dead share must not keep JMeter from exiting.
     */
    private static final ExecutorService STATUS_CHECKS = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "web-insight-report-rebuild-status");
        thread.setDaemon(true);
        return thread;
    });

    /** Where this panel's status checks run: {@link #STATUS_CHECKS}, or what a test holds them in. */
    private final transient Executor statusChecks;

    /**
     * One check at a time (event thread only). While one runs - or hangs - a tick or a keystroke only
     * asks for another, and the one asked for runs once, after it: a hung stat cannot pile up checks.
     */
    private transient volatile boolean statusCheckRunning;
    private transient volatile boolean statusCheckAgain;

    /** Bumped when the panel shows another element (or none), so a check of the previous one is dropped. */
    private transient volatile int shownGeneration;

    /** Refreshes the status line shortly after the last keystroke. */
    private transient Timer refreshSoon;

    public WebInsightReportListenerGui() {
        this(STATUS_CHECKS);
    }

    /** With the status checks run on {@code statusChecks}: a test holds them to see when they run. */
    WebInsightReportListenerGui(Executor statusChecks) {
        this.statusChecks = statusChecks;
        init();
    }

    private void init() {
        setLayout(new BorderLayout(0, 5));
        setBorder(makeBorder());

        add(makeTitlePanel(), BorderLayout.NORTH);

        JPanel configPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(2, 2, 2, 2);

        // Report title
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0;
        configPanel.add(new JLabel("Report Title:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.gridwidth = 2;
        reportTitleField = new JTextField("JMeter Web Insight Report", 30);
        configPanel.add(reportTitleField, gbc);

        // Report filename
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0;
        gbc.gridwidth = 1;
        configPanel.add(new JLabel("Report Filename:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.gridwidth = 2;
        reportFilenameField = new JTextField(30);
        reportFilenameField.setToolTipText("Default: web-insight-report.html. Supports ${timestamp} placeholder.");
        configPanel.add(reportFilenameField, gbc);

        // Output directory with browse button
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.weightx = 0;
        gbc.gridwidth = 1;
        configPanel.add(new JLabel("Output Directory:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        outputDirectoryField = new JTextField(30);
        configPanel.add(outputDirectoryField, gbc);
        gbc.gridx = 2;
        gbc.weightx = 0;
        JButton browseButton = new JButton("Browse...");
        browseButton.addActionListener(e -> browseOutputDirectory());
        configPanel.add(browseButton, gbc);

        // Annotations file with browse button.
        //
        // The gap this closes: there was no field for it at all, so a GUI run could not be
        // pointed at an asserts file by any means. "Author in JSON or in the GUI" was true only
        // for someone already driving JMeter from a command line with -J.
        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.weightx = 0;
        gbc.gridwidth = 1;
        configPanel.add(new JLabel("Annotations File:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        annotationsFileField = new JTextField(30);
        annotationsFileField.setToolTipText("report-annotations.json \u2014 the asserts, timeline "
                + "markers and notes this run is judged against. Left empty, one is auto-detected "
                + "in the output directory. -Jwebinsight.report.annotations overrides this.");
        configPanel.add(annotationsFileField, gbc);
        gbc.gridx = 2;
        gbc.weightx = 0;
        JButton browseAnnotations = new JButton("Browse...");
        browseAnnotations.addActionListener(e -> browseAnnotationsFile());
        configPanel.add(browseAnnotations, gbc);

        // Results file (JTL). The listener IS a ResultCollector, so naming a file here makes JMeter
        // write the run's samples to it, as a Simple Data Writer would. A GUI run with only this
        // listener used to leave no JTL at all, and the server data cannot be added without one
        // (P5): the rebuild reads the run's JTL. Left empty, nothing is written, as before.
        gbc.gridx = 0;
        gbc.gridy = 4;
        gbc.weightx = 0;
        gbc.gridwidth = 1;
        configPanel.add(new JLabel("Results file (JTL):"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        resultsFileField = new JTextField(30);
        resultsFileField.setToolTipText("Where to write this run's samples (a CSV .jtl), e.g. C:/runs/checkout/results.jtl"
                + " - needed to add server data afterwards with the Rebuild report with server data button below,"
                + " jmeter -g or Tools > Generate HTML report."
                + " Leave empty when -l or another results writer already saves the JTL.");
        configPanel.add(resultsFileField, gbc);
        gbc.gridx = 2;
        gbc.weightx = 0;
        JButton browseResults = new JButton("Browse...");
        browseResults.addActionListener(e -> browseResultsFile());
        configPanel.add(browseResults, gbc);

        // Server data (CSV), read only by the Rebuild button below - never when the run ends (the
        // data does not exist yet), and never by jmeter -g, which cannot see a .jmx field (L2).
        gbc.gridx = 0;
        gbc.gridy = 5;
        gbc.weightx = 0;
        gbc.gridwidth = 1;
        configPanel.add(new JLabel("Server data (CSV):"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        serverDataField = new JTextField(30);
        serverDataField.setToolTipText("The CSV your export tool wrote for this run, e.g. C:/runs/checkout/server.csv,"
                + " or a server-metrics.json from wir-collect. Read only by Rebuild report with server data, after the"
                + " run. Functions and properties work (${__P(runs.dir)}/server.csv); test-plan variables do not."
                + " -Jwebinsight.report.servermetrics overrides this.");
        configPanel.add(serverDataField, gbc);
        gbc.gridx = 2;
        gbc.weightx = 0;
        browseServerData = new JButton("Browse...");
        browseServerData.addActionListener(e -> browseServerDataFile());
        configPanel.add(browseServerData, gbc);

        // What the field resolves to, before anyone clicks: the path, found or not, an override, a
        // refusal, a stale file. The result is never a surprise.
        gbc.gridx = 1;
        gbc.gridy = 6;
        gbc.weightx = 1.0;
        gbc.gridwidth = 2;
        serverDataStatus = new JLabel(" ");
        configPanel.add(serverDataStatus, gbc);

        gbc.gridx = 1;
        gbc.gridy = 7;
        gbc.weightx = 0;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.WEST;
        rebuildButton = new JButton(REBUILD);
        rebuildButton.addActionListener(e -> rebuild());
        configPanel.add(rebuildButton, gbc);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.CENTER;

        // Generate JUnit XML checkbox
        gbc.gridx = 0;
        gbc.gridy = 8;
        gbc.weightx = 0;
        gbc.gridwidth = 1;
        configPanel.add(new JLabel(""), gbc); // empty label for alignment
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.gridwidth = 2;
        generateJunitXmlCheckbox = new JCheckBox("Generate JUnit XML", false);
        generateJunitXmlCheckbox.setToolTipText("Generate a JUnit XML report alongside the HTML report (for CI/CD integration).");
        configPanel.add(generateJunitXmlCheckbox, gbc);

        add(configPanel, BorderLayout.CENTER);

        // Refreshed as you type (shortly after the last key), and every two seconds while shown: a CSV
        // exported meanwhile appears as found, and the button comes back once a running test ends.
        refreshSoon = new Timer(300, e -> refreshServerDataStatus());
        refreshSoon.setRepeats(false);
        DocumentListener edited = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refreshSoon.restart();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refreshSoon.restart();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                refreshSoon.restart();
            }
        };
        for (JTextField field : new JTextField[]{serverDataField, resultsFileField, annotationsFileField,
                reportTitleField, reportFilenameField, outputDirectoryField}) {
            field.getDocument().addDocumentListener(edited);
        }
        Timer whileShown = new Timer(STATUS_TICK_MILLIS, e -> {
            if (isShowing()) {
                refreshServerDataStatus();
            }
        });
        whileShown.start();
        refreshServerDataStatus();
    }

    private void browseAnnotationsFile() {
        JFileChooser chooser = new JFileChooser(annotationsFileField.getText());
        chooser.setDialogTitle("Select the annotations file");
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            annotationsFileField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void browseResultsFile() {
        JFileChooser chooser = new JFileChooser(resultsFileField.getText());
        chooser.setDialogTitle("Write the run's results (JTL) to");
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            resultsFileField.setText(chooser.getSelectedFile().getAbsolutePath().replace(java.io.File.separatorChar, '/'));
        }
    }

    /**
     * Starts in the results file's folder - the export usually sits beside the run - and writes the
     * path with {@code /}, as the results file's Browse does, so a {@code .jmx} saved on Windows reads on
     * macOS and Linux once the folder exists there.
     */
    private void browseServerDataFile() {
        JFileChooser chooser = new JFileChooser(startFolder());
        chooser.setDialogTitle("Select the server data for this run");
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setFileFilter(new FileNameExtensionFilter("Server data (*.csv, *.json)", "csv", "json"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            serverDataField.setText(chooser.getSelectedFile().getAbsolutePath().replace(File.separatorChar, '/'));
        }
    }

    private File startFolder() {
        for (String text : new String[]{serverDataField.getText(), resultsFileField.getText()}) {
            if (text != null && !text.trim().isEmpty() && !text.contains("${")) {
                File parent = new File(text.trim()).getAbsoluteFile().getParentFile();
                if (parent != null && parent.isDirectory()) {
                    return parent;
                }
            }
        }
        return null;
    }

    private void browseOutputDirectory() {
        JFileChooser chooser = new JFileChooser(outputDirectoryField.getText());
        chooser.setDialogTitle("Select Output Directory");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            outputDirectoryField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    // ---- Rebuild report with server data --------------------------------------------------------

    /**
     * What a status check or a click reads from the panel, taken on the event thread - the fields as they
     * read (unsaved edits included: a panel button bypasses ActionRouter, so the element has not been
     * updated from the fields, and must not be, here: {@code refreshCurrentGui()} would reload the panel
     * from the element and lose them), the shown element's last run, whether a test runs. Taking it
     * touches no file; {@link #resolve} does, and runs off the event thread.
     */
    static final class Snapshot {
        final RebuildRequest.Panel panel;
        final List<String> texts;
        final LastRun lastRun;
        final boolean running;
        final int generation;

        Snapshot(List<String> texts, boolean junit, LastRun lastRun, boolean running, int generation) {
            this.panel = new RebuildRequest.Panel(texts.get(0), texts.get(1), texts.get(2), texts.get(3),
                    texts.get(4), texts.get(5), junit);
            this.texts = texts;
            this.lastRun = lastRun;
            this.running = running;
            this.generation = generation;
        }
    }

    private Snapshot snapshot() {
        return new Snapshot(fieldTexts(), generateJunitXmlCheckbox.isSelected(), lastRunOfShownElement(),
                aTestIsRunning(), shownGeneration);
    }

    /** The six fields a rebuild reads, in {@link RebuildRequest.Panel}'s order, the checkbox last. */
    private List<String> fieldTexts() {
        return Arrays.asList(reportTitleField.getText(), reportFilenameField.getText(), outputDirectoryField.getText(),
                annotationsFileField.getText(), resultsFileField.getText(), serverDataField.getText(),
                String.valueOf(generateJunitXmlCheckbox.isSelected()));
    }

    /** What {@code snapshot} would rebuild. Stats the files it names: never on the event thread. */
    private static RebuildRequest resolve(Snapshot snapshot, Function<String, FieldValue> evaluate) {
        return RebuildRequest.resolve(snapshot.panel, snapshot.lastRun, WebInsightReportListenerGui::property,
                snapshot.running, WebInsightReportListenerGui::baseRelative, evaluate, ZoneId.systemDefault());
    }

    /** What a click now would rebuild, resolved on the calling thread. */
    RebuildRequest currentRequest(Function<String, FieldValue> evaluate) {
        return resolve(snapshot(), evaluate);
    }

    private LastRun lastRunOfShownElement() {
        TestElement element = shown.get();
        return element instanceof WebInsightReportListener ? ((WebInsightReportListener) element).getLastRun() : null;
    }

    /**
     * R8: a run of this plugin whose report is still to come, or any JMeter test still started -
     * {@code JMeterContextService}'s start time returns to 0 only once every results file is closed.
     */
    private static boolean aTestIsRunning() {
        return WebInsightReportListener.runsInFlight() > 0 || JMeterContextService.getTestStartTime() != 0;
    }

    private static String property(String name) {
        try {
            return JMeterUtils.getProperty(name);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String baseRelative(String name) {
        try {
            return FileServer.resolveBaseRelativeName(name);
        } catch (RuntimeException e) {
            return name;
        }
    }

    /**
     * Check what a click now would do, off the event thread, and show it in the status line and the
     * button when the check returns - unless the panel has changed meanwhile, when it is checked again
     * instead. One check at a time: see {@link #statusCheckRunning}.
     */
    void refreshServerDataStatus() {
        if (statusCheckRunning) {
            statusCheckAgain = true;
            return;
        }
        Snapshot asked = snapshot();
        Map<String, Evaluation> cache = evaluated;
        statusCheckRunning = true;
        statusCheckAgain = false;
        try {
            statusChecks.execute(() -> {
                RebuildRequest request = null;
                try {
                    request = resolve(asked, text -> cachedEvaluation(cache, text, System.currentTimeMillis(),
                            FieldValue::evaluate));
                } catch (RuntimeException e) {
                    log.debug("Could not resolve the rebuild request: {}", e.getMessage());
                }
                RebuildRequest checked = request;
                SwingUtilities.invokeLater(() -> statusChecked(asked, checked));
            });
        } catch (RejectedExecutionException e) {
            statusCheckRunning = false;
            log.debug("Could not check the rebuild status: {}", e.getMessage());
        }
    }

    /** A check returned (event thread): shown when it is of the panel as it is now, then any check asked for since. */
    private void statusChecked(Snapshot asked, RebuildRequest request) {
        statusCheckRunning = false;
        boolean current = asked.generation == shownGeneration && asked.texts.equals(fieldTexts());
        if (request != null && current) {
            showStatus(request);
        }
        if (statusCheckAgain || !current) {
            refreshServerDataStatus();
        }
    }

    /** The status line as it reads now. */
    String statusText() {
        return serverDataStatus.getText();
    }

    /** The status line and the button, from what a click now would do. */
    private void showStatus(RebuildRequest request) {
        String status = request.statusLine();
        String second = request.isRefused() && !request.refusedOnServerData() ? request.refusal() : null;
        serverDataStatus.setText(rebuilding ? REBUILDING : html(status, second));
        boolean overridden = request.serverDataOverridden();
        serverDataField.setEnabled(!overridden);
        browseServerData.setEnabled(!overridden);
        rebuildButton.setEnabled(!rebuilding && !request.isRefused());
        rebuildButton.setToolTipText(rebuilding ? "A rebuild is running."
                : request.isRefused() ? request.refusal()
                : "Rebuild the report this listener wrote, with this server data: read the run's JTL, collect the"
                + " CSV and judge the Server tab - the same rebuild jmeter -g runs - then show what the collector said.");
    }

    /**
     * A field's evaluation for the status line: the one kept for {@code text} while it is young enough to
     * still be what a click would read (see {@link #EVALUATION_LIFETIME_MILLIS}), else a fresh one, kept.
     * Text with no {@code ${...}} reads the same however long ago it was read.
     */
    static FieldValue cachedEvaluation(Map<String, Evaluation> cache, String text, long now,
                                       Function<String, FieldValue> evaluate) {
        String key = text == null ? "" : text;
        Evaluation kept;
        synchronized (cache) {
            kept = cache.get(key);
        }
        if (kept != null && (!key.contains("${") || now - kept.at < EVALUATION_LIFETIME_MILLIS)) {
            return kept.value;
        }
        FieldValue fresh = evaluate.apply(key);
        synchronized (cache) {
            cache.put(key, new Evaluation(fresh, now));
        }
        return fresh;
    }

    private void rebuild() {
        Snapshot clicked = snapshot();
        rebuilding = true;
        rebuildButton.setEnabled(false);
        rebuildButton.setText(REBUILDING);
        serverDataStatus.setText(REBUILDING);
        new SwingWorker<RebuildResult, Void>() {
            private volatile RebuildRequest request;

            @Override
            protected RebuildResult doInBackground() {
                // Afresh at the click, never from the status line's evaluations: a function reads the
                // time of this click. And here, not on the event thread: resolving stats every file the
                // fields name, which can block for as long as a sleeping share takes to time out.
                request = resolve(clicked, FieldValue::evaluate);
                return request.isRefused() ? null : GuiRebuild.run(request);
            }

            @Override
            protected void done() {
                rebuilding = false;
                rebuildButton.setText(REBUILD);
                refreshServerDataStatus();
                try {
                    RebuildResult result = get();
                    if (result == null) {
                        JOptionPane.showMessageDialog(dialogParent(), request.refusal(), REBUILD,
                                JOptionPane.WARNING_MESSAGE);
                    } else {
                        showResult(result);
                    }
                } catch (Exception e) {
                    log.error("The rebuild did not finish", e);
                    JOptionPane.showMessageDialog(dialogParent(), "The rebuild did not finish: " + e.getMessage(),
                            REBUILD, JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    /** The result dialog: what was written, the verdict, the collector's lines; Open report, Copy, Close. */
    private void showResult(RebuildResult result) {
        Window owner = dialogParent();
        JDialog dialog = new JDialog(owner, result.headline(), Dialog.ModalityType.MODELESS);
        String text = result.text();
        JTextArea area = new JTextArea(text, 24, 100);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setCaretPosition(0);

        JButton open = new JButton("Open report");
        File html = result.html();
        boolean canBrowse = html != null && Desktop.isDesktopSupported()
                && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE);
        open.setEnabled(canBrowse);
        if (html != null && !canBrowse) {
            open.setToolTipText("This desktop cannot open a browser from Java - open " + html.getAbsolutePath()
                    + " yourself (Copy copies it).");
        }
        open.addActionListener(e -> {
            try {
                Desktop.getDesktop().browse(html.toURI());
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(dialog, "Could not open a browser: " + ex.getMessage()
                        + "\nThe report is " + html.getAbsolutePath(), REBUILD, JOptionPane.WARNING_MESSAGE);
            }
        });
        JButton copy = new JButton("Copy");
        copy.addActionListener(e -> {
            try {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
            } catch (Exception ex) {
                log.warn("Could not copy to the clipboard: {}", ex.getMessage());
            }
        });
        JButton close = new JButton("Close");
        close.addActionListener(e -> dialog.dispose());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(open);
        buttons.add(copy);
        buttons.add(close);
        dialog.getContentPane().setLayout(new BorderLayout(0, 5));
        dialog.getContentPane().add(new JScrollPane(area), BorderLayout.CENTER);
        dialog.getContentPane().add(buttons, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    private Window dialogParent() {
        GuiPackage gui = GuiPackage.getInstance();
        if (gui != null && gui.getMainFrame() != null) {
            return gui.getMainFrame();
        }
        return SwingUtilities.getWindowAncestor(this);
    }

    /** One or two lines of plain text in a label, escaped: a path is not markup. */
    private static String html(String first, String second) {
        StringBuilder sb = new StringBuilder("<html>").append(escape(first == null || first.isEmpty() ? " " : first));
        if (second != null) {
            sb.append("<br>").append(escape(second));
        }
        return sb.append("</html>").toString();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static Map<String, Evaluation> newCache() {
        return new LinkedHashMap<String, Evaluation>(16, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Evaluation> eldest) {
                return size() > 32;
            }
        };
    }

    // ---- the element ---------------------------------------------------------------------------

    @Override
    public String getLabelResource() {
        return "web_insight_report_listener";
    }

    @Override
    public String getStaticLabel() {
        return "Web Insight Report";
    }

    @Override
    public TestElement createTestElement() {
        WebInsightReportListener listener = new WebInsightReportListener();
        modifyTestElement(listener);
        return listener;
    }

    @Override
    public void modifyTestElement(TestElement element) {
        super.configureTestElement(element);
        element.setProperty(WebInsightReportListener.REPORT_TITLE, reportTitleField.getText());
        element.setProperty(WebInsightReportListener.REPORT_FILENAME, reportFilenameField.getText());
        element.setProperty(WebInsightReportListener.REPORT_OUTPUT_DIR, outputDirectoryField.getText());
        element.setProperty(WebInsightReportListener.ANNOTATIONS_FILE, annotationsFileField.getText());
        // ResultCollector's own property: JMeter writes the JTL, and its "file exists" prompt
        // (append / overwrite / don't start) guards a re-run.
        element.setProperty(org.apache.jmeter.reporters.ResultCollector.FILENAME, resultsFileField.getText().trim());
        element.setProperty(WebInsightReportListener.SERVER_DATA_FILE, serverDataField.getText().trim());
        element.setProperty(WebInsightReportListener.GENERATE_JUNIT_XML, generateJunitXmlCheckbox.isSelected());
    }

    @Override
    public void configure(TestElement element) {
        super.configure(element);
        shown = new WeakReference<>(element);
        evaluated = newCache();
        shownGeneration++;
        reportTitleField.setText(element.getPropertyAsString(
                WebInsightReportListener.REPORT_TITLE, "JMeter Web Insight Report"));
        reportFilenameField.setText(element.getPropertyAsString(
                WebInsightReportListener.REPORT_FILENAME, ""));
        outputDirectoryField.setText(element.getPropertyAsString(
                WebInsightReportListener.REPORT_OUTPUT_DIR, ""));
        annotationsFileField.setText(element.getPropertyAsString(
                WebInsightReportListener.ANNOTATIONS_FILE, ""));
        resultsFileField.setText(element.getPropertyAsString(
                org.apache.jmeter.reporters.ResultCollector.FILENAME, ""));
        serverDataField.setText(element.getPropertyAsString(
                WebInsightReportListener.SERVER_DATA_FILE, ""));
        generateJunitXmlCheckbox.setSelected(element.getPropertyAsBoolean(
                WebInsightReportListener.GENERATE_JUNIT_XML, false));
        refreshServerDataStatus();
    }

    @Override
    public void clearGui() {
        super.clearGui();
        reportTitleField.setText("JMeter Web Insight Report");
        reportFilenameField.setText("");
        outputDirectoryField.setText("");
        // JMeter reuses ONE gui instance per component type, so whatever is left in a field here
        // is what the next listener someone adds starts with — and is what gets saved into their
        // .jmx. A leaked annotations path is the worst of the six to forget: the new test is
        // then judged against another test's quality gate, which looks exactly like a real
        // verdict, or against a file that is not there, which now exits 1 by default. A leaked
        // server data path is the same mistake one click later: Rebuild would judge the new test
        // against another test's server data.
        annotationsFileField.setText("");
        resultsFileField.setText("");
        serverDataField.setText("");
        generateJunitXmlCheckbox.setSelected(false);
        shown = new WeakReference<>(null);
        evaluated = newCache();
        shownGeneration++;
        refreshServerDataStatus();
    }
}
