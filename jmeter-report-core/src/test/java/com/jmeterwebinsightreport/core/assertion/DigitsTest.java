package com.jmeterwebinsightreport.core.assertion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs {@code digits-vectors.json} against {@link Digits#beside}.
 *
 * <p>The same file is run against the page's {@code WIR.digits.beside} by
 * {@code test-plans/js-unit/digits.test.js}. Java writes a client actual in the CI/CD JSON and
 * JUnit, the page writes the same actual on both tabs, and two copies of one rounding rule drift
 * between the runs that test them — so neither owns it. Compared with {@code ==}, never a
 * tolerance: the whole question is which side of a band the written number is on.
 */
class DigitsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The definition has 34 cases; a file that lost some must not pass on what is left. Four are
     * exact binary ties (1.125, -1.125, 372.5): the page's rounding is fixed by the JavaScript
     * spec, this side's by a {@code RoundingMode} and a {@code Math.round} anyone could swap, so
     * those four are what hold this class to the page. Eight are at four decimals, the CI/CD JSON's
     * server actual (S2), two of them the cap, which four places reach from 1e7 up.
     */
    private static final int FLOOR = 34;

    private static JsonNode vectors() throws IOException {
        try (InputStream in = DigitsTest.class.getResourceAsStream("/assertion/digits-vectors.json")) {
            assertNotNull(in, "digits-vectors.json is missing from the test resources");
            return MAPPER.readTree(in);
        }
    }

    @TestFactory
    Stream<DynamicTest> everyVectorIsWrittenAsTheDefinitionSays() throws IOException {
        JsonNode cases = vectors().get("cases");
        assertTrue(cases.size() >= FLOOR, "digits-vectors.json carries " + cases.size()
                + " cases, fewer than the " + FLOOR + " that define the rule");
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : cases) {
            tests.add(DynamicTest.dynamicTest(c.get("name").asText(), () -> {
                double[] bands = new double[c.get("bands").size()];
                for (int i = 0; i < bands.length; i++) {
                    bands[i] = c.get("bands").get(i).asDouble();
                }
                double got = Digits.beside(c.get("value").asDouble(), c.get("decimals").asInt(), bands);
                double want = c.get("expect").asDouble();
                assertTrue(got == want, c.get("name").asText() + ": wrote " + got + ", expected " + want);
            }));
        }
        return tests.stream();
    }

    /**
     * L12: the file's {@code bandWriteCases}, against {@link Predicate#trim} — how a band's own constant
     * is written in every sentence that quotes it. {@code digits.test.js} runs the same cases against
     * {@code WIR.javaText.trim}.
     */
    @TestFactory
    Stream<DynamicTest> everyBandIsWrittenInPlainDigits() throws IOException {
        JsonNode cases = vectors().get("bandWriteCases");
        assertNotNull(cases, "digits-vectors.json carries no bandWriteCases");
        assertTrue(cases.size() >= 19, "digits-vectors.json carries " + cases.size() + " band write cases");
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : cases) {
            tests.add(DynamicTest.dynamicTest(c.get("name").asText(), () ->
                    assertEquals(c.get("expect").asText(), Predicate.trim(c.get("value").asDouble()),
                            c.get("name").asText())));
        }
        return tests.stream();
    }

    /** A band that is not a finite number is its own word, and describe() writes a band through trim. */
    @Test
    void trimNamesANonFiniteBandAndDescribeWritesThroughIt() {
        assertEquals("NaN", Predicate.trim(Double.NaN));
        assertEquals("Infinity", Predicate.trim(Double.POSITIVE_INFINITY));
        assertEquals("-Infinity", Predicate.trim(Double.NEGATIVE_INFINITY));
        assertEquals("> 0.0005 ms", Predicate.of(Predicate.Op.GT, 0.0005).describe("ms"));
    }

    @Test
    void aBandThatIsNotFiniteSeparatesNothing() {
        double v = 119.36094674556213;
        // No finite band: the base, as with no band at all.
        assertTrue(Digits.beside(v, 2, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY) == 119.36);
        // Beside a finite one it is that band alone that decides.
        assertTrue(Digits.beside(v, 2, Double.NaN, 119.36, Double.POSITIVE_INFINITY) == 119.361);
    }

    @Test
    void aValueThatIsNotFiniteIsReturnedAsItIs() {
        assertTrue(Double.isNaN(Digits.beside(Double.NaN, 2, 1.0, 0)));
        assertTrue(Digits.beside(Double.POSITIVE_INFINITY, 2, 1.0, 0) == Double.POSITIVE_INFINITY);
        assertTrue(Digits.beside(Double.NEGATIVE_INFINITY, 0, 1.0) == Double.NEGATIVE_INFINITY);
    }

    @Test
    void theBandsOfARuleAreEveryOneItDeclaresAndZeroIsAddedOnlyWhenAsked() {
        Predicate warn = Predicate.of(Predicate.Op.GT, 234.91);
        Predicate fail = Predicate.of(Predicate.Op.GT, 100000);
        assertArrayEquals(new double[] {234.91, 100000}, Digits.bands(warn, fail));
        assertArrayEquals(new double[] {100000}, Digits.bands(null, fail), "an absent band is no constant");
        assertEquals(0, Digits.bands((Predicate) null, null).length);
        assertArrayEquals(new double[] {234.91, 100000, 0}, Digits.withZero(Digits.bands(warn, fail)));
        assertArrayEquals(new double[] {0}, Digits.withZero(new double[0]));
    }
}
