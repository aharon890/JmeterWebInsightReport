package com.jmeterwebinsightreport.core.assertion;

/**
 * Why a rule (or one of its matches) produced no judgement.
 *
 * <p>{@link AssertionStatus#NOT_JUDGED} says a rule decided nothing. This says which kind of
 * nothing, because each has a completely different fix and a single word for all of them
 * sends the reader looking in the wrong place. It is machine-readable on purpose: the counts
 * are published per reason, so a pipeline that wants a typo'd rule to stop a build can ask for
 * exactly that without the engine imposing it on everyone.
 *
 * <p>One vocabulary for both sides of the gate. A client rule and a server-metric KPI that both
 * matched nothing say the same word, which is the whole reason the server side did not get a
 * fifth status of its own (INCONCLUSIVE was one word for five situations fixed by five different
 * people). Three of the reasons can only be produced by a server KPI — {@link #serverOnly()} —
 * and a client rule never carries them; they are still counted on every gate, as zero, so the
 * {@code gate.notJudged} block has one shape and its sum stays closed.
 *
 * <p>The declaration order is the WIRE order and nothing else. The first four are the original
 * vocabulary and keep their position — every pipeline that read {@code gate.notJudged} before
 * server metrics existed reads the same keys in the same place. The server-side reasons
 * follow, from "the source is at fault" to "the run is at fault". A surface that lists reasons
 * for a human uses {@link #displayOrder()}, not {@code values()}: the two differ, and iterating
 * the wire order on the console once put "disabled" ahead of "unmatched" on every run that
 * had both.
 *
 * <p>Each {@link #phrase()} is mirrored word for word by {@code WIR.status.REASON_PHRASE} in
 * {@code report.js}, which builds the same load-rule sentences in the browser. The
 * {@code reasonPhrases} map in {@code server-parity-vectors.json} pins both copies to one text:
 * the two had already drifted on DISABLED ("was parked" here, "parked" there) before anything
 * compared them.
 */
public enum NotJudged {

    /** {@code enabled: false}. The tester parked it; nothing is wrong. */
    DISABLED("was parked with enabled: false"),

    /**
     * The target selected nothing to judge — no sampler matched, or every match recorded zero
     * samples. On a server KPI: the query ran and matched no series.
     *
     * <p>Usually a renamed or mistyped sampler. A row that never ran is the same case and not a
     * separate one: its latency metrics are not small, they are undefined and stored as zero,
     * so judging them would report success for a plan entry that never executed.
     *
     * <p>Not a config error, on either side. A sampler that only runs in some environments is
     * legitimate, and so is a Kafka query that matches nothing in DEV because the topic is not
     * deployed there; failing the run on it would strand exactly those users. A pipeline that
     * wants it fatal opts in with {@code jq}.
     */
    NO_MATCH("matched nothing in this run"),

    /**
     * The metric is real but this run cannot produce a value for it — an off-grid percentile
     * with no retained distribution, a JTL column the test did not write. On a server KPI: the
     * series exists but the reducer produced no number, because the window was empty after
     * scoping or every point in it was a gap.
     *
     * <p>Distinct from {@link #INVALID_RULE}: the rule is well-formed and would work against a
     * run that carried the data. The fix is the run, not the file.
     */
    NO_VALUE("could not be measured in this run"),

    /**
     * The file is wrong — unknown metric, unreadable target, unparseable pattern, no bands;
     * on a server KPI also an unknown source, a missing query, an undeclared gate name.
     *
     * <p>A defect in the annotations rather than in the run, and the one a strict pipeline most
     * likely wants to treat as a failure. It used to BE a failure: INVALID ranked above FAIL,
     * so a mistyped metric name failed the build while no measured number had exceeded
     * anything. It is loud on six channels instead — the finding (with "did you mean…?"),
     * jmeter.log, stdout, the CI/CD JSON count, the findings pane and the blocking banner —
     * and {@code failonerror} decides whether loud means fatal.
     */
    INVALID_RULE("could not run"),

    /**
     * Server only. The series could not be collected — the source refused it or never answered
     * (authentication, timeout, DNS, a 5xx), or a CSV row of it could not be read.
     *
     * <p>Kept apart from {@link #NO_MATCH} because "we asked and got nothing back" and "we never
     * managed to ask" look identical in a report and are fixed by different people: this one is
     * credentials, an endpoint or the exported file, that one is the query or the environment labels.
     *
     * <p>The phrase says what happened to the SERIES and leaves the cause to the detail (SF23). It
     * used to end "because the source never answered", which was false beside a source that read
     * PARTIAL: one bad CSV row fails one series, and the source answers every other.
     */
    SOURCE_FAILED("could not be collected"),

    /**
     * Server only. Data arrived, but below the coverage floor (default 90% of the window).
     *
     * <p>Judging a window that is mostly holes would report a verdict about the part that
     * happened to be scraped. The fix is the collector's wait, the source's step, or the floor.
     */
    LOW_COVERAGE("was collected below the coverage floor"),

    /**
     * Server only. The data is fine; the load is not established. The KPI depends on a load rule
     * ({@code requiresLoad}, or the {@code divideBy} it needs a number from) that either FAILED —
     * the run missed its throughput target beyond tolerance, so a band written for 5 RPS says
     * nothing about a run that produced 3 — or decided nothing at all, so there is no load to
     * judge against. The detail says which, in the load rule's own words.
     *
     * <p>Neither a config error nor a verdict. When the load rule FAILED it is a client rule and
     * already carries the FAIL, under {@code failonassert}. When it decided nothing, its own
     * reason is the config error where there is one ({@code INVALID_RULE}) and nothing is fatal
     * where there is none ({@code NO_MATCH}, {@code DISABLED}) — so a renamed load sampler stands
     * the dependent KPIs down without failing the build, and the client gate's NO_MATCH and each
     * KPI's detail say so. This reason only explains why the dependent KPI stayed silent.
     *
     * <p>The phrase used to be "the run missed its load target", which was false for the second
     * case and turned the JUnit message into a contradiction ("missed its load target: load rule
     * 'x' matched nothing").
     */
    LOAD_NOT_ACHIEVED("was not judged because the load it depends on was not established");

    /**
     * The order a human-facing list of reasons uses — the console verdict line, the log line,
     * the Summary strip.
     *
     * <p>Config errors first, because they are what the reader most needs put in front of them;
     * then the order the console has always printed: unmatched, not measurable, disabled, with
     * the run-side server reason before "disabled" because a parked rule is the one state the
     * tester chose. On a gate of client rules this reproduces the pre-server-metrics line
     * exactly, which is what lets the console stay a stable surface while the vocabulary grows.
     *
     * <p>Every constant appears exactly once; {@code NotJudgedTest} pins that, because a reason
     * missing from this list would be counted everywhere and named nowhere.
     */
    private static final java.util.List<NotJudged> DISPLAY_ORDER =
            java.util.Collections.unmodifiableList(java.util.Arrays.asList(
                    INVALID_RULE, SOURCE_FAILED, LOW_COVERAGE,
                    NO_MATCH, NO_VALUE, LOAD_NOT_ACHIEVED, DISABLED));

    public static java.util.List<NotJudged> displayOrder() {
        return DISPLAY_ORDER;
    }

    private final String phrase;

    NotJudged(String phrase) {
        this.phrase = phrase;
    }

    /** A sentence fragment that reads after a rule id: "checkout-p95 matched nothing in this run". */
    public String phrase() {
        return phrase;
    }

    /** How the reason is named in the CI/CD JSON, JUnit XML and report payload. */
    public String wireName() {
        return name();
    }

    /**
     * The key this reason is counted under in the {@code gate.notJudged} block.
     *
     * <p>Every constant is named, and there is no {@code default} arm on purpose. There was one,
     * mapping anything unlisted to {@code "invalidRule"} — so a fifth reason added later would
     * have published its count under the invalid-rule key, silently overwriting the real one and
     * breaking BOTH closed arithmetics: the sum that lets a pipeline check the gate's own
     * bookkeeping instead of trusting it. That is the single invariant this enum exists to
     * protect, and the default arm quietly opted out of it. Now the compiler refuses the
     * addition until the key is chosen.
     */
    public String countKey() {
        switch (this) {
            case DISABLED:          return "disabled";
            case NO_MATCH:          return "noMatch";
            case NO_VALUE:          return "noValue";
            case INVALID_RULE:      return "invalidRule";
            case SOURCE_FAILED:     return "sourceFailed";
            case LOW_COVERAGE:      return "lowCoverage";
            case LOAD_NOT_ACHIEVED: return "loadNotAchieved";
        }
        throw new IllegalStateException("no count key for " + this
                + " — add one; gate.notJudged.* must sum to notJudged.total");
    }

    /**
     * The short label a console line or a badge strip counts this reason under —
     * "2 invalid, 1 unmatched". The same words on every surface that lists reasons, so the
     * terminal, the log and the Summary strip cannot describe one run in three vocabularies.
     */
    public String label() {
        switch (this) {
            case DISABLED:          return "disabled";
            case NO_MATCH:          return "unmatched";
            case NO_VALUE:          return "not measurable";
            case INVALID_RULE:      return "invalid";
            case SOURCE_FAILED:     return "source failed";
            case LOW_COVERAGE:      return "low coverage";
            case LOAD_NOT_ACHIEVED: return "load not achieved";
        }
        throw new IllegalStateException("no label for " + this);
    }

    /**
     * True when this reason means <em>the file asked for a check that never ran</em> — the
     * thing {@code failonerror} (default on) exists to fail the process over, and the thing the
     * JUnit writer renders as a {@code <failure>} rather than a {@code <skipped>} so it cannot
     * vanish from a dashboard as a grey line nobody clicks. For the two server-only reasons the
     * exit is additionally subject to the owning gate's {@code failsBuild} (delivery plan D4):
     * an observe-only gate's collection failure is printed, counted and rendered as a failure
     * in its own suite, and does not exit the process.
     *
     * <p>Decided here, once, by an exhaustive switch — not by a subtraction at each call site.
     * {@code skipped = notJudged − invalidRule} was how the JUnit writer used to reason, and
     * under that arithmetic every reason added later landed in {@code <skipped>} by default,
     * invisible, which is the wrong default for a source that could not be reached.
     *
     * <p>What is and is not an error is a decision, not a derivation, and the line is drawn
     * at whose fault it is: {@link #DISABLED} was asked for; {@link #NO_MATCH} is legitimate on
     * a plan or an environment running a subset; {@link #NO_VALUE} is the run's data, not the
     * file; {@link #LOAD_NOT_ACHIEVED} is already a FAIL on the load rule it depends on, and
     * counting it twice under a different flag would exit 1 for a verdict that
     * {@code failonassert} was reserved to decide — or, when the load rule decided nothing, that
     * rule's own reason already carries whatever is fatal about it. The other three mean the
     * check was declared and never happened.
     */
    public boolean isConfigError() {
        switch (this) {
            case INVALID_RULE:
            case SOURCE_FAILED:
            case LOW_COVERAGE:
                return true;
            case DISABLED:
            case NO_MATCH:
            case NO_VALUE:
            case LOAD_NOT_ACHIEVED:
                return false;
        }
        throw new IllegalStateException("no failonerror decision for " + this);
    }

    /**
     * True when only a server-metric KPI can produce this reason.
     *
     * <p>The client evaluator — and its mirror in {@code report.js} — never emits one of these,
     * so the client parity vectors are not asked to cover them and are refused if they claim
     * to. The server parity vectors (delivery plan D21, milestone M4) will carry the other half
     * of the guard; until they exist, nothing produces these reasons at all.
     */
    public boolean serverOnly() {
        switch (this) {
            case SOURCE_FAILED:
            case LOW_COVERAGE:
            case LOAD_NOT_ACHIEVED:
                return true;
            case DISABLED:
            case NO_MATCH:
            case NO_VALUE:
            case INVALID_RULE:
                return false;
        }
        throw new IllegalStateException("no side recorded for " + this);
    }
}
