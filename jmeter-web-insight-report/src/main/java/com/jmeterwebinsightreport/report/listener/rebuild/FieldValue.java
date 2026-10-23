package com.jmeterwebinsightreport.report.listener.rebuild;

import org.apache.jmeter.engine.util.CompoundVariable;
import org.apache.jmeter.functions.InvalidVariableException;
import org.apache.jmeter.threads.JMeterContext;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.util.JMeterUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A listener field evaluated at the click, the way JMeter evaluates a test element's property when a
 * test starts - and refused in words wherever that evaluation would quietly produce a wrong path (R3).
 *
 * <p>JMeter replaces {@code ${...}} in a property when a test starts. The Rebuild button runs after
 * the test, so the fields it reads are evaluated here, explicitly, with {@link CompoundVariable}:
 * functions and properties work ({@code ${__P(runs.dir)}/server.csv}, {@code ${__time(yyyy-MM-dd)}}).
 * Four ways that goes wrong without a word, each closed here:
 * <ul>
 *   <li><b>The {@code String} constructor swallows a malformed function</b> and yields {@code ""}, which
 *   then names no file at all. {@code new CompoundVariable()} and {@code setParameters} throw instead,
 *   and the exception's message becomes the refusal.</li>
 *   <li><b>A backslash is an escape to the function parser</b>, so {@code \\host\share\x.csv} came back
 *   as another path. Text with no function is returned exactly as typed, which is what JMeter itself
 *   does with such a property. A backslash right before a <code>${</code> escapes the reference, which is
 *   then never evaluated; that is refused naming the backslash, not as a function JMeter lacks.</li>
 *   <li><b>A test-plan variable</b> ({@code ${runsDir}} from User Defined Variables) exists only while
 *   a test runs. Unresolved, it is left as the literal {@code ${runsDir}} and would be looked up as a
 *   file of that name; it is refused, naming it and pointing at {@code ${__P(...)}}.</li>
 *   <li><b>{@code ${__P(name)}} with {@code name} unset</b> reads as {@code 1} (and
 *   {@code ${__property(name)}} as the name itself) - a path that exists nowhere, reported as "not
 *   found" with no hint why. Refused, saying it would read as 1.</li>
 * </ul>
 *
 * <p>The thread's variables are swapped for fresh, empty ones around the evaluation and put back in a
 * {@code finally}: the event thread can carry what JMeter's Function Helper dialog left there, and a
 * variable that happens to resolve on this thread today is not one the field can rely on.
 */
public final class FieldValue {

    /** {@code ${name}} left in the text after evaluation: a variable nothing defined. */
    private static final Pattern LEFTOVER = Pattern.compile("\\$\\{([^}]*)}");

    private final String value;
    private final String refusal;
    private final boolean evaluated;

    private FieldValue(String value, String refusal, boolean evaluated) {
        this.value = value;
        this.refusal = refusal;
        this.evaluated = evaluated;
    }

    /** A value already resolved elsewhere - by the run, for a field unchanged since (R4). */
    public static FieldValue resolved(String value) {
        return new FieldValue(value == null ? "" : value.trim(), null, false);
    }

    /**
     * The field's text evaluated now: the text itself when it has no function, the evaluation's result
     * when it has one, or a refusal sentence.
     */
    public static FieldValue evaluate(String text) {
        String raw = text == null ? "" : text.trim();
        if (raw.isEmpty()) {
            return new FieldValue("", null, false);
        }
        if (!raw.contains("${")) {
            // Not even handed to the parser: it has nothing to do, and it would read a backslash.
            return new FieldValue(raw, null, false);
        }
        String escaped = escapedReference(raw);
        if (escaped != null) {
            // The parser reads "\$" as a literal dollar, so the reference after it is never evaluated
            // and comes out as text. Left to the checks below, a ${__P(day)} typed after a Windows
            // folder separator was refused as "JMeter has no such function here" - __P exists, and the
            // sentence sent the user looking for a missing function instead of at the backslash.
            return refused(raw + ": the backslash before " + escaped + " stops JMeter evaluating it - write the"
                    + " path with / instead: " + forwardSlashes(raw));
        }
        CompoundVariable compound = new CompoundVariable();
        try {
            compound.setParameters(raw);
        } catch (InvalidVariableException | RuntimeException e) {
            return refused("JMeter cannot read the function in " + raw + ": " + messageOf(e));
        }
        if (!compound.hasFunction()) {
            return checked(raw, raw, false);
        }
        String unset = unsetProperty(raw);
        if (unset != null) {
            return refused(unset);
        }
        JMeterContext context = JMeterContextService.getContext();
        JMeterVariables saved = context.getVariables();
        String value;
        try {
            context.setVariables(new JMeterVariables());
            value = compound.execute();
        } catch (RuntimeException e) {
            return refused("JMeter could not evaluate " + raw + ": " + messageOf(e));
        } finally {
            context.setVariables(saved);
        }
        return checked(raw, value == null ? "" : value.trim(), true);
    }

    /** The evaluated (or literal) text, trimmed; "" when the field is empty or refused. */
    public String value() {
        return refusal == null ? value : "";
    }

    /** Why this field cannot be used, or null. */
    public String refusal() {
        return refusal;
    }

    public boolean isRefused() {
        return refusal != null;
    }

    /** True when a function was executed to produce {@link #value()}: it is the click's, not the run's. */
    public boolean wasEvaluated() {
        return evaluated;
    }

    /**
     * This value with {@code f} applied to its text and to its refusal, evaluated or not as it was: how a
     * token held out of the evaluation is put back into both.
     */
    FieldValue map(UnaryOperator<String> f) {
        return new FieldValue(f.apply(value), refusal == null ? null : f.apply(refusal), evaluated);
    }

    private static FieldValue refused(String why) {
        return new FieldValue("", why, false);
    }

    /** A {@code ${...}} still in the result names something nothing resolved. */
    private static FieldValue checked(String raw, String value, boolean evaluated) {
        Matcher leftover = LEFTOVER.matcher(value);
        if (leftover.find()) {
            String name = leftover.group(1).trim();
            if (name.isEmpty()) {
                return refused(raw + " has an empty ${} - there is nothing in it to evaluate");
            }
            if (name.startsWith("__")) {
                return refused(raw + " leaves " + leftover.group() + " unevaluated - JMeter has no such function here");
            }
            return refused("${" + name + "} is a test-plan variable, which exists only while a test runs - set it"
                    + " as a property and write ${__P(" + name + ")} instead");
        }
        if (value.contains("${")) {
            return refused(raw + " has a \"${\" that is not a complete function or property reference");
        }
        return new FieldValue(value, null, evaluated);
    }

    /**
     * The first {@code ${...}} that an odd run of backslashes in front of it escapes, as the function
     * parser reads it ({@code \\} is an escaped backslash, so {@code \\${x}} is still a reference), or
     * null. What a hand-typed Windows path looks like: {@code D:\runs\${__P(day)}\server.csv}.
     */
    static String escapedReference(String raw) {
        for (int at = raw.indexOf("${"); at >= 0; at = raw.indexOf("${", at + 1)) {
            int backslashes = 0;
            for (int i = at - 1; i >= 0 && raw.charAt(i) == '\\'; i--) {
                backslashes++;
            }
            if (backslashes % 2 == 1) {
                int close = raw.indexOf('}', at);
                return close < 0 ? raw.substring(at) : raw.substring(at, close + 1);
            }
        }
        return null;
    }

    /** The path's folder separators written as {@code /}; a backslash inside a {@code ${...}} is the function's own. */
    private static String forwardSlashes(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        int depth = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '$' && i + 1 < raw.length() && raw.charAt(i + 1) == '{') {
                depth++;
            } else if (c == '}' && depth > 0) {
                depth--;
            } else if (c == '\\' && depth == 0) {
                c = '/';
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * The first {@code ${__P(name)}} or {@code ${__property(name)}} whose property is not set and which
     * gives no default, as a sentence, or null. A name that is itself a function is not judged here:
     * what it reads is only known once it runs.
     */
    static String unsetProperty(String raw) {
        for (String function : new String[]{"__P", "__property"}) {
            String opener = "${" + function + "(";
            for (int at = raw.indexOf(opener); at >= 0; at = raw.indexOf(opener, at + 1)) {
                List<String> args = arguments(raw, at + opener.length());
                if (args == null || args.isEmpty()) {
                    continue;
                }
                String name = args.get(0);
                if (name.isEmpty() || name.contains("${")) {
                    continue;
                }
                // __P(name, default); __property(name, variable, default).
                int defaultAt = "__P".equals(function) ? 1 : 2;
                if (args.size() > defaultAt) {
                    continue;
                }
                if (property(name) != null) {
                    continue;
                }
                String readsAs = "__P".equals(function) ? "1" : "its own name, " + name;
                return "${" + function + "(" + name + ")} reads the property " + name + ", which is not set, so it"
                        + " would read as " + readsAs + " - set it with -J" + name + "=... or in user.properties,"
                        + " or give a default: ${__P(" + name + ",<default>)}";
            }
        }
        return null;
    }

    /**
     * The top-level arguments of the function call whose argument list starts at {@code from}, split on
     * unescaped commas outside nested calls, or null when the call is not closed.
     */
    private static List<String> arguments(String raw, int from) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int i = from; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                current.append(raw.charAt(++i));
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (depth == 0) {
                    args.add(current.toString());
                    return args;
                }
                depth--;
            } else if (c == ',' && depth == 0) {
                args.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        return null;
    }

    private static String property(String name) {
        try {
            return JMeterUtils.getProperty(name);
        } catch (RuntimeException e) {
            return null;   // no properties loaded: nothing is set
        }
    }

    private static String messageOf(Throwable e) {
        return e.getMessage() == null || e.getMessage().isEmpty() ? e.getClass().getSimpleName() : e.getMessage();
    }
}
