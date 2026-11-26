package com.jmeterwebinsightreport;

import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.adapter.k6.K6Results;
import com.jmeterwebinsightreport.core.rundata.RunDataFormat;
import com.jmeterwebinsightreport.report.exporter.ReportRebuild;
import com.jmeterwebinsightreport.report.generator.ExitPolicy;
import com.jmeterwebinsightreport.report.generator.NonZeroExit;
import com.jmeterwebinsightreport.report.generator.PropertyCheck;
import com.jmeterwebinsightreport.report.generator.ReportArtifacts;
import com.jmeterwebinsightreport.report.generator.ReportConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * {@code build-report}: the report built with no JMeter (spec 001, contracts/build-report-cli.md, FR-015).
 *
 * <p>It runs the one rebuild ({@link ReportRebuild}: parse, companion files with the in-process server CSV
 * collection, generate and announce) over run data, with the report's properties from the command line instead of
 * JMeter's, and decides its exit code by the same policy {@code jmeter -g} applies.
 *
 * <pre>
 * java -jar jmeter-web-insight-report-X.jar build-report --run-data run.jsonl.gz --out report/
 *      [--annotations report-annotations.json] [--servermetrics server.csv] [--property key=value]...
 * </pre>
 */
public final class BuildReport {

    static final String USAGE = "usage: build-report (--run-data <run-data.jsonl.gz>"
            + " | --from k6 --k6-results <results.csv.gz> [--k6-summary <summary.json>] [--write-run-data])"
            + " --out <folder>"
            + " [--annotations FILE] [--servermetrics FILE] [--baseline FILE]"
            + " [--property key=value]... [--properties FILE]";
    private static final String MORE = "build-report --help lists every option";

    private BuildReport() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err, System.getenv()));
    }

    /** The whole command, testable: returns the exit code instead of calling {@code System.exit}. */
    public static int run(String[] args, PrintStream out, PrintStream err, Map<String, String> environment) {
        if (args.length == 1 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
            out.print(help());
            return 0;
        }
        Options o;
        try {
            o = Options.parse(args);
        } catch (IllegalArgumentException e) {
            err.println("build-report: " + e.getMessage());
            err.println(USAGE);
            err.println(MORE);
            return 1;
        }
        File given = o.k6Results != null ? o.k6Results : o.runData;
        if (!given.isFile()) {
            err.println("build-report: " + (o.k6Results != null ? "k6's results" : "the run data")
                    + " does not exist: " + given.getAbsolutePath());
            return 1;
        }
        if (o.k6Summary != null && !o.k6Summary.isFile()) {
            err.println("build-report: k6's summary does not exist: " + o.k6Summary.getAbsolutePath());
            return 1;
        }
        Map<String, String> settings;
        try {
            settings = o.settings();
        } catch (IOException e) {
            err.println("build-report: the properties file could not be read: " + e.getMessage());
            return 1;
        }
        Function<String, String> props = settings::get;
        Consumer<String> warn = w -> {
            if (w != null) {
                err.println("WARNING " + w);
            }
        };

        String propertyError = PropertyCheck.emit(PropertyCheck.check(settings), w -> err.println("WARNING " + w));
        boolean failOnError = ExitPolicy.failOnError(props.apply(NonZeroExit.FAIL_ON_ERROR), warn);
        boolean failOnServerError = ExitPolicy.failOnServerError(props.apply(NonZeroExit.FAIL_ON_SERVER_ERROR), warn);
        boolean failOnAssert = ExitPolicy.failOnAssert(props.apply(NonZeroExit.FAIL_ON_ASSERT), warn);
        int code = 0;
        if (propertyError != null && failOnError) {
            code = 1;
        }

        try {
            if (!o.out.isDirectory() && !o.out.mkdirs()) {
                err.println("build-report: the output folder could not be created: " + o.out.getAbsolutePath());
                return 1;
            }
            ReportConfiguration config = ReportRebuild.configuration(o.out, props);
            if (o.k6Results != null) {
                // k6's own files through the report's k6 adapter (decision 8), as run data the report then reads
                o.runData = k6RunData(o, config, props, out);
            }
            ReportData data = ReportRebuild.parseRunData(o.runData, config, props);
            out.println("build-report: " + toolLine(data) + ", run data format " + RunDataFormat.VERSION
                    + " reader");
            ReportRebuild.readCompanionFiles(config, o.out, o.runData, data, props, out, err, environment,
                    ReportRebuild.Route.BUILD_REPORT);
            ReportArtifacts artifacts = ReportRebuild.generateAndAnnounce(config, data,
                    o.runData.getPath().replace(File.separatorChar, '/'), out, null);
            String client = artifacts.clientConfigMarker();
            if (client != null) {
                err.println(client);
                if (failOnError) {
                    code = 1;
                }
            }
            String server = artifacts.serverConfigMarker(failOnServerError);
            if (server != null) {
                err.println(server);
                if (failOnServerError) {
                    code = 1;
                }
            }
            if (artifacts.anyFailed()) {
                err.println("WEB-INSIGHT-REPORT: INCOMPLETE - " + artifacts.incompleteReason());
                if (failOnError) {
                    code = 1;
                }
            }
            if (failOnAssert && artifacts.anyGateFailsBuild()) {
                err.println("WEB-INSIGHT-REPORT: ASSERT FAIL - " + artifacts.verdictLine());
                code = 1;
            }
            return code;
        } catch (Exception e) {
            err.println(ReportArtifacts.failureLine(e));
            err.println("build-report: " + ReportArtifacts.describe(e));
            return 1;
        }
    }

    /**
     * k6's results as run data: beside the report when {@code --write-run-data} asks, else a scratch file. The test
     * name is the report title ({@code webinsight.report.title}, else the default title), which the run's identity
     * hashes: {@code wir-collect --k6-results} must be given the same as {@code --test-name}.
     */
    private static File k6RunData(Options o, ReportConfiguration config, Function<String, String> props,
                                  PrintStream out) throws IOException {
        String title = props.apply("webinsight.report.title");
        String testName = title != null && !title.trim().isEmpty() ? title : config.getReportTitle();
        File target;
        if (o.writeRunData) {
            String html = props.apply("webinsight.report.filename");
            String base = html == null || html.trim().isEmpty() ? "web-insight-report"
                    : (html.endsWith(".html") ? html.substring(0, html.length() - 5) : html);
            target = new File(o.out, base + RunDataFormat.FILE_SUFFIX);
        } else {
            target = File.createTempFile("wir-k6-run-data-", ".jsonl.gz");
            target.deleteOnExit();
        }
        K6Results.Result r = K6Results.toRunData(o.k6Results, o.k6Summary, testName, target);
        out.println("build-report: k6 results read: " + r.requests + " requests, " + r.passes + " group passes"
                + (r.cutOffPasses > 0 ? " (" + r.cutOffPasses + " cut off by the end of the test)" : "")
                + (r.wholeSeconds ? "; whole-second times" : "")
                + (r.withoutUsers ? "; no vu and iter, so request rows only" : ""));
        if (o.writeRunData) {
            out.println("Run data: " + target.getPath());
        }
        return target;
    }

    private static String toolLine(ReportData data) {
        String tool = data.getMetadata().getToolName();
        String version = tool == null ? data.getMetadata().getJmeterVersion() : data.getMetadata().getToolVersion();
        return (tool == null ? "jmeter" : tool) + (version == null ? "" : " " + version);
    }

    static String help() {
        return "build-report - the Web Insight Report built with no JMeter (spec 001)\n"
                + "\n"
                + USAGE + "\n"
                + "\n"
                + "Options:\n"
                + "  --run-data FILE       the run's samples as run data (.jsonl or .jsonl.gz), of any tool\n"
                + "  --from k6             or k6's own files, through the report's k6 adapter:\n"
                + "  --k6-results FILE     k6's --out csv file (.csv or .csv.gz), written with\n"
                + "                        K6_CSV_TIME_FORMAT=unix_milli and vu,iter in K6_SYSTEM_TAGS\n"
                + "  --k6-summary FILE     k6's end-of-test summary (handleSummary's data): thresholds, counters\n"
                + "  --write-run-data      with --from k6, also keep the run data beside the report\n"
                + "  --out FOLDER          where the report is written; created if missing\n"
                + "  --annotations FILE    the test's asserts (else auto-detected in --out, as on jmeter -g)\n"
                + "  --servermetrics FILE  server data: a payload wir-collect wrote, or a CSV read in-process\n"
                + "  --baseline FILE       the Compare tab's baseline: a JMeter results file, or run data of any\n"
                + "                        tool (.jsonl or .jsonl.gz), read by its content\n"
                + "  --property KEY=VALUE  any webinsight.* setting; repeatable; wins over --properties\n"
                + "  --properties FILE     a properties file of webinsight.* settings\n"
                + "\n"
                + "The title is the run data's test name: its identity (runId) hashes it, so a different\n"
                + "webinsight.report.title is refused.\n"
                + "\n"
                + "Exit codes, as jmeter -g: 0 when the report was written, whatever its verdict; 1 when\n"
                + "nothing could be written, or under webinsight.report.failonerror (default true) for a\n"
                + "configuration error, webinsight.report.server.failonerror for a server one, and\n"
                + "webinsight.report.failonassert for a FAIL.\n"
                + "\n"
                + "With no JMeter installed, unzip jmeter-web-insight-report-<v>-standalone.zip and run the JAR\n"
                + "from there: its lib/ holds what the report needs.\n";
    }

    /** The command line, read. */
    static final class Options {
        File runData;
        File k6Results;
        File k6Summary;
        String from;
        boolean writeRunData;
        File out;
        File annotations;
        File serverMetrics;
        File baseline;
        File propertiesFile;
        final Map<String, String> properties = new LinkedHashMap<>();

        static Options parse(String[] args) {
            Options o = new Options();
            List<String> seen = new ArrayList<>();
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                if (!a.startsWith("--")) {
                    throw new IllegalArgumentException("unexpected argument: " + a);
                }
                String key = a.substring(2);
                if ("write-run-data".equals(key)) {
                    o.writeRunData = true;
                    continue;
                }
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("--" + key + " needs a value");
                }
                String value = args[++i];
                if (!"property".equals(key)) {
                    if (seen.contains(key)) {
                        throw new IllegalArgumentException("--" + key + " given twice");
                    }
                    seen.add(key);
                }
                switch (key) {
                    case "run-data": o.runData = new File(value); break;
                    case "from": o.from = value; break;
                    case "k6-results": o.k6Results = new File(value); break;
                    case "k6-summary": o.k6Summary = new File(value); break;
                    case "out": o.out = new File(value); break;
                    case "annotations": o.annotations = new File(value); break;
                    case "servermetrics": o.serverMetrics = new File(value); break;
                    case "baseline": o.baseline = new File(value); break;
                    case "properties": o.propertiesFile = new File(value); break;
                    case "property": {
                        int eq = value.indexOf('=');
                        if (eq <= 0) {
                            throw new IllegalArgumentException("--property takes key=value, not " + value);
                        }
                        o.properties.put(value.substring(0, eq).trim(), value.substring(eq + 1));
                        break;
                    }
                    default:
                        throw new IllegalArgumentException("unknown option --" + key);
                }
            }
            boolean k6 = o.from != null || o.k6Results != null;
            if (o.from != null && !"k6".equals(o.from)) {
                throw new IllegalArgumentException("--from takes k6, the one tool this report reads the files of"
                        + " (another tool writes run data: --run-data)");
            }
            if (k6 && (o.from == null || o.k6Results == null)) {
                throw new IllegalArgumentException("k6's files are read with --from k6 --k6-results FILE");
            }
            if (k6 && o.runData != null) {
                throw new IllegalArgumentException("give the run once: --run-data, or --from k6 --k6-results");
            }
            if (!k6 && o.runData == null) {
                throw new IllegalArgumentException("--run-data (or --from k6 --k6-results) is required");
            }
            if (o.writeRunData && !k6) {
                throw new IllegalArgumentException("--write-run-data goes with --from k6: run data is already run data");
            }
            if (o.out == null) {
                throw new IllegalArgumentException("--out is required");
            }
            return o;
        }

        /** The settings: the properties file, then the options that name files, then each --property. */
        Map<String, String> settings() throws IOException {
            Map<String, String> m = new LinkedHashMap<>();
            if (propertiesFile != null) {
                Properties p = new Properties();
                try (Reader r = new InputStreamReader(Files.newInputStream(propertiesFile.toPath()),
                        StandardCharsets.UTF_8)) {
                    p.load(r);
                }
                for (String k : p.stringPropertyNames()) {
                    m.put(k, p.getProperty(k));
                }
            }
            if (annotations != null) {
                m.put("webinsight.report.annotations", annotations.getPath());
            }
            if (serverMetrics != null) {
                m.put("webinsight.report.servermetrics", serverMetrics.getPath());
            }
            if (baseline != null) {
                m.put("webinsight.report.baseline", baseline.getPath());
            }
            m.putAll(properties);
            return m;
        }
    }
}
