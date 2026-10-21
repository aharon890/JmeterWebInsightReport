package com.jmeterwebinsightreport.report.generator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * The {@code webinsight.*} JMeter properties this run received, checked for the mistakes that
 * otherwise pass in silence. Both ingestion paths call it, so one rule answers on both.
 *
 * <p>Every property is read by its exact name, so a property that arrives under any other name is
 * simply never read — and the run then reports, correctly as far as it knows, that nothing was
 * declared. Three ways that happens, each seen in a real run:
 *
 * <ul>
 *   <li><b>PowerShell splits {@code -Jwebinsight.report.x=y} at the first dot</b> (Windows
 *       PowerShell 5.1 hands {@code ["-Jwebinsight", ".report.x=y"]} to a native program). JMeter
 *       then sets one property literally named {@code webinsight}. The annotations and the server
 *       data are lost, the report says nothing was declared, and the run exits 0. An ERROR.</li>
 *   <li><b>A typo in a name</b> ({@code webinsight.report.servermetric}). A WARNING with the
 *       nearest known name.</li>
 *   <li><b>A Windows path in a {@code -q} properties file written with single backslashes.</b>
 *       {@code Properties.load} reads {@code \t}, {@code \f}, {@code \r} and {@code \n} as control
 *       characters, so {@code C:\tests\run} arrives as {@code C:<TAB>ests<CR>un}. A WARNING naming
 *       the fix (forward slashes).</li>
 * </ul>
 */
public final class PropertyCheck {

    /** What one finding is: an ERROR is a client config error; a WARNING is said and nothing else. */
    public enum Severity { ERROR, WARNING }

    /** One problem with the properties, in a sentence that names the fix. */
    public static final class Problem {
        private final Severity severity;
        private final String message;

        Problem(Severity severity, String message) {
            this.severity = severity;
            this.message = message;
        }

        public Severity severity() { return severity; }

        public String message() { return message; }

        @Override
        public String toString() { return severity + ": " + message; }
    }

    /** Every name the plugin reads. A name outside this set (and its prefixes below) is unknown. */
    static final Set<String> KNOWN = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "webinsight.apdex.threshold",
            "webinsight.compare.errorrate.threshold",
            "webinsight.compare.mean.threshold",
            "webinsight.compare.p95.threshold",
            "webinsight.compare.p99.threshold",
            "webinsight.compare.throughput.threshold",
            "webinsight.error.body.maxsize",
            "webinsight.report.annotations",
            "webinsight.report.baseline",
            "webinsight.report.externaldata",
            "webinsight.report.externaldata.threshold",
            "webinsight.report.failonassert",
            "webinsight.report.failonerror",
            "webinsight.report.filename",
            "webinsight.report.junit",
            "webinsight.report.output",
            "webinsight.report.rampup",
            "webinsight.report.resolved.annotations",
            "webinsight.report.resolved.filename",
            "webinsight.report.resolved.title",
            "webinsight.report.server.failonerror",
            "webinsight.report.servermetrics",
            "webinsight.report.title")));

    /** Families whose members are read by prefix: the server-data reader options. */
    static final List<String> KNOWN_PREFIXES = Collections.unmodifiableList(Arrays.asList(
            "webinsight.report.servermetrics."));

    /** Properties whose value is a path, where backslash mangling shows. */
    static final Set<String> PATHS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "webinsight.report.annotations",
            "webinsight.report.baseline",
            "webinsight.report.output",
            "webinsight.report.servermetrics")));

    private PropertyCheck() {
    }

    /** Checks JMeter's property set as it stands. */
    public static List<Problem> check(Properties properties) {
        if (properties == null) {
            return Collections.emptyList();
        }
        java.util.Map<String, String> map = new java.util.TreeMap<>();
        for (String name : properties.stringPropertyNames()) {
            map.put(name, properties.getProperty(name));
        }
        return check(map);
    }

    /** The rule itself, over plain names and values — what the tests drive. */
    public static List<Problem> check(Map<String, String> properties) {
        List<Problem> problems = new ArrayList<>();
        for (Map.Entry<String, String> e : properties.entrySet()) {
            String name = e.getKey();
            String value = e.getValue() == null ? "" : e.getValue();
            if (name.equals("webinsight")) {
                problems.add(new Problem(Severity.ERROR, "JMeter received a property named \"webinsight\" "
                        + "with the value \"" + value + "\". That is what Windows PowerShell makes of an "
                        + "unquoted -Jwebinsight.report." + afterDot(value) + ": it splits the argument at "
                        + "the first dot, so the setting never reached the plugin. Quote each -J argument "
                        + "(\"-Jwebinsight.report.annotations=a.json\"), or put the settings in a properties "
                        + "file and pass -q run.properties"));
                continue;
            }
            if (!name.startsWith("webinsight.")) {
                continue;
            }
            if (!isKnown(name)) {
                String near = nearest(name);
                problems.add(new Problem(Severity.WARNING, "the JMeter property " + name + " is not one the "
                        + "Web Insight plugin reads, so it does nothing"
                        + (near != null ? " — did you mean " + near + "?" : "")));
            }
            if (PATHS.contains(name) && hasControlCharacter(value)) {
                problems.add(new Problem(Severity.WARNING, name + " holds a control character (a tab, "
                        + "form feed or line break): a Windows path written with single backslashes in a "
                        + "properties file is read that way (\\t is a tab, \\f a form feed, \\r and \\n line "
                        + "breaks). Write the path with forward slashes, e.g. C:/runs/checkout/server.csv"));
            }
        }
        return problems;
    }

    /**
     * Prints each problem on stderr — {@code CONFIG ERROR} for an ERROR, {@code WARNING} otherwise —
     * and hands it to {@code log}. Both paths emit through here, so the wording is one.
     *
     * @return the ERRORs' sentences joined, or null when there is none — what {@code failonerror}
     *         acts on, as for any client config error
     */
    public static String emit(List<Problem> problems, java.util.function.Consumer<String> log) {
        StringBuilder errors = new StringBuilder();
        for (Problem p : problems) {
            String line = (p.severity() == Severity.ERROR ? "WEB-INSIGHT-REPORT: CONFIG ERROR — "
                    : "WEB-INSIGHT-REPORT: WARNING — ") + p.message();
            System.err.println(line);
            if (log != null) {
                log.accept(line);
            }
            if (p.severity() == Severity.ERROR) {
                errors.append(errors.length() == 0 ? "" : "; ").append(p.message());
            }
        }
        return errors.length() == 0 ? null : errors.toString();
    }

    static boolean isKnown(String name) {
        if (KNOWN.contains(name)) {
            return true;
        }
        for (String prefix : KNOWN_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** The value's key part: ".report.annotations=a.json" → "annotations=…" for the sentence. */
    private static String afterDot(String value) {
        String v = value.startsWith(".report.") ? value.substring(".report.".length()) : value;
        int eq = v.indexOf('=');
        return eq > 0 ? v.substring(0, eq) + "=..." : "x=...";
    }

    private static boolean hasControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\t' || c == '\f' || c == '\r' || c == '\n') {
                return true;
            }
        }
        return false;
    }

    /** The known name within two edits, case-insensitively, or null. */
    static String nearest(String name) {
        String best = null;
        int bestDistance = 3;
        String lower = name.toLowerCase(Locale.ROOT);
        for (String known : KNOWN) {
            if (known.contains(".resolved.")) {
                continue; // internal, never typed
            }
            int d = distance(lower, known);
            if (d < bestDistance) {
                bestDistance = d;
                best = known;
            }
        }
        return best;
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
