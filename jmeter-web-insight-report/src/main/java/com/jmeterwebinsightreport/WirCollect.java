package com.jmeterwebinsightreport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmeterwebinsightreport.core.assertion.AssertionValidator;
import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.model.TestMetadata;
import com.jmeterwebinsightreport.core.parser.JtlParser;
import com.jmeterwebinsightreport.core.servermetrics.CollectionStatus;
import com.jmeterwebinsightreport.core.servermetrics.Durations;
import com.jmeterwebinsightreport.core.servermetrics.KpiUsability;
import com.jmeterwebinsightreport.core.servermetrics.ServerGate;
import com.jmeterwebinsightreport.core.servermetrics.ServerMetricsConfig;
import com.jmeterwebinsightreport.core.servermetrics.ServerSource;
import com.jmeterwebinsightreport.core.servermetrics.catalogue.Catalogue;
import com.jmeterwebinsightreport.core.servermetrics.collect.Collector;
import com.jmeterwebinsightreport.core.servermetrics.collect.Coverage;
import com.jmeterwebinsightreport.core.servermetrics.collect.CsvColumns;
import com.jmeterwebinsightreport.core.servermetrics.collect.CsvShape;
import com.jmeterwebinsightreport.core.servermetrics.collect.CsvSource;
import com.jmeterwebinsightreport.core.servermetrics.collect.FileSource;
import com.jmeterwebinsightreport.core.servermetrics.collect.PrometheusSource;
import com.jmeterwebinsightreport.core.servermetrics.collect.SeriesSource;
import com.jmeterwebinsightreport.core.servermetrics.collect.ServerMetricsPayload;
import com.jmeterwebinsightreport.core.util.AtomicFileWriter;
import com.jmeterwebinsightreport.core.util.RunIdentity;
import com.jmeterwebinsightreport.report.annotation.AnnotationLoader;
import com.jmeterwebinsightreport.report.annotation.ServerMetricsJson;
import com.jmeterwebinsightreport.report.annotation.ServerMetricsMapper;

import java.io.File;
import java.io.PrintStream;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * {@code wir-collect}: the CI step between the JMeter run and the report.
 *
 * <pre>
 *   jmeter -n -t plan.jmx -l results.jtl                       # 1. the run (interim report, client gate)
 *   wir-collect --jtl results.jtl --annotations a.json --out server-metrics.json   # 2. this
 *   jmeter -g results.jtl -o out -Jwebinsight.report.annotations=a.json \
 *          -Jwebinsight.report.servermetrics=server-metrics.json                # 3. the report that gates
 * </pre>
 *
 * <p>Step 3 names the annotations file explicitly: {@code -g -o} demands an empty output
 * directory, so there is nothing for the exporter to auto-detect there. C37 runs these three
 * lines under {@code set -e} with a source that is down, and expects step 3's report.
 *
 * <p>A separate step, not part of the listener, for the reasons the canvas gave: a crashed test
 * still gets a report, distributed generators work, and metrics can be re-collected and the
 * report regenerated without re-running the test. Everything the collector needs about the
 * run — window, identity, test name — it derives from the JTL through the same core the
 * generator uses, so the payload's {@code runId} is the report's {@code runId} by construction.
 * (There is no {@code run-context.json}: a fourth file outside the manifest, the publish path
 * and the exists-policy was the canvas's idea, and the JTL already carries every fact in it.)
 *
 * <p><b>The test name matters.</b> {@code RunIdentity} hashes it, so the name given here must
 * be the one the {@code -g} exporter will use — the report title. {@code --test-name} defaults
 * to the exporter's default title and must be set to the same text as
 * {@code -Jwebinsight.report.title} when step 3 sets one.
 *
 * <p><b>Exit codes (D27).</b> <b>0 whenever the payload was written</b>, whatever each series'
 * status: the payload records FAILED, EMPTY and INSUFFICIENT, and the <em>report</em> decides
 * what each one means under {@code failonerror}.
 * <b>1</b> when nothing was written: a usage, input or environment fault (bad arguments, an
 * unreadable JTL or annotations file, a file with nothing to collect, an undeclared
 * {@code --env} or {@code --component}, no reader for a source type, an unwritable
 * {@code --out}, a ramp-up that swallows the run). There is no other code. Until M7d a series
 * that was not judgeable exited 2, and that stopped a Jenkins {@code sh} step, or any
 * {@code set -e} script, before step 3, the only report that gates — and the collector, which
 * sees series and not the asserts' consequences, was the wrong place to decide that.
 *
 * <p><b>Nothing to collect</b> is a file with no server assert <em>and</em> no component of a
 * catalogue type. A file that declares {@code postgres} or {@code kafka} components and no
 * assert yet is collected — every catalogue metric of each, as D1 has it — and exits 0, so the
 * page's metric picker has this run's values for the first assert (SF6). That shape used to exit
 * 1 as "declares no serverMetrics.asserts"; it is the one input whose exit code changed, and it
 * stays inside D27's rule (0 whenever the payload was written). A custom component alone still
 * exits 1: it has no catalogue, so there is nothing to ask a source for.
 *
 * <p>Runs inside the shaded plugin JAR with JMeter's {@code lib/} on the classpath - Jackson and
 * slf4j are JMeter's. {@code java -jar <the plugin JAR>} gets there through
 * {@link WirCollectLauncher}, which finds JMeter from the JAR's place in {@code lib/ext} and runs
 * this class over the plugin JAR plus {@code lib/*}: the same command on every OS. The wrappers
 * {@code scripts/wir-collect.sh} (bash) and {@code .bat} (one {@code java} line) need nothing else
 * from this repository. {@code --help} prints the plain {@code java -jar} line.
 *
 * <p>Console lines are ASCII. They are for {@code grep} in a CI log, and the JVM writes
 * {@code System.out} in the platform charset — an em dash became U+FFFD in every log captured
 * under Git Bash and Jenkins. Every line goes through {@link #ascii}, the findings and errors
 * other classes wrote included: only {@code --help} was ASCII until review:W.
 */
public final class WirCollect {

    static final String USAGE = "usage: wir-collect --jtl <results.jtl> --annotations <report-annotations.json>"
            + " --out <server-metrics.json> (--prometheus-url URL [--prometheus-token-env NAME] | --recording DIR"
            + " | --csv FILE [--from TOOL] [--layout wide [--columns MAP]] [--csv-columns SPEC] [--component ID]"
            + " [--time-zone ZONE])"
            + " [--step 15s] [--clock-offset 0s] [--env NAME] [--test-name NAME] [--rampup MILLIS] [--max-wait 10m] [--no-wait]";

    /** What {@code --time-zone} reads, for the sentences that refuse one. */
    private static final String ZONE_GRAMMAR = "an IANA id such as Europe/Berlin, UTC, or an offset such as +03:00";

    /** How many headers a console line lists before it says how many more there are. */
    static final int LISTED = 10;

    /** Printed under {@link #USAGE} after a usage error, so the long text is one flag away. */
    static final String MORE = "wir-collect --help lists the options, the exit codes and the plain java -jar command";

    static final String MAIN_CLASS = "com.jmeterwebinsightreport.WirCollect";

    /** Every option there is. Anything else is refused: a misspelt flag that is silently kept
     * changes the payload's identity ({@code --test_name}) or the wait ({@code --maxwait}) without a word.
     * {@code --help} and {@code -h} are not in here: {@link #asksForHelp} answers them before the
     * arguments are parsed, where an option can stand and never as an option's value. */
    static final Set<String> OPTIONS = new LinkedHashSet<>(Arrays.asList(
            "jtl", "annotations", "out", "prometheus-url", "prometheus-token-env", "recording", "csv", "from",
            "layout", "columns", "csv-columns", "component", "time-zone", "step", "clock-offset", "env", "test-name",
            "rampup", "max-wait", "no-wait", "collected-at"));

    private WirCollect() {
    }

    public static void main(String[] args) {
        int code;
        try {
            code = run(args, System.out, System.err, System.getenv());
        } catch (OutOfMemoryError e) {
            // The CSV is held in memory while it is indexed - about ten times its size. A raw stack
            // trace said nothing a CI log reader could act on.
            System.err.println("wir-collect: ran out of memory reading the server data (" + e.getMessage()
                    + "). Give the JVM more heap, e.g. JAVA_TOOL_OPTIONS=-Xmx2g, or export a narrower window"
                    + " - the rows outside the run are not used");
            code = 1;
        }
        System.exit(code);
    }

    /** The whole program, testable: returns the exit code instead of calling {@code System.exit}. */
    public static int run(String[] args, PrintStream out, PrintStream err, Map<String, String> environment) {
        return run(args, null, out, err, environment);
    }

    /**
     * The same program over a run its caller has already parsed: {@code jmeter -g}, which collects a
     * CSV in-process right after parsing {@code --jtl} for its report (P4), so the JTL is read once.
     * {@code parsed} must be that file's parse under the report's title - the test name the report
     * hashes into its {@code runId} - with or without the report's ramp-up and Apdex settings, which
     * change neither the window nor the identity; the window, the {@code runId}, the {@code --rampup}
     * check, every refusal, every console line and the payload are this collector's own, exactly as
     * from the file. {@code --jtl} is still required and checked, and names the run in the sentences.
     * Null parses {@code --jtl} here, as {@code wir-collect} always does.
     */
    public static int run(String[] args, ReportData parsed, PrintStream out, PrintStream err,
                          Map<String, String> environment) {
        if (asksForHelp(args)) {
            for (String line : help(pluginJarName()).split("\n")) {
                println(out, line);
            }
            return 0;
        }
        Map<String, String> opts;
        try {
            opts = parseArgs(args);
        } catch (IllegalArgumentException e) {
            println(err, "wir-collect: " + e.getMessage());
            println(err, USAGE);
            println(err, MORE);
            return 1;
        }

        File jtl = new File(opts.get("jtl"));
        File annotations = new File(opts.get("annotations"));
        File outFile = new File(opts.get("out"));
        if (!jtl.isFile()) {
            println(err, "wir-collect: JTL does not exist: " + jtl.getAbsolutePath());
            return 1;
        }

        // 1. The run, through the same core the generator uses.
        String testName = opts.containsKey("test-name") ? opts.get("test-name") : DEFAULT_TEST_NAME;
        ReportData data;
        if (parsed != null) {
            // The caller's parse names the run as its report does: that name is the one hashed.
            data = parsed;
            if (parsed.getMetadata() != null && parsed.getMetadata().getTestName() != null) {
                testName = parsed.getMetadata().getTestName();
            }
        } else {
            try {
                data = new JtlParser().parse(jtl, testName);
            } catch (Exception e) {
                println(err, "wir-collect: the JTL could not be parsed: " + jtl.getAbsolutePath() + " - " + e.getMessage());
                return 1;
            }
        }
        TestMetadata meta = data.getMetadata();
        if (meta == null || meta.getStartTime() == null || meta.getEndTime() == null) {
            println(err, "wir-collect: the JTL carries no samples, so there is no window to collect");
            return 1;
        }
        Long rampUpEnd = null;
        if (opts.containsKey("rampup")) {
            long rampUp = Long.parseLong(opts.get("rampup"));
            long runMillis = meta.getEndTime().toEpochMilli() - meta.getStartTime().toEpochMilli();
            if (rampUp >= runMillis) {
                // A ramp-up as long as the run leaves an empty judged window: every KPI NO_VALUE,
                // which is not a config error, so the report would exit 0 having judged nothing.
                // Commonest cause by far is seconds given where milliseconds are meant.
                println(err, "wir-collect: --rampup " + rampUp + " ms is at or beyond the end of the run ("
                        + runMillis + " ms between the JTL's first and last sample), so no server assert would "
                        + "have a moment left to be judged in. --rampup is in MILLISECONDS - the same number "
                        + "as -Jwebinsight.report.rampup");
                println(err, USAGE);
                println(err, MORE);
                return 1;
            }
            rampUpEnd = rampUp > 0 ? meta.getStartTime().toEpochMilli() + rampUp : null;
        }
        ServerMetricsPayload.Window window = new ServerMetricsPayload.Window(
                meta.getStartTime().toEpochMilli(), meta.getEndTime().toEpochMilli(), rampUpEnd);
        String runId = RunIdentity.of(data);

        // 2. The declaration.
        AnnotationLoader.Result loaded = new AnnotationLoader().read(annotations);
        if (loaded.unusable()) {
            println(err, "wir-collect: " + loaded.problem());
            return 1;
        }
        ServerMetricsMapper.Result mapped = new ServerMetricsMapper().map(loaded.annotations().getServerMetrics());
        for (AssertionValidator.Finding f : mapped.findings()) {
            println(err, "wir-collect: " + f.describe());
        }
        ServerMetricsConfig config = mapped.config();
        // Something to collect is a server assert, or a component of a catalogue type - whose
        // every catalogue metric the collector fetches (D1), so the picker can show this run's
        // values before the first assert is written (SF6). A file with neither, a custom
        // component alone included, has nothing to ask a source for.
        if (!config.declaresAnything() && !declaresCatalogueComponent(config)) {
            println(err, "wir-collect: " + annotations.getAbsolutePath() + " declares no serverMetrics.asserts"
                    + " and no component of a catalogue type (" + catalogueTypes() + ") - nothing to collect");
            return 1;
        }

        // A --component the file does not declare would put every row where nothing asks: each
        // series EMPTY, with nothing to say the id was mistyped. Refused, as an undeclared --env is.
        String component = opts.containsKey("component") ? opts.get("component").trim() : null;
        // (Without a components block the config holds an implicit gate, which no file declared.)
        Set<String> declared = config.gatesDeclared() ? config.gates().keySet() : Collections.<String>emptySet();
        if (component != null && !declared.contains(component)) {
            println(err, "wir-collect: --component '" + component + "' is not declared under serverMetrics.components"
                    + (declared.isEmpty() ? " (none are)" : " (declared: " + String.join(", ", declared) + ")"));
            return 1;
        }

        // 3. The source: one, from the flags (C8), under the id every assert names. For a CSV, what
        //    the flags left unsaid - the layout, the step - is read off the file (decision 12) and
        //    printed, so a measured value is never silent and the flag that overrides it is named.
        //    The reader that measures is the one that collects, so the file is read once; and it
        //    measures only the series the collection will read, asked as the collection asks.
        CsvSource csvReader = new CsvSource();
        Collector.Run run = new Collector.Run(runId, testName, window);
        if (opts.containsKey("csv") && new File(opts.get("csv")).isFile()) {
            Collector planner = new Collector(Collections.<ServerSource.Type, SeriesSource>emptyMap(), environment);
            ServerMetricsConfig declaration = config;
            String env = opts.get("env");
            for (String line : measureCsv(opts, csvReader, window,
                    s -> seriesMeasured(planner, declaration, env, run, s))) {
                println(out, "wir-collect: " + line);
            }
        }
        ServerSource source = collectionSource(opts);
        if (source.type() == ServerSource.Type.CSV && !new File(source.path()).isFile()) {
            // As a missing --jtl is: a typo here used to write a payload of failed series and exit 0,
            // to be found only at step 3.
            println(err, "wir-collect: CSV file does not exist: " + new File(source.path()).getAbsolutePath());
            return 1;
        }
        config = config.withSource(source);
        PrometheusSource.Settle settle = opts.containsKey("no-wait")
                ? PrometheusSource.Settle.none()
                : new PrometheusSource.Settle(parseMaxWait(opts.get("max-wait"), err),
                        PrometheusSource.Settle.DEFAULT_POLL_MILLIS, PrometheusSource.Settle.DEFAULT_STABLE_FOR);
        Map<ServerSource.Type, SeriesSource> readers = new EnumMap<>(ServerSource.Type.class);
        // --recording and --csv are command-line paths, so they are relative to where the command
        // runs; each is written into the payload as typed, never made absolute (no local path on
        // the page).
        readers.put(ServerSource.Type.FILE, new FileSource());
        // Kept: a wide file's columns that named nothing are read off it after the collection.
        readers.put(ServerSource.Type.CSV, csvReader);
        readers.put(ServerSource.Type.PROMETHEUS, PrometheusSource.live(environment, settle));

        // 4. The collection.
        ServerMetricsPayload payload;
        try {
            payload = new Collector(readers, environment).collect(config, opts.get("env"), run,
                    // --collected-at: the time to stamp, for jmeter -g collecting in-process (the CSV's
                    // own time), so a rebuild from the same file keeps its collectionId - and the
                    // reader's server drafts. Not in --help: the report passes it, a person does not.
                    opts.containsKey("collected-at") ? opts.get("collected-at") : Instant.now().toString());
        } catch (Collector.CollectionException e) {
            println(err, "wir-collect: " + e.getMessage());
            return 1;
        }

        // 5. The payload, staged and moved like every other artifact.
        try {
            AtomicFileWriter.write(outFile, payloadText(payload));
        } catch (Exception e) {
            println(err, "wir-collect: the payload could not be written: " + outFile.getAbsolutePath() + " - " + e.getMessage());
            return 1;
        }

        // 6. One line per outcome, greppable, then the summary. The exit is 0 from here on,
        //    whatever the statuses (D27): they are in the payload, and the report judges them.
        //    A wide file first says which of its columns named nothing: an unset legend is
        //    otherwise only an EMPTY series further down, with nothing to say why.
        String unmapped = unmappedColumnsLine(csvReader.unmappedColumns());
        if (unmapped != null) {
            println(out, "wir-collect: " + unmapped);
        }
        if (!csvReader.mapColumnsNotInFile().isEmpty()) {
            println(out, "wir-collect: " + mapColumnsLine(new File(source.csvColumns()).getName(),
                    new File(source.path()).getName(), csvReader.mapColumnsNotInFile()));
        }
        // ...and which named a series nothing asked for: a typo in a legend's component reads as
        // component/metric/instance, so it is not "not mapped", and is otherwise as silent.
        String unasked = unaskedColumnsLine(csvReader.columnsNotAskedFor());
        if (unasked != null) {
            println(out, "wir-collect: " + unasked);
        }
        // The same for a long file: rows under a component and metric nothing asked for are a name
        // the annotations spell otherwise ("App" for "app", cpu_pct for cpu.pct), or an export that
        // carries more than is asserted on - listed, so the first is seen.
        String unaskedSeries = unaskedSeriesLine(csvReader.seriesNotAskedFor());
        if (unaskedSeries != null) {
            println(out, "wir-collect: " + unaskedSeries);
        }
        for (String duplicate : csvReader.duplicateNotes()) {
            println(out, "wir-collect: WARNING " + duplicate);
        }
        // The window the rows were matched against, so a file of another range or zone can be seen
        // for what it is next to the lines below.
        println(out, "wir-collect: run window " + java.time.Instant.ofEpochMilli(window.getStart()) + " .. "
                + java.time.Instant.ofEpochMilli(window.getEnd()) + " (" + data.getTopLevelSampleCount()
                + " samples" + (source.clockOffsetMs() != 0 ? ", rows moved by --clock-offset "
                        + Durations.format(source.clockOffsetMs()) : "") + ")");
        boolean csv = source.type() == ServerSource.Type.CSV;
        int incomplete = 0;
        for (ServerMetricsPayload.SeriesRecord s : payload.getSeries()) {
            CollectionStatus status = s.collectionStatus();
            // No source id: a collection has one (C8), so it would say the same word on every line.
            // A catalogue metric is named as the file names it, component/metric, not by the
            // PromQL the catalogue resolved it to; a custom query as component/query. A series is
            // a component's AND a query's (two components may ask the same one), and by the query
            // alone two components printed the same line twice (SF25).
            String name = s.getComponent() == null ? s.getResolvedQuery()
                    : s.getComponent() + "/" + (s.getMetric() != null ? s.getMetric() : s.getResolvedQuery());
            // "coverage=NN%" stays one token to grep for; "of the collected window" says which
            // window it is of, as every other surface that shows coverage does (D28).
            println(out, "wir-collect: " + status.wireName() + " " + name
                    + (s.getCoverage() != null ? " coverage=" + Math.round(s.getCoverage() * 100)
                            + "% of the collected window" : "")
                    + (s.getError() != null ? " - " + s.getError() : ""));
            String padded = paddingWarning(s);
            if (padded != null) {
                println(out, "wir-collect: WARNING " + name + ": " + padded);
            }
            // A file's rows are at the interval it was exported at, which the step must match.
            if (csv) {
                String key = s.getMetric() != null ? s.getMetric() : s.getResolvedQuery();
                int blank = s.getComponent() != null ? csvReader.blankRows(source, window, s.getComponent(), key) : 0;
                long rowSpacing = blank > 0 ? csvReader.rowSpacingMillis(source, window, s.getComponent(), key) : 0;
                for (String line : spacingLines(name, s, blank, rowSpacing, source.stepMillis())) {
                    println(out, "wir-collect: " + line);
                }
            }
            if (!status.judgeable()) {
                incomplete++;
            }
        }
        for (String line : allEmptyComponentLines(config, payload)) {
            println(out, "wir-collect: WARNING " + line);
        }
        println(out, "wir-collect: wrote " + outFile.getAbsolutePath() + " - run " + runId + ", collection "
                + payload.getCollectionId() + ", " + payload.getSeries().size() + " series, "
                + incomplete + " not judgeable"
                + (incomplete > 0 ? " (recorded in the payload; the report decides what they mean)" : "")
                + (payload.getSeries().isEmpty() ? emptyPayloadClause(config) : ""));
        return 0;
    }

    /**
     * One line per catalogue component every one of whose series came back EMPTY. Each series
     * already says EMPTY on its own line; when all of a component's do, the cause is almost never
     * the metrics - it is the component: a {@code match} that selects nothing in this source, or an
     * exporter nobody scrapes. Said once, about the component, so it is not read as N unrelated gaps.
     */
    static List<String> allEmptyComponentLines(ServerMetricsConfig config, ServerMetricsPayload payload) {
        Map<String, int[]> byComponent = new LinkedHashMap<>();   // {series, empty}
        for (ServerMetricsPayload.SeriesRecord s : payload.getSeries()) {
            if (s.getComponent() == null || s.getMetric() == null) {
                continue;   // a custom query says its own; only catalogue metrics share a cause
            }
            int[] n = byComponent.computeIfAbsent(s.getComponent(), k -> new int[2]);
            n[0]++;
            if (s.collectionStatus() == CollectionStatus.EMPTY) {
                n[1]++;
            }
        }
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, int[]> e : byComponent.entrySet()) {
            int[] n = e.getValue();
            if (n[0] == 0 || n[1] != n[0]) {
                continue;
            }
            ServerGate gate = config.gate(e.getKey());
            String match = gate == null ? null : gate.match();
            lines.add("component " + e.getKey() + ": every catalogue metric it asks for (" + n[0] + ") came back empty - "
                    + (match != null && !match.trim().isEmpty()
                            ? "its match (" + match + ") selects nothing in this source"
                            : "this source has none of its " + (gate == null ? "" : gate.type() + " ") + "series")
                    + ", or its exporter is not collected");
        }
        return lines;
    }

    /**
     * Why a written payload holds no series, for the summary line - an empty payload must not read
     * as success. An enabled assert's series is recorded whatever came back, so only a file with no
     * enabled assert that has a query gets here: every assert disabled, or none written yet.
     * Catalogue metrics are kept only when they returned data, so with a catalogue component the
     * source held none of them; without one nothing was asked at all.
     */
    static String emptyPayloadClause(ServerMetricsConfig config) {
        return declaresCatalogueComponent(config)
                ? " - the source returned none of the declared components' catalogue metrics"
                : " - no enabled server assert has a query to collect, so nothing was asked for";
    }

    /**
     * The invisible-padding WARNING for a series whose resolved query starts or ends with a
     * character String.trim leaves (Q2), or {@code null}. Said here, at step 2, because this is
     * the console read right after the query is written, and the series line alone cannot say why
     * it came back EMPTY. It is also the one place that sees a query padded by an
     * {@code ${env.*}} value: the report checks only what the file wrote.
     *
     * <p>Not for a series that {@linkplain #returnedPoints returned points}: the source answered
     * the text as written - a CSV cell padded the same way, or a visible character that ends in a
     * variation selector or a tag, as an emoji or a flag does (F8 review) - so "no series will
     * match it - delete it" would be false beside the OK line above it, and following it would
     * break the match.
     */
    static String paddingWarning(ServerMetricsPayload.SeriesRecord s) {
        if (returnedPoints(s.collectionStatus())) {
            return null;
        }
        String padded = KpiUsability.invisiblePaddingText(s.getResolvedQuery());
        if (padded == null) {
            return null;
        }
        boolean written = KpiUsability.invisiblePaddingText(s.getQuery()) != null;
        return padded + (written ? "" : " (it comes from a value " + s.getQuery() + " interpolates)");
    }

    /**
     * Whether the source returned points for the query as asked: OK, PARTIAL, or INSUFFICIENT
     * (points, too few of them). EMPTY, FAILED and NOT_REQUESTED returned none. The page's Validate
     * query reads the same three (report.js {@code returnedPoints}).
     */
    static boolean returnedPoints(CollectionStatus status) {
        return status == CollectionStatus.OK || status == CollectionStatus.PARTIAL
                || status == CollectionStatus.INSUFFICIENT;
    }

    /**
     * The line for a CSV series whose points are further apart than {@code --step} can count, or
     * {@code null}: its spacing ({@link Coverage#spacingMillis}, the largest over its instances) more
     * than 1.5 x the step, or more than 1% above it when the series came back INSUFFICIENT. Coverage
     * counts one slot per step, so a Grafana export at the panel's 60 s interval read at the default
     * 15 s covers a quarter of the slots at most and reads LOW_COVERAGE - a correct export judged on
     * nothing, for a reason no other line gives. Within 1.5 x a slot in every few is missed, which is
     * said only when it cost the series its coverage floor: 20 s rows, a common panel interval, in
     * 15 s slots cover three in four, under the default 0.9. A spacing at or finer than the step is
     * never short of points: a series short of the floor there is missing data, not a coarse export.
     */
    static String spacingWarning(String name, ServerMetricsPayload.SeriesRecord s, long stepMillis) {
        if (stepMillis <= 0 || s.getInstances() == null) {
            return null;
        }
        long spacing = Coverage.spacingMillis(s.getInstances());
        return tooCoarse(spacing, stepMillis, s) ? spacingSentence(name, spacing, stepMillis) : null;
    }

    /**
     * {@link #spacingWarning}'s rule: more than 1.5 x the step, or more than 1% above it when that cost
     * the series its floor. Within 1% is the step itself, timestamps jittered by a few ms: a raw 15 s
     * export reads 15002ms apart, which loses no slot, and an outage that left it short of its floor
     * was advised "pass --step 15.002s".
     */
    private static boolean tooCoarse(long spacing, long stepMillis, ServerMetricsPayload.SeriesRecord s) {
        return spacing * 2 > stepMillis * 3
                || spacing * 100 > stepMillis * 101 && s.collectionStatus() == CollectionStatus.INSUFFICIENT;
    }

    private static String spacingSentence(String name, long spacing, long stepMillis) {
        return name + " points are " + stepText(spacing) + " apart but --step is " + stepText(stepMillis)
                + " - coverage counts " + stepText(stepMillis) + " slots; pass --step " + stepText(spacing);
    }

    /**
     * The spacing lines for one CSV series: its {@link #spacingWarning}, and beside it a line for its
     * rows with no value. A series with {@code blank} such rows has gaps the export wrote, which a
     * coarser --step would read as covered; so where its rows - a blank one included,
     * {@code rowSpacing} apart - are not too coarse for the step by {@link #spacingWarning}'s own rule,
     * the blanks made the wide gaps and only they are said, as gaps and not a coarser interval. Where
     * the rows themselves are, both are said: the interval the rows are at, and the blanks beside it.
     */
    static List<String> spacingLines(String name, ServerMetricsPayload.SeriesRecord s, int blank, long rowSpacing,
                                     long stepMillis) {
        List<String> lines = new ArrayList<>();
        String spacing = spacingWarning(name, s, stepMillis);
        if (spacing == null) {
            return lines;
        }
        // The rows' own spacing by spacingWarning's rule: 20 s rows in 15 s slots that left the series
        // INSUFFICIENT are a coarser interval, blanks or not.
        boolean rowsCoarse = tooCoarse(rowSpacing, stepMillis, s);
        if (blank == 0) {
            lines.add(spacing);
            return lines;
        }
        if (rowsCoarse) {
            lines.add(spacingSentence(name, rowSpacing, stepMillis));
        }
        lines.add(name + " has " + blank + " row" + (blank == 1 ? "" : "s") + " with no value (an empty cell or NaN)"
                + " - gaps in the export" + (rowsCoarse ? "" : ", not a coarser interval"));
        return lines;
    }

    /** A duration as {@code --step} takes it, in seconds when it is whole ones: 60s, not 1m. */
    private static String stepText(long millis) {
        return millis % 1000 == 0 ? (millis / 1000) + "s" : Durations.format(millis);
    }

    /**
     * "N columns not mapped to a component and metric: "h1", "h2" ..." for a wide file's columns
     * that neither the {@code component/metric[/instance]} header nor a {@code --columns} map named,
     * or {@code null} when every column named a series. Headers are free text, commas and all, so
     * each is quoted; past {@link #LISTED} the rest are counted.
     */
    static String unmappedColumnsLine(List<String> headers) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        return headers.size() + (headers.size() == 1 ? " column" : " columns")
                + " not mapped to a component and metric: " + quotedList(headers);
    }

    /**
     * "N columns name a component and metric the annotations do not ask for: ..." for a wide file's
     * columns that the header convention or the map did name, as a series the collection never asked
     * for ({@link CsvSource#columnsNotAskedFor}), or {@code null} when there are none. Quoted and cut
     * short as {@link #unmappedColumnsLine} is.
     */
    static String unaskedColumnsLine(List<String> headers) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        return headers.size() + (headers.size() == 1 ? " column names" : " columns name")
                + " a component and metric the annotations do not ask for: " + quotedList(headers);
    }

    /**
     * "N series in the file are not asked for by the annotations: ..." for a long file's
     * component/metric pairs nothing asked for ({@link CsvSource#seriesNotAskedFor}), or null.
     */
    static String unaskedSeriesLine(List<String> series) {
        if (series == null || series.isEmpty()) {
            return null;
        }
        return series.size() + (series.size() == 1 ? " series" : " series") + " in the file "
                + (series.size() == 1 ? "is" : "are") + " not asked for by the annotations (component/metric"
                + " must match exactly): " + quotedList(series);
    }

    /** The line for {@code --columns} entries naming a column the export does not have: they map nothing. */
    static String mapColumnsLine(String mapName, String csvName, List<String> columns) {
        boolean one = columns.size() == 1;
        return mapName + " names " + columns.size() + (one ? " column that " : " columns that ") + csvName
                + " does not have, so " + (one ? "it maps" : "they map") + " nothing: " + quotedList(columns);
    }

    private static String quotedList(List<String> items) {
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(items.size(), LISTED);
        for (int i = 0; i < shown; i++) {
            sb.append(i > 0 ? ", " : "").append('"').append(items.get(i)).append('"');
        }
        if (items.size() > shown) {
            sb.append(" and ").append(items.size() - shown).append(" more");
        }
        return sb.toString();
    }

    /**
     * The {@code --help} text. ASCII, like every console line here. It carries the plain
     * {@code java -cp} command because a user may have only the JAR (an older release attached
     * no wrapper script); {@code jarName} is the file this class was loaded from when that is the
     * plugin JAR, so the line can be copied as printed.
     */
    static String help(String jarName) {
        return USAGE + "\n"
                + "\n"
                + "Collects what a report-annotations.json serverMetrics block asks for, over the window of\n"
                + "the finished run in --jtl, into one payload file. It is step 2 of 3:\n"
                + "  1. jmeter -n -t plan.jmx -l results.jtl\n"
                + "  2. wir-collect --jtl results.jtl --annotations report-annotations.json --out server-metrics.json\n"
                + "  3. jmeter -g results.jtl -o report -q run.properties\n"
                + "        where run.properties holds webinsight.report.annotations=report-annotations.json\n"
                + "        and webinsight.report.servermetrics=server-metrics.json (forward slashes in paths)\n"
                + "Step 3 is the report that judges the server asserts and decides the exit code.\n"
                + "\n"
                + "Options:\n"
                + "  --jtl FILE            the finished run; its first and last sample are the window\n"
                + "  --annotations FILE    the file that declares serverMetrics: components and server asserts\n"
                + "  --out FILE            where the payload is written, e.g. server-metrics.json\n"
                + "Where the metrics come from - exactly one:\n"
                + "  --prometheus-url URL  a Prometheus HTTP API, e.g. https://prometheus.example/api/v1\n"
                + "  --prometheus-token-env NAME\n"
                + "                        the environment variable holding a bearer token for it\n"
                + "  --recording DIR       recorded Prometheus responses, replayed instead of a live server\n"
                + "  --csv FILE            a CSV export, one row per point: time,component,metric,instance,value\n"
                + "                        time is ISO 8601 with a zone, or epoch milliseconds; metric is a\n"
                + "                        catalogue metric's name, or a custom assert's query\n"
                + "  --from TOOL           with --csv: the file was exported from TOOL - one of\n"
                + "                        " + String.join(", ", Catalogue.TOOLS) + " - and its metric column\n"
                + "                        holds TOOL's names for the catalogue's metrics. A value read under\n"
                + "                        TOOL's name is converted to the catalogue's unit\n"
                + "  --layout long|wide    with --csv: long (the default) is one row per point, as above; wide\n"
                + "                        is a time column and one column per series - Grafana's panel\n"
                + "                        Inspect > Data > Download CSV with Series joined by time and\n"
                + "                        Formatted data off. Name each column component/metric or\n"
                + "                        component/metric/instance (the panel's legend), or map it with\n"
                + "                        --columns; a column neither names is ignored and listed. Without\n"
                + "                        --layout, a header of Time and component/metric columns is read\n"
                + "                        as wide, and the console says so\n"
                + "  --columns MAP         with --layout wide: a CSV of column,component,metric,instance that\n"
                + "                        names each export column by its exact header\n"
                + "  --csv-columns SPEC    with a long --csv: the file's names for the columns, as key=column\n"
                + "                        pairs - time, component, metric, instance, value; a key not given\n"
                + "                        keeps its own name. metric=_measurement+_field joins two columns as\n"
                + "                        <measurement>.<field>. InfluxDB 2:\n"
                + "                        time=_time,value=_value,metric=_measurement+_field,instance=host\n"
                + "                        (its # annotation lines and blocks are read as they come)\n"
                + "  --component ID        with a long --csv: every row belongs to component ID, for a file\n"
                + "                        with no component column (InfluxDB, Dynatrace). For a file whose\n"
                + "                        asserts are all on ID: any other component's find no rows\n"
                + "  --time-zone ZONE      with --csv: the zone of a time written with none, e.g. Grafana's\n"
                + "                        Formatted data - an IANA id (Europe/Berlin), UTC, or an offset\n"
                + "                        (+03:00). Without it such a time is refused, never guessed\n"
                + "  --step DURATION       the resolution asked for and the coverage grid (default: with --csv,\n"
                + "                        the interval measured from the file's rows, printed; else 15s)\n"
                + "  --clock-offset DURATION\n"
                + "                        added to every timestamp, e.g. -30s when the servers' clocks run\n"
                + "                        30 s ahead of the load generator (default 0)\n"
                + "Optional:\n"
                + "  --env NAME            the serverMetrics.environments entry to resolve ${env.*} from\n"
                + "                        (default: the file's defaultEnv)\n"
                + "  --test-name NAME      the same text as -Jwebinsight.report.title when step 3 sets one;\n"
                + "                        the run's identity hashes it (default: \"" + DEFAULT_TEST_NAME + "\")\n"
                + "  --rampup MILLIS       leave the first MILLIS of the run out of the judged window;\n"
                + "                        the same number as -Jwebinsight.report.rampup\n"
                + "  --max-wait DURATION   how long a source may take to settle (default 10m)\n"
                + "  --no-wait             take what the sources hold now, without waiting\n"
                + "  --collected-at TIME   the time to stamp the collection with (ISO 8601); jmeter -g\n"
                + "                        passes the CSV's own time, so a rebuild keeps its collectionId\n"
                + "  --help, -h            this text\n"
                + "\n"
                + "Exit codes:\n"
                + "  0  the payload was written, whatever each series' status. A source that failed or a\n"
                + "     query that matched nothing is recorded in it, and step 3 decides what that means\n"
                + "     (failonerror).\n"
                + "  1  nothing was written: a usage, input or environment fault, or a file with nothing\n"
                + "     to collect (no server assert, and no component of type "
                + String.join(" or ", Catalogue.builtIn().types().keySet()) + ").\n"
                + "\n"
                + "Without the wrapper scripts, the same on Windows, macOS and Linux - the plugin JAR runs\n"
                + "the collector, finding JMeter from its place in <JMETER_HOME>/lib/ext:\n"
                + "    java -jar <JMETER_HOME>/lib/ext/" + jarName + " --jtl results.jtl ...\n";
    }

    /** Whether any declared component has a type from the built-in catalogue. */
    static boolean declaresCatalogueComponent(ServerMetricsConfig config) {
        for (ServerGate component : config.gates().values()) {
            if (component.type() != null && Catalogue.builtIn().type(component.type()) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * "postgres, kafka, redis, host-linux, aws-ec2, oracle, influxdb2, influxdb3, jvm, http-service,
     * kubernetes-pod, mysql": the types a component may name to be collected without an assert.
     */
    private static String catalogueTypes() {
        return String.join(", ", Catalogue.builtIn().types().keySet());
    }

    /** Placeholder for {@link #help} when this class was not loaded from the plugin JAR. */
    static final String JAR_PLACEHOLDER = "jmeter-web-insight-report-X.Y.Z.jar";

    /** The plugin JAR's file name as it sits in {@code lib/ext}, or {@link #JAR_PLACEHOLDER}. */
    static String pluginJarName() {
        try {
            java.security.CodeSource source = WirCollect.class.getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                String path = source.getLocation().getPath();
                String name = path.substring(path.lastIndexOf('/') + 1);
                if (name.matches("jmeter-web-insight-report-[0-9A-Za-z.\\-]+\\.jar")) {
                    return name;
                }
            }
        } catch (RuntimeException e) {
            // A security manager may refuse getProtectionDomain; the placeholder is still a valid help text.
        }
        return JAR_PLACEHOLDER;
    }

    /**
     * What the {@code -g} exporter names a run: {@code config.getReportTitle()}, whose default is
     * this literal (ReportConfiguration.java). {@code RunIdentity} hashes the name, so the two
     * must agree or the payload is refused as another run's — and when step 3 sets
     * {@code -Jwebinsight.report.title}, step 2 must pass the same text as {@code --test-name}.
     */
    static final String DEFAULT_TEST_NAME = "JMeter Web Insight Report";

    /**
     * Whether {@code args} asks for the help: a {@code --help} or {@code -h} standing where an
     * option can, never one that is an option's value. It used to match anywhere, so
     * {@code --test-name -h} printed the help and exited 0 with nothing written, and 0 means the
     * payload was written. Answered before {@link #parseArgs}, so the help still wins over an
     * unknown option or a missing one; values are skipped by the parser's own rule,
     * {@link #consumesNext}.
     */
    static boolean asksForHelp(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--help") || args[i].equals("-h")) {
                return true;
            }
            if (consumesNext(args[i])) {
                i++;
            }
        }
        return false;
    }

    /**
     * Whether {@code arg}, standing where an option can, takes the NEXT argument as its value: a
     * known option other than {@code --no-wait}, written without {@code =value}. The one rule
     * {@link #parseArgs} reads values by and {@link #asksForHelp} skips them by, so the two cannot
     * disagree about what is a value. An unknown option takes nothing here; the parser refuses it.
     */
    private static boolean consumesNext(String arg) {
        if (!arg.startsWith("--")) {
            return false;
        }
        String key = arg.substring(2);
        return key.indexOf('=') <= 0 && OPTIONS.contains(key) && !key.equals("no-wait");
    }

    /** One console line, ASCII (see {@link #ascii}). Every line this class prints goes through here. */
    private static void println(PrintStream stream, String line) {
        stream.println(ascii(line));
    }

    /**
     * {@code text} as ASCII, for the console. What is printed here is mostly written elsewhere -
     * the mapper's findings, the collector's and the sources' errors - for the page and the payload,
     * which are UTF-8, so it carries typography (an em dash, an arrow, a {@code >=} sign); and a query
     * or a path from the user's file may carry anything. The typography gets its ASCII spelling;
     * anything else becomes its Java escape (a backslash, {@code u} and four hex digits: an e-acute
     * is {@code u00e9}), which says exactly what was there and still greps. The payload and the
     * report keep the original text.
     *
     * <p>Control characters are escaped too (SF25): a tab as backslash-t, a line feed as
     * backslash-n, a carriage return as backslash-r, and any other one (DEL included) as its Java
     * escape. A multi-line PromQL query - which the U7 trim leaves alone inside - printed a raw line
     * break and split one outcome over two lines of a console that promises one line per outcome.
     */
    static String ascii(String text) {
        StringBuilder sb = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x20 && c < 0x7f) {
                if (sb != null) {
                    sb.append(c);
                }
                continue;
            }
            if (sb == null) {
                sb = new StringBuilder(text.length() + 16).append(text, 0, i);
            }
            sb.append(asciiFor(c));
        }
        return sb == null ? text : sb.toString();
    }

    /** Code points, not character literals: a no-break space is invisible in a literal. */
    private static String asciiFor(char c) {
        switch (c) {
            case 0x09:
                return "\\t";   // tab
            case 0x0a:
                return "\\n";   // line feed
            case 0x0d:
                return "\\r";   // carriage return
            case 0x2014: case 0x2013: case 0x2012: case 0x2010: case 0x2011: case 0x2212:
                return "-";     // em, en and figure dash, hyphens, minus sign
            case 0x00b7: case 0x2022:
                return "|";     // middle dot, bullet: the criteria separator ("warn > 650 ms  |  fail > 800 ms")
            case 0x2192:
                return "->";    // rightwards arrow
            case 0x21d2:
                return "=>";    // rightwards double arrow
            case 0x2265:
                return ">=";
            case 0x2264:
                return "<=";
            case 0x2026:
                return "...";   // ellipsis
            case 0x2018: case 0x2019:
                return "'";     // single curly quotes
            case 0x201c: case 0x201d:
                return "\"";    // double curly quotes
            // No case for U+00A0, the no-break space: it is the invisible character a pasted query
            // most often carries, and spelt as a plain space it read exactly like the padding a
            // query is trimmed of - an EMPTY series with no visible cause. It gets its escape.
            case 0x00d7:
                return "x";     // multiplication sign
            default:
                return String.format("\\u%04x", (int) c);
        }
    }

    /**
     * The payload as written: indented, with LF line endings on every OS. Jackson's default
     * pretty-printer ends a line with the platform's separator, so the same collection was a
     * different file on Windows (CRLF) than anywhere else, and a committed payload read as edited
     * until git normalised it.
     */
    static String payloadText(ServerMetricsPayload payload) throws com.fasterxml.jackson.core.JsonProcessingException {
        com.fasterxml.jackson.core.util.DefaultPrettyPrinter pretty =
                new com.fasterxml.jackson.core.util.DefaultPrettyPrinter();
        // Objects only: arrays keep Jackson's default single-line layout ("[ {", points inline),
        // which is the layout every committed payload already has.
        pretty.indentObjectsWith(new com.fasterxml.jackson.core.util.DefaultIndenter("  ", "\n"));
        return new ObjectMapper().writer(pretty).writeValueAsString(payload);
    }

    /** The fewest points a measured step may leave in the run's window to be adopted (F5). */
    static final int MIN_MEASURED_POINTS = 10;

    /**
     * Decision 12, "measure, don't ask": a CSV's layout and step, when no flag gave them, from the
     * file itself, put into {@code opts} as if they had been passed, and one line each saying so. The
     * wide layout from the header ({@link CsvShape}), only where nothing else names columns (no
     * --csv-columns, no --component); then the step from {@code reader}'s own index of the file under
     * every flag given ({@link CsvSource#measuredStepMillis}), which the collection reuses, over the
     * series {@code asked} says the collection will read from the source those flags make
     * ({@link #seriesMeasured}; null: every series in the file). A step
     * coarser than the default that would leave fewer than {@value #MIN_MEASURED_POINTS} points in
     * {@code window} is not adopted - a few points would judge the whole run at full coverage - and
     * that is said instead. Nothing measured, a file the reader refuses included, says nothing. An
     * explicit flag is never overridden.
     */
    static List<String> measureCsv(Map<String, String> opts, CsvSource reader, ServerMetricsPayload.Window window,
                                   Function<ServerSource, List<SeriesSource.Request>> asked) {
        List<String> lines = new ArrayList<>();
        if (!opts.containsKey("layout") && !opts.containsKey("csv-columns") && !opts.containsKey("component")
                && CsvShape.looksWide(new File(opts.get("csv")))) {
            opts.put("layout", "wide");
            lines.add("layout wide, read from the header (Time, then component/metric columns) - pass --layout long"
                    + " to read it otherwise");
        }
        if (opts.containsKey("step")) {
            return lines;
        }
        long step;
        try {
            ServerSource source = collectionSource(opts);
            step = reader.measuredStepMillis(source, window, asked == null ? null : asked.apply(source));
        } catch (RuntimeException unmeasurable) {
            return lines;   // the collection says what is wrong with the file
        }
        if (step <= 0) {
            return lines;
        }
        long run = window.getEnd() - window.getStart();
        if (step > ServerSource.DEFAULT_STEP_MILLIS && run / step + 1 < MIN_MEASURED_POINTS) {
            lines.add("step " + Durations.format(step) + " measured from the file's rows is too coarse for this "
                    + Durations.format(Math.round(run / 1000.0) * 1000) + " run (fewer than " + MIN_MEASURED_POINTS + " points) - collecting at "
                    + Durations.format(ServerSource.DEFAULT_STEP_MILLIS) + "; pass --step to collect at another");
            return lines;
        }
        opts.put("step", Durations.format(step));
        lines.add("step " + Durations.format(step) + ", measured from the file's rows - pass --step"
                + " to collect at another");
        return lines;
    }

    /**
     * The series a measured step is taken over: those the enabled server asserts read, asked of
     * {@code source} as the collection asks ({@link Collector#assertRequests}) - so a coarse series
     * in the file that no assert reads cannot set the step every assert is counted in. A file with
     * no assert yet, only catalogue components, is measured over their catalogue metrics, the only
     * series its collection reads ({@link Collector#catalogueRequests}).
     */
    static List<SeriesSource.Request> seriesMeasured(Collector planner, ServerMetricsConfig config, String env,
                                                     Collector.Run run, ServerSource source) {
        List<SeriesSource.Request> asserts = planner.assertRequests(config, env, run, source);
        return asserts.isEmpty() ? planner.catalogueRequests(config, env, run, source) : asserts;
    }

    /** The collection's one source, from the flags parseArgs has already checked. */
    static ServerSource collectionSource(Map<String, String> opts) {
        long step = opts.containsKey("step") ? Durations.parseMillis(opts.get("step"))
                : ServerSource.DEFAULT_STEP_MILLIS;
        long offset = opts.containsKey("clock-offset") ? clockOffsetMillis(opts.get("clock-offset")) : 0;
        if (opts.containsKey("recording")) {
            return ServerSource.file(ServerSource.COLLECTION_ID, opts.get("recording"), step, offset);
        }
        if (opts.containsKey("csv")) {
            return ServerSource.csv(ServerSource.COLLECTION_ID, opts.get("csv"), step, offset, fromTool(opts),
                    layout(opts), opts.containsKey("columns") ? opts.get("columns").trim() : null, timeZone(opts),
                    opts.containsKey("csv-columns") ? CsvColumns.parse(opts.get("csv-columns")).normalised() : null,
                    opts.containsKey("component") ? opts.get("component").trim() : null);
        }
        Map<String, String> auth = new LinkedHashMap<>();
        if (opts.get("prometheus-token-env") != null) {
            auth.put("bearerTokenEnv", opts.get("prometheus-token-env").trim());
        }
        return ServerSource.prometheus(ServerSource.COLLECTION_ID, opts.get("prometheus-url").trim(), step, offset, auth);
    }

    /**
     * {@code --from}: the tool a CSV was exported from, as the catalogue names it - trimmed and in
     * lower case, so {@code Datadog} is {@code datadog} - or null when it was not given (a file in
     * the catalogue's own names). Not a tool {@link Catalogue#TOOLS} lists: null, which
     * {@link #parseArgs} has already refused.
     */
    static String fromTool(Map<String, String> opts) {
        String text = opts.get("from");
        if (text == null) {
            return null;
        }
        String tool = text.trim().toLowerCase(Locale.ROOT);
        return Catalogue.TOOLS.contains(tool) ? tool : null;
    }

    /**
     * {@code --layout}: how the CSV lays its points out, {@link ServerSource.CsvLayout#LONG} when it
     * was not given. Not a layout: null, which {@link #parseArgs} has already refused.
     */
    static ServerSource.CsvLayout layout(Map<String, String> opts) {
        return opts.containsKey("layout") ? ServerSource.CsvLayout.fromKey(opts.get("layout"))
                : ServerSource.CsvLayout.LONG;
    }

    /**
     * {@code --time-zone}: the zone a CSV's zone-less time is in, as java.time reads it - an IANA
     * id, {@code UTC}, an offset such as {@code +03:00} - or null when it was not given, or cannot
     * be read (which {@link #parseArgs} has already refused). Never guessed: without it a zone-less
     * time is refused, because a wrong zone shifts every point of the run by hours, in silence.
     */
    static ZoneId timeZone(Map<String, String> opts) {
        String text = opts.get("time-zone");
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        try {
            return ZoneId.of(text.trim());
        } catch (DateTimeException e) {
            return null;
        }
    }

    /**
     * {@code --clock-offset}: a duration with an optional sign - {@code -30s}, {@code +2500ms},
     * {@code 1m} - added to every timestamp the source returns, so a server whose clock runs 30 s
     * ahead is lined up with the run by {@code -30s}. Null when the text is not one. Shown in the
     * Collection panel, because an offset nobody can see misattributes every spike in silence.
     */
    static Long clockOffsetMillis(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        int sign = 1;
        if (t.startsWith("-") || t.startsWith("+")) {
            sign = t.startsWith("-") ? -1 : 1;
            t = t.substring(1).trim();
        }
        if (t.equals("0")) {
            return 0L;
        }
        Long ms = Durations.parseMillis(t);
        return ms == null || ms < 0 ? null : sign * ms;
    }

    private static long parseMaxWait(String text, PrintStream err) {
        if (text == null) {
            return PrometheusSource.Settle.DEFAULT_MAX_WAIT_MILLIS;
        }
        Long millis = Durations.parseMillis(text);
        if (millis == null || millis < 0) {
            println(err, "wir-collect: --max-wait must be " + Durations.grammar() + "; using the default 10m");
            return PrometheusSource.Settle.DEFAULT_MAX_WAIT_MILLIS;
        }
        return millis;
    }

    static Map<String, String> parseArgs(String[] args) {
        Map<String, String> opts = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument: " + a);
            }
            String key = a.substring(2);
            String inlineValue = null;
            int eq = key.indexOf('=');
            if (eq > 0) {   // --env=TEST is the same as --env TEST
                inlineValue = key.substring(eq + 1);
                key = key.substring(0, eq);
            }
            if (!OPTIONS.contains(key)) {
                throw new IllegalArgumentException("unknown option --" + key);
            }
            if (opts.containsKey(key)) {
                throw new IllegalArgumentException("--" + key + " given twice");
            }
            if (consumesNext(a)) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("--" + key + " needs a value");
                }
                opts.put(key, args[++i]);
            } else {
                // --no-wait takes no value (--no-wait=x is still just --no-wait); otherwise the
                // value was inline: --env=TEST.
                opts.put(key, key.equals("no-wait") ? "true" : inlineValue);
            }
        }
        for (String required : new String[] {"jtl", "annotations", "out"}) {
            if (!opts.containsKey(required)) {
                throw new IllegalArgumentException("--" + required + " is required");
            }
        }
        // Where the metrics come from is the collector's to be told, never the file's (C8).
        boolean url = opts.containsKey("prometheus-url");
        boolean recording = opts.containsKey("recording");
        boolean csv = opts.containsKey("csv");
        List<String> given = new ArrayList<>();
        if (url) given.add("--prometheus-url");
        if (recording) given.add("--recording");
        if (csv) given.add("--csv");
        if (given.size() > 1) {
            throw new IllegalArgumentException(String.join(" and ", given) + " are " + given.size()
                    + " places to fetch from - give one");
        }
        if (given.isEmpty()) {
            throw new IllegalArgumentException("where to fetch the metrics from is required: "
                    + "--prometheus-url URL, --recording DIR, or --csv FILE");
        }
        if (csv && (opts.get("csv") == null || opts.get("csv").trim().isEmpty())) {
            throw new IllegalArgumentException("--csv needs a file, e.g. server-metrics.csv");
        }
        // Only a CSV has a metric column to read by another tool's names: a query language and a
        // recording are asked by the catalogue's query, whatever tool stored the data.
        if (opts.containsKey("from")) {
            if (!csv) {
                throw new IllegalArgumentException("--from goes with --csv");
            }
            String tool = opts.get("from") == null ? "" : opts.get("from").trim();
            if (fromTool(opts) == null) {
                throw new IllegalArgumentException("--from " + (tool.isEmpty() ? "needs a tool"
                        : "'" + tool + "' is not a tool the catalogue names") + " - one of "
                        + String.join(", ", Catalogue.TOOLS));
            }
        }
        // How a CSV is read - its layout, its column map, its zone - means nothing to a query
        // language or a recording, which carry their own times and name their own series.
        if (opts.containsKey("layout")) {
            if (!csv) {
                throw new IllegalArgumentException("--layout goes with --csv");
            }
            String text = opts.get("layout") == null ? "" : opts.get("layout").trim();
            if (layout(opts) == null) {
                throw new IllegalArgumentException("--layout " + (text.isEmpty() ? "needs a layout"
                        : "'" + text + "' is not a layout") + " - one of " + ServerSource.CsvLayout.vocabulary());
            }
        }
        if (opts.containsKey("columns")) {
            // A map of column headers is for a file whose columns are series.
            if (!csv || layout(opts) != ServerSource.CsvLayout.WIDE) {
                // On a long file the slip is the other flag's name: point at it.
                throw new IllegalArgumentException("--columns goes with --layout wide"
                        + (csv ? " - a long file's column names are --csv-columns" : ""));
            }
            if (opts.get("columns") == null || opts.get("columns").trim().isEmpty()) {
                throw new IllegalArgumentException("--columns needs a file, e.g. columns.csv");
            }
        }
        // A long file another tool wrote: its names for the columns, and the one component its rows
        // are in. A wide file's columns are series, each named by its header or the --columns map.
        if (opts.containsKey("csv-columns")) {
            if (!csv) {
                throw new IllegalArgumentException("--csv-columns goes with --csv");
            }
            if (layout(opts) == ServerSource.CsvLayout.WIDE) {
                throw new IllegalArgumentException("--csv-columns goes with the long layout - a wide file's columns"
                        + " are mapped by --columns");
            }
            CsvColumns.parse(opts.get("csv-columns"));   // refuses what it cannot read, naming the key
        }
        if (opts.containsKey("component")) {
            if (!csv) {
                throw new IllegalArgumentException("--component goes with --csv");
            }
            if (layout(opts) == ServerSource.CsvLayout.WIDE) {
                throw new IllegalArgumentException("--component goes with the long layout - a wide file names each"
                        + " column's component in its header or --columns");
            }
            if (opts.get("component") == null || opts.get("component").trim().isEmpty()) {
                throw new IllegalArgumentException("--component needs a component id, e.g. orders-db");
            }
            // One source of truth for a row's component, as the reader holds a file to beside its own column.
            if (opts.containsKey("csv-columns") && CsvColumns.parse(opts.get("csv-columns")).maps("component")) {
                throw new IllegalArgumentException("--component and --csv-columns component= both say which"
                        + " component a row is in - give one");
            }
        }
        if (opts.containsKey("time-zone")) {
            if (!csv) {
                throw new IllegalArgumentException("--time-zone goes with --csv");
            }
            String text = opts.get("time-zone") == null ? "" : opts.get("time-zone").trim();
            if (timeZone(opts) == null) {
                throw new IllegalArgumentException("--time-zone " + (text.isEmpty() ? "needs a zone"
                        : "'" + text + "' is not a zone java.time can read") + " - " + ZONE_GRAMMAR);
            }
        }
        if (opts.containsKey("prometheus-token-env") && !url) {
            throw new IllegalArgumentException("--prometheus-token-env goes with --prometheus-url");
        }
        if (url) {
            String u = opts.get("prometheus-url") == null ? "" : opts.get("prometheus-url").trim();
            if (u.isEmpty()) {
                throw new IllegalArgumentException("--prometheus-url needs a URL, e.g. https://prometheus.example/api/v1");
            }
            // Credentials by reference only, as they always were: the URL is written into the
            // payload and every CI log. The token's variable goes in --prometheus-token-env.
            if (ServerMetricsJson.authorityHasUserInfo(u)) {
                throw new IllegalArgumentException("--prometheus-url carries credentials (user:password@...) - "
                        + "give the token's environment variable with --prometheus-token-env instead");
            }
            String param = ServerMetricsJson.credentialParam(u);
            if (param != null) {
                throw new IllegalArgumentException("--prometheus-url parameter '" + param + "' looks like a "
                        + "credential and must take its value from ${secret.NAME}, an environment variable "
                        + "the collector resolves and masks wherever the URL is written");
            }
        }
        if (opts.containsKey("step")) {
            Long step = opts.get("step") == null ? null : Durations.parseMillis(opts.get("step"));
            if (step == null || step <= 0) {
                throw new IllegalArgumentException("--step must be " + Durations.grammar());
            }
        }
        if (opts.containsKey("clock-offset") && clockOffsetMillis(opts.get("clock-offset")) == null) {
            throw new IllegalArgumentException("--clock-offset must be a signed duration such as -30s, +2500ms or 1m"
                    + " - added to every timestamp the servers report, so -30s lines up servers whose clocks run"
                    + " 30 s ahead of the load generator");
        }
        if (opts.containsKey("rampup")) {
            long rampUp;
            try {
                rampUp = Long.parseLong(opts.get("rampup"));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("--rampup must be a number of milliseconds");
            }
            if (rampUp < 0) {
                // Silently treated as "no ramp-up" before, which judged the ramp-up the author
                // asked to exclude.
                throw new IllegalArgumentException("--rampup must be a number of milliseconds, 0 or more");
            }
        }
        return opts;
    }
}
