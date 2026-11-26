package com.jmeterwebinsightreport.core.assertion;

import java.util.Map;

/**
 * The run's word for a sampler, in the few sentences the judge writes (spec 001, contracts/page-words.md).
 *
 * <p>The page's words are one table, the report module's {@code templates/page-words.json}, with a column per tool:
 * "sampler" on a JMeter run, "request" on a k6 run. Four sentences the judge writes into the CI/CD JSON and JUnit
 * name a sampler: a metric that is not available per sampler ({@link Usability}), a {@code divideBy} rule that
 * matched several ({@code ServerKpiEvaluator}), the retired {@code onMissing} key ({@code AssertionRuleMapper}) and
 * a run that contained none ({@link AssertionValidator}). Each reads the run's column's {@code sampler} and
 * {@code samplers} through this, handed in by whoever knows the run's tool ({@code PageWords.samplerWords}), and
 * report.js's mirror reads the same two keys through {@code W(key)}, so the page and the run say one sentence.
 *
 * <p>{@link #JMETER} is the default wherever no run is in hand, so a JMeter run's sentences are today's, character
 * for character. {@code PageWordsTest} holds it to the table's {@code jmeter} column.
 */
public final class SamplerWords {

    /** The table's keys for the word, as report.js's {@code W('sampler')} and {@code W('samplers')} read them. */
    public static final String ONE_KEY = "sampler";
    public static final String MANY_KEY = "samplers";

    /** A JMeter run's words: the table's {@code jmeter} column, and what every sentence said before spec 001. */
    public static final SamplerWords JMETER = new SamplerWords("sampler", "samplers");

    private final String one;
    private final String many;

    private SamplerWords(String one, String many) {
        this.one = one;
        this.many = many;
    }

    /**
     * The words of one column of the table, as {@code PageWords.forTool} returns it. A key the column lacks reads as
     * itself, as report.js's {@code W(key)} reads it; no column at all is JMeter's.
     */
    public static SamplerWords of(Map<String, String> column) {
        if (column == null) {
            return JMETER;
        }
        String one = column.get(ONE_KEY);
        String many = column.get(MANY_KEY);
        return new SamplerWords(one == null ? ONE_KEY : one, many == null ? MANY_KEY : many);
    }

    /** The word for one sampler: "sampler", "request". */
    public String one() {
        return one;
    }

    /** The word for several: "samplers", "requests". */
    public String many() {
        return many;
    }

    @Override
    public String toString() {
        return one + "/" + many;
    }
}
