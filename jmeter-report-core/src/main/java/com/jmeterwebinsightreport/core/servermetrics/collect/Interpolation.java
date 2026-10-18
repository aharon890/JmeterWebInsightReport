package com.jmeterwebinsightreport.core.servermetrics.collect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code ${env.*}}, {@code ${run.*}} and {@code ${secret.*}} inside a source URL or a query.
 *
 * <p>Three scopes, three rules:
 * <ul>
 *   <li>{@code ${env.name}} — the selected environment's {@code vars}. Unknown → refused, not
 *       left in place: a literal {@code ${env.cluster}} inside a PromQL selector matches nothing
 *       and reads as NO_MATCH, which sends the reader to the query instead of the file.</li>
 *   <li>{@code ${run.startTs}}, {@code ${run.endTs}}, {@code ${run.runId}},
 *       {@code ${run.testName}} — the run's own facts, so a query can select a load-test's
 *       own traffic by label if the services carry one.</li>
 *   <li>{@code ${secret.NAME}} — an environment variable, allowed in a URL and <em>refused in a
 *       query</em>: a query is written into the payload and the report as resolved, and a secret
 *       belongs in a header, never in a label selector. The values substituted are handed back
 *       as {@link Result#secretsUsed()} so the collector can mask them by value wherever the
 *       resolved URL is later written.</li>
 * </ul>
 * Anything else shaped like a placeholder — {@code ${evn.cluster}}, {@code ${ENV.x}}, a nested
 * {@code ${env.${run.x}}} — is refused too, for the same reason the unknown name is: left in
 * place it reaches the source as a literal, matches nothing, and reads as NO_MATCH.
 *
 * <p>Thresholds are never interpolated. That is the mapper's rule (a band must be a literal
 * number); this class is only ever given a URL or a query.
 */
public final class Interpolation {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{(env|run|secret)\\.([A-Za-z0-9_.-]+)}");
    /** Whatever still looks like a placeholder once the three scopes have been resolved. */
    private static final Pattern LEFTOVER = Pattern.compile("\\$\\{[^}]*}");

    /** The values one collection resolves against. */
    public static final class Scope {
        private final Map<String, String> env;
        private final Map<String, String> run;
        private final Map<String, String> secrets;

        public Scope(Map<String, String> env, Map<String, String> run, Map<String, String> secrets) {
            this.env = env == null ? new LinkedHashMap<String, String>() : env;
            this.run = run == null ? new LinkedHashMap<String, String>() : run;
            this.secrets = secrets == null ? new LinkedHashMap<String, String>() : secrets;
        }
    }

    /** Where a placeholder is being resolved — decides whether {@code ${secret.*}} is allowed. */
    public enum Site { URL, QUERY }

    /** The resolved text, or a problem naming the placeholder that could not be resolved. */
    public static final class Result {
        private final String text;
        private final String problem;
        private final List<String> secretsUsed;

        private Result(String text, String problem, List<String> secretsUsed) {
            this.text = text;
            this.problem = problem;
            this.secretsUsed = Collections.unmodifiableList(secretsUsed);
        }

        public String text() { return text; }
        public String problem() { return problem; }
        public boolean failed() { return problem != null; }

        /** The values substituted for {@code ${secret.*}} — what must never be written as text. */
        public List<String> secretsUsed() { return secretsUsed; }
    }

    private Interpolation() {
    }

    public static Result resolve(String text, Scope scope, Site site) {
        List<String> secretsUsed = new ArrayList<>();
        if (text == null) {
            return new Result(null, null, secretsUsed);
        }
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String kind = m.group(1);
            String name = m.group(2);
            String value;
            switch (kind) {
                case "env":
                    value = scope.env.get(name);
                    if (value == null) {
                        return new Result(null, "${env." + name + "} is not a variable of the selected "
                                + "environment" + (scope.env.isEmpty() ? " (it declares none)"
                                : " (declared: " + String.join(", ", scope.env.keySet()) + ")"), secretsUsed);
                    }
                    break;
                case "run":
                    value = scope.run.get(name);
                    if (value == null) {
                        return new Result(null, "${run." + name + "} is not a run variable (one of: "
                                + String.join(", ", scope.run.keySet()) + ")", secretsUsed);
                    }
                    break;
                default:
                    if (site == Site.QUERY) {
                        return new Result(null, "${secret." + name + "} is not allowed in a query - the "
                                + "resolved query is written into the payload and the report; a secret "
                                + "belongs in auth, never in a label selector", secretsUsed);
                    }
                    value = scope.secrets.get(name);
                    if (value == null) {
                        return new Result(null, "${secret." + name + "}: environment variable " + name
                                + " is not set", secretsUsed);
                    }
                    secretsUsed.add(value);
                    break;
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        Matcher left = LEFTOVER.matcher(out);
        if (left.find()) {
            return new Result(null, left.group() + " is not a placeholder this collector resolves "
                    + "(${env.*}, ${run.*}, ${secret.*}); left in place it would reach the source as a "
                    + "literal and match nothing", secretsUsed);
        }
        return new Result(out.toString(), null, secretsUsed);
    }
}
