package com.jmeterwebinsightreport.core.servermetrics;

import com.jmeterwebinsightreport.core.assertion.AssertionValidator;
import com.jmeterwebinsightreport.core.assertion.Predicate;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server-side {@code UsabilityTest}: the invariant that makes one answer out of two
 * readers, and the severity line that separates "this is wrong" from "this cannot run".
 */
class KpiUsabilityTest {

    /**
     * A no-break space and a zero-width space, by code point: literal ones would be invisible in
     * this file too. Declared before SHAPES, which reads them as it initialises.
     */
    private static final String NBSP = String.valueOf((char) 0x00a0);
    private static final String ZWSP = String.valueOf((char) 0x200b);

    private static ServerKpi.Builder good() {
        return ServerKpi.builder("prom", "max by (partition) (kafka_consumergroup_lag)")
                .id("lag").unit("msgs").reducer(Reducer.parse("max")).gate("server")
                .fail(Predicate.of(Predicate.Op.GT, 800));
    }

    private static final List<ServerKpi> SHAPES = Arrays.asList(
            good().build(),
            good().warn(Predicate.of(Predicate.Op.GT, 640)).build(),
            good().fail(null).build(),                                                   // no bands
            ServerKpi.builder("prom", "   ").id("blank-query").reducer(Reducer.parse("max"))
                    .fail(Predicate.of(Predicate.Op.GT, 1)).build(),                     // missing query
            good().reducer(null).declarationProblem(KpiUsability.unknownReducer("mean")).build(),
            good().fail(null).declarationProblem(KpiUsability.unreadableBand("fail", "fail has 2 operators")).build(),
            good().declarationProblem(KpiUsability.unknownMetric("lagg", "kafka@1", "consumer.lag.max")).build(),
            good().declarationProblem(KpiUsability.undeclaredComponent("kafkaa", "kafka, jvm")).build(),
            good().declarationProblem(KpiUsability.retiredKey("warnAt", "\"warnAt\" is not a key of a server assert")).build(),
            good().warn(Predicate.of(Predicate.Op.GT, 900)).build(),                     // warn covered by fail
            good().fail(Predicate.of(Predicate.Op.LT, 0)).build(),                       // negative-only on max
            good().reducer(Reducer.parse("slope")).fail(Predicate.of(Predicate.Op.LT, 0)).build(),
            good().declarationProblem(KpiUsability.unknownType("db", "nosuchdb",
                    "postgres, kafka, redis, host-linux, aws-ec2, oracle, influxdb2, influxdb3, jvm, http-service,"
                            + " kubernetes-pod, mysql, custom")).build(),
            good().reducer(null).declarationProblem(KpiUsability.badTolerance("declares both")).build(),
            good().declarationProblem(KpiUsability.unreadableField("across",
                    "'worst' is not one of " + Across.vocabulary())).build(),
            good().reducer(null).build(),                                                // null reducer, nothing recorded
            good().gate(null).build(),                                                   // null gate, nothing recorded
            ServerKpi.builder("prom", NBSP + "lag").id("nbsp").reducer(Reducer.parse("max")).gate("server")
                    .fail(Predicate.of(Predicate.Op.GT, 800)).build(),                   // 17: invisible padding
            ServerKpi.builder("prom", NBSP + "lag").id("nbsp-no-bands").reducer(Reducer.parse("max"))
                    .gate("server").build(),                                             // 18: ...beside a refusal
            pct().fail(Predicate.of(Predicate.Op.GT, 150)).build(),                      // 19: past the ceiling
            pct().reducer(Reducer.parse("slope")).fail(Predicate.of(Predicate.Op.GT, 150)).build(), // 20: slope
            pct().divideBy("load:rps").fail(Predicate.of(Predicate.Op.GT, 150)).build()  // 21: ratio
    );

    /** A catalogue assert on a metric whose declared ceiling is 100 (F2b), as the mapper builds one. */
    private static ServerKpi.Builder pct() {
        return ServerKpi.builder("metrics", "100 * used / max").id("db-conn").unit("%")
                .reducer(Reducer.parse("max")).gate("db").metric("connections.used.pct").ceiling(100.0);
    }

    @Test
    void refusalIsNonNullExactlyWhenCheckContainsAnError() {
        // THE invariant. Without it the evaluator and the validator can separate again — a KPI
        // reported as broken and judged anyway, which is the loadValidity bug in server clothing.
        for (ServerKpi kpi : SHAPES) {
            KpiUsability refusal = KpiUsability.refusal(kpi);
            KpiUsability firstError = null;
            for (KpiUsability u : KpiUsability.check(kpi)) {
                if (u.severity() == AssertionValidator.Severity.ERROR) {
                    firstError = u;
                    break;
                }
            }
            if (firstError == null) {
                assertNull(refusal, kpi.id() + " has no ERROR and must not be refused");
            } else {
                assertNotNull(refusal, kpi.id() + " has an ERROR and must be refused");
                assertEquals(firstError.reasonText(), refusal.reasonText(),
                        "the INVALID_RULE detail is the first ERROR's own sentence");
            }
        }
    }

    @Test
    void aWellFormedKpiIsUsable() {
        assertNull(KpiUsability.refusal(SHAPES.get(0)));
        assertTrue(KpiUsability.check(SHAPES.get(0)).isEmpty());
        assertTrue(KpiUsability.check(SHAPES.get(1)).isEmpty(), "warn below fail is fine");
    }

    @Test
    void noBandsRefusesUnlessABandWasWrittenAndCouldNotBeRead() {
        ServerKpi noBands = SHAPES.get(2);
        assertEquals(KpiUsability.Reason.NO_BANDS, KpiUsability.refusal(noBands).reason());

        // One finding for one mistake: the unreadable band already said so.
        ServerKpi unreadable = SHAPES.get(5);
        List<KpiUsability> found = KpiUsability.check(unreadable);
        assertEquals(1, found.size(), found.toString());
        assertEquals(KpiUsability.Reason.BAND_UNREADABLE, found.get(0).reason());
        assertTrue(found.get(0).loaderReported(), "the mapper already reported it");
    }

    @Test
    void aMissingQueryRefusesBeforeAnythingBelowIt() {
        KpiUsability r = KpiUsability.refusal(SHAPES.get(3));
        assertEquals(KpiUsability.Reason.MISSING_QUERY, r.reason());
        assertFalse(r.loaderReported(), "found here, so the validator reports it");
    }

    @Test
    void mapperRecordedFactsRefuseAndAreNotReportedTwice() {
        for (int i : new int[] {4, 5, 6, 7, 8, 12, 13, 14}) {
            ServerKpi kpi = SHAPES.get(i);
            KpiUsability r = KpiUsability.refusal(kpi);
            assertNotNull(r, kpi.toString());
            assertTrue(r.loaderReported(), r.reason() + " was recorded by the mapper");
        }
    }

    @Test
    void aWarnBandTheFailBandCoversIsAWarningNotARefusal() {
        ServerKpi swapped = SHAPES.get(9);
        assertNull(KpiUsability.refusal(swapped), "the verdicts are correct; refusing would exit 1 over swapped numbers");
        List<KpiUsability> found = KpiUsability.check(swapped);
        assertEquals(1, found.size());
        assertEquals(KpiUsability.Reason.WARN_COVERED_BY_FAIL, found.get(0).reason());
        assertEquals(AssertionValidator.Severity.WARNING, found.get(0).severity());
    }

    @Test
    void aNegativeOnlyBandIsAWarningOnAGaugeAndNothingOnASlope() {
        // The client refuses lt 0 because it KNOWS its metrics are non-negative. A server KPI is
        // a gauge whose range this class does not know, and a slope is negative on purpose.
        ServerKpi onMax = SHAPES.get(10);
        assertNull(KpiUsability.refusal(onMax));
        List<KpiUsability> found = KpiUsability.check(onMax);
        assertEquals(1, found.size());
        assertEquals(KpiUsability.Reason.BAND_NEGATIVE_ONLY, found.get(0).reason());
        assertEquals(AssertionValidator.Severity.WARNING, found.get(0).severity());

        ServerKpi onSlope = SHAPES.get(11);
        assertTrue(KpiUsability.check(onSlope).isEmpty(), "heap must not shrink is a legitimate slope check");
    }

    @Test
    void anUpwardBandPastTheCatalogueCeilingIsAWarningNeverARefusal() {
        // F2b. The page never proposes a band past a metric's ceiling, but a typed or dragged one
        // used to get no finding at all: fail > 150 % on a percentage can never fire, and the
        // assert PASSes every run. Judged as written all the same, because some ceilings are nominal.
        ServerKpi past = SHAPES.get(19);
        assertNull(KpiUsability.refusal(past));
        List<KpiUsability> found = KpiUsability.check(past);
        assertEquals(1, found.size(), found.toString());
        assertEquals(KpiUsability.Reason.BAND_ABOVE_CEILING, found.get(0).reason());
        assertEquals(AssertionValidator.Severity.WARNING, found.get(0).severity());
        assertEquals("fail > 150 % can only fire above the ceiling of 100 % the catalogue declares for "
                + "connections.used.pct. If it never reads above its ceiling the band never fires and the "
                + "server assert reports PASS on every run", found.get(0).reasonText());

        assertTrue(KpiUsability.check(pct().fail(Predicate.of(Predicate.Op.GTE, 100)).build()).isEmpty(),
                "gte at the ceiling can fire on the ceiling itself");
        assertEquals(KpiUsability.Reason.BAND_ABOVE_CEILING,
                KpiUsability.check(pct().fail(Predicate.of(Predicate.Op.GT, 100)).build()).get(0).reason(),
                "gt at the ceiling fires only above it");
        assertTrue(KpiUsability.check(SHAPES.get(20)).isEmpty(), "a slope is per minute, not bounded by it");
        assertTrue(KpiUsability.check(SHAPES.get(21)).isEmpty(), "a ratio is per unit of the load");
        assertTrue(KpiUsability.check(pct().ceiling(null).fail(Predicate.of(Predicate.Op.GT, 150)).build()).isEmpty(),
                "no ceiling, no claim");
    }

    @Test
    void theClassDoesNotTrustTheMapperForANullReducerOrGate() {
        // The builder is public. "This method IS the definition of an unusable KPI" is only
        // true if it answers for a KPI built by anyone — so a null reducer or gate with nothing
        // recorded is refused here, not dereferenced by M4's evaluator.
        ServerKpi noReducer = SHAPES.get(15);
        assertEquals(KpiUsability.Reason.UNKNOWN_REDUCER, KpiUsability.refusal(noReducer).reason());
        ServerKpi noGate = SHAPES.get(16);
        assertEquals(KpiUsability.Reason.UNDECLARED_COMPONENT, KpiUsability.refusal(noGate).reason());
        assertTrue(KpiUsability.refusal(noGate).reasonText().startsWith("names no component"),
                KpiUsability.refusal(noGate).reasonText());

        // And when the mapper DID record it, the fact is not added a second time.
        ServerKpi recorded = SHAPES.get(13);
        int badTolerance = 0;
        int unknownReducer = 0;
        for (KpiUsability u : KpiUsability.check(recorded)) {
            if (u.reason() == KpiUsability.Reason.BAD_TOLERANCE) badTolerance++;
            if (u.reason() == KpiUsability.Reason.UNKNOWN_REDUCER) unknownReducer++;
        }
        assertEquals(1, badTolerance);
        assertEquals(0, unknownReducer, "one finding for one mistake");
    }

    @Test
    void aToleranceTheReducerRejectsIsFoundHereAndSaysSoItself() {
        // BAD_TOLERANCE is raised from two places: the mapper, for an `over` clause it could not
        // READ, and check(), for one it read perfectly well that max/min do not accept. Deciding
        // loaderReported() by Reason alone made the second one silent - the KPI was refused as
        // INVALID_RULE and no finding anywhere said why, because every BAD_TOLERANCE was assumed
        // to have been reported at load. The flag is per instance for exactly this reason.
        ServerKpi overOnAvg = good()
                .reducer(Reducer.of(Reducer.Kind.AVG).over(Reducer.Over.sustainedFor(30000))).build();
        KpiUsability refusal = KpiUsability.refusal(overOnAvg);
        assertNotNull(refusal, "an over on avg cannot be judged");
        assertEquals(KpiUsability.Reason.BAD_TOLERANCE, refusal.reason());
        assertFalse(refusal.loaderReported(),
                "found by check(), so check() must report it - nothing upstream did");
        assertTrue(refusal.reasonText().contains("only defined on max"), refusal.reasonText());

        // ...while the mapper-recorded one is still not said twice.
        assertTrue(KpiUsability.badTolerance("declares both").loaderReported());
    }

    @Test
    void everyReasonHasALoaderReportedDecisionAndASample() {
        // No default arm, on purpose: a thirteenth Reason added without a case here fails this
        // test rather than being skipped. And every sample must carry the reason it was asked
        // for, so the table below cannot quietly drift from the enum.
        for (KpiUsability.Reason r : KpiUsability.Reason.values()) {
            KpiUsability sample = sampleFor(r);
            assertNotNull(sample, r + " has no sample — add one");
            assertEquals(r, sample.reason());
            // Exhaustive switch in loaderReported(): an unlisted constant throws here.
            boolean reported = sample.loaderReported();
            assertEquals(producedByCheck(r), !reported,
                    r + ": produced by check() must mean NOT loader-reported, and vice versa");
        }
    }

    private static boolean producedByCheck(KpiUsability.Reason r) {
        return r == KpiUsability.Reason.MISSING_QUERY || r == KpiUsability.Reason.NO_BANDS
                || r == KpiUsability.Reason.WARN_COVERED_BY_FAIL || r == KpiUsability.Reason.BAND_NEGATIVE_ONLY
                || r == KpiUsability.Reason.BAND_ABOVE_CEILING || r == KpiUsability.Reason.INVISIBLE_PADDING;
    }

    private static KpiUsability sampleFor(KpiUsability.Reason r) {
        switch (r) {
            case UNKNOWN_TYPE:         return KpiUsability.unknownType("c", "t", "v");
            case UNKNOWN_METRIC:       return KpiUsability.unknownMetric("m", "t@1", "a, b");
            case METRIC_OR_QUERY:      return KpiUsability.metricOrQuery("p");
            case UNDECLARED_COMPONENT: return KpiUsability.undeclaredComponent("x", "y");
            case UNKNOWN_REDUCER:      return KpiUsability.unknownReducer("x");
            case BAD_TOLERANCE:        return KpiUsability.badTolerance("p");
            case UNREADABLE_FIELD:     return KpiUsability.unreadableField("across", "p");
            case BAND_UNREADABLE:      return KpiUsability.unreadableBand("fail", "p");
            case RETIRED_KEY:          return KpiUsability.retiredKey("k", "p");
            // The four check() produces, pulled out of SHAPES so they are the real objects.
            case MISSING_QUERY:        return KpiUsability.check(SHAPES.get(3)).get(0);
            case NO_BANDS:             return KpiUsability.check(SHAPES.get(2)).get(0);
            case WARN_COVERED_BY_FAIL: return KpiUsability.check(SHAPES.get(9)).get(0);
            case BAND_NEGATIVE_ONLY:   return KpiUsability.check(SHAPES.get(10)).get(0);
            case BAND_ABOVE_CEILING:   return KpiUsability.check(SHAPES.get(19)).get(0);
            case INVISIBLE_PADDING:    return KpiUsability.check(SHAPES.get(17)).get(0);
        }
        throw new IllegalStateException("no sample for " + r);
    }

    @Test
    void aQueryIsReadTrimmedAsEveryOtherTextFieldOfAnAssertIs() {
        // U7. A query is a series' identity, and "lag " asked a CSV for rows nobody had written:
        // EMPTY, NO_MATCH, and a run that should FAIL exited 0. String.trim, so the same as the
        // id, the unit and the component - and only what is <= U+0020.
        assertEquals("lag", ServerKpi.builder("prom", "  lag\t\n").reducer(Reducer.parse("max")).build().query());
        assertNull(ServerKpi.builder("prom", null).build().query(), "no query stays no query");
        assertEquals("", ServerKpi.builder("prom", "   ").build().query(), "a blank one is still blank");
        assertEquals(NBSP + "lag", ServerKpi.builder("prom", " " + NBSP + "lag ").build().query(),
                "a no-break space is not what String.trim trims, and the page trims as Java does");
    }

    @Test
    void aQueryPaddedWithAnInvisibleCharacterIsAWarningNotARefusal() {
        // Q2: findings do not gate. The KPI judges what it asked for - nothing matches - and the
        // warning is the only thing on any surface that can say why.
        ServerKpi padded = SHAPES.get(17);
        assertNull(KpiUsability.refusal(padded), "an invisible character is not a reason to exit 1");
        List<KpiUsability> found = KpiUsability.check(padded);
        assertEquals(1, found.size(), found.toString());
        assertEquals(KpiUsability.Reason.INVISIBLE_PADDING, found.get(0).reason());
        assertEquals(AssertionValidator.Severity.WARNING, found.get(0).severity());
        assertFalse(found.get(0).loaderReported(), "check() finds it, so check() reports it");
        assertEquals("the query has an invisible character at its start (U+00A0) that is not trimmed like a plain "
                + "space, so no series will match it — delete it", found.get(0).reasonText());

        // Either end, a zero-width space too; both ends in one sentence.
        assertEquals("the query has an invisible character at its end (U+200B) that is not trimmed like a plain "
                        + "space, so no series will match it — delete it",
                KpiUsability.check(ServerKpi.builder("prom", "lag" + ZWSP).id("z").reducer(Reducer.parse("max"))
                        .gate("server").fail(Predicate.of(Predicate.Op.GT, 1)).build()).get(0).reasonText());
        assertTrue(KpiUsability.check(ServerKpi.builder("prom", NBSP + "lag" + ZWSP).id("both")
                        .reducer(Reducer.parse("max")).gate("server").fail(Predicate.of(Predicate.Op.GT, 1)).build())
                .get(0).reasonText().startsWith("the query has an invisible character at its start (U+00A0) "
                        + "and at its end (U+200B) that"));
        // Inside the query it is the query's business, and a well-formed one raises nothing.
        assertTrue(KpiUsability.check(ServerKpi.builder("prom", "a" + NBSP + "b").id("inside")
                .reducer(Reducer.parse("max")).gate("server").fail(Predicate.of(Predicate.Op.GT, 1)).build()).isEmpty());

        // It is a fact about the query alone, so a refusal that returns early does not swallow it.
        List<KpiUsability> beside = KpiUsability.check(SHAPES.get(18));
        assertEquals(KpiUsability.Reason.NO_BANDS, beside.get(0).reason(), "the refusal first");
        assertEquals(KpiUsability.Reason.INVISIBLE_PADDING, beside.get(beside.size() - 1).reason());

        assertTrue(KpiUsability.hasInvisibleEnd(NBSP + "x"));
        assertTrue(KpiUsability.hasInvisibleEnd("x" + String.valueOf((char) 0xfeff)));
        assertFalse(KpiUsability.hasInvisibleEnd("x"));
        assertFalse(KpiUsability.hasInvisibleEnd(""));
        assertFalse(KpiUsability.hasInvisibleEnd(null));
    }

    @Test
    void theInvisibleClassIsAFixedTableNotTheRuntimesUnicodeCategories() {
        // F8: the six code points a review named, and the tag space past the BMP, are samples of
        // a class - White_Space, Default_Ignorable_Code_Point and the C1 controls above U+0020.
        // None of them is a space separator to Character.getType on this JVM, and U+180E was one
        // before Unicode 6.3: a class read off the runtime moved with the runtime. ServerParityTest
        // sweeps every code point against the vector file's invisibleEnds.ranges.
        for (int cp : new int[] {0x2060, 0x00AD, 0x0085, 0x200E, 0x200F, 0x180E, 0x202E, 0x2066, 0x034F,
                0xFE0F, 0x3164, 0xE0020}) {
            String ch = new String(Character.toChars(cp));
            assertTrue(KpiUsability.hasInvisibleEnd("x" + ch), String.format("U+%04X at the end", cp));
            assertTrue(KpiUsability.hasInvisibleEnd(ch + "x"), String.format("U+%04X at the start", cp));
        }
        assertEquals("the query has an invisible character at its end (U+E0020) that is not trimmed like a "
                        + "plain space, so no series will match it — delete it",
                KpiUsability.invisiblePaddingText("cpu" + new String(Character.toChars(0xE0020))),
                "named by its code point, not by a UTF-16 unit");
        // Visible characters, and format characters that are not default-ignorable, stay out: the
        // braille blank, the not sign, the Arabic number sign, an interlinear annotation anchor,
        // and the first code point past the tag block.
        for (int cp : new int[] {0x2800, 0x00AC, 0x0600, 0xFFF9, 0xE1000}) {
            assertFalse(KpiUsability.hasInvisibleEnd("x" + new String(Character.toChars(cp))),
                    String.format("U+%04X is not invisible padding", cp));
        }
        // At or below U+0020 is String.trim's: ServerKpi has taken it off both ends before anything
        // asks, and "not trimmed like a plain space" is no sentence for a plain space.
        assertNull(KpiUsability.invisiblePaddingText("x "));
        assertNull(KpiUsability.invisiblePaddingText("\tx"));
    }

    @Test
    void aRetiredKeySentenceAlwaysNamesTheKey() {
        assertTrue(KpiUsability.retiredKey("warnAt", "is gone").reasonText().startsWith("\"warnAt\": "));
        assertEquals("\"warnAt\" is gone", KpiUsability.retiredKey("warnAt", "\"warnAt\" is gone").reasonText(),
                "a sentence that already opens with the key is not prefixed twice");
    }

    @Test
    void aKpiWithNoIdDerivesAStableReadableOne() {
        ServerKpi kpi = ServerKpi.builder("prom", "max by (partition) (kafka_consumergroup_lag{topic=\"orders\"})")
                .reducer(Reducer.parse("max")).fail(Predicate.of(Predicate.Op.GT, 1)).build();
        assertFalse(kpi.idDeclared());
        // The query slug is cut at 40 characters and a dangling dash dropped — readable in a
        // JUnit testcase name, and the same on every run.
        assertEquals("prom-max-by-partition-kafka-consumergroup-lag", kpi.id());
        assertEquals("prom-max-by-partition-kafka-consumergroup-lag",
                ServerKpi.builder("prom", kpi.query()).reducer(Reducer.parse("max")).build().id(),
                "stable across builds");
        assertTrue(good().build().idDeclared());
    }

    @Test
    void aCatalogueAssertIsNamedAfterItsComponentAndMetric() {
        ServerKpi kpi = ServerKpi.builder(ServerSource.COLLECTION_ID, "max by (instance) (pg_up)")
                .gate("orders-db").metric("connections.used.pct")
                .reducer(Reducer.parse("max")).fail(Predicate.of(Predicate.Op.GT, 85)).build();
        assertEquals("orders-db-connections-used-pct", kpi.id());
        assertEquals("connections.used.pct", kpi.metric());

        // A custom query on a declared component is named after the component, not the source.
        ServerKpi custom = ServerKpi.builder(ServerSource.COLLECTION_ID, "payment_queue_depth")
                .gate("checkout-app").reducer(Reducer.parse("max")).fail(Predicate.of(Predicate.Op.GT, 1)).build();
        assertEquals("checkout-app-payment-queue-depth", custom.id());
    }

    /** {@link #good()} with the unit, reducer and divisor given. */
    private static ServerKpi shaped(String unit, String reduce, String divideBy) {
        return good().unit(unit).reducer(reduce == null ? null : Reducer.parse(reduce)).divideBy(divideBy).build();
    }

    @Test
    void theVerdictUnitIsTheSeriesUnitPerMinuteOnASlopeAndPerUnitOfLoadOnARatio() {
        // SF16 (B9 + the F5 leftovers). unit() is the series' unit and stays so - it is the CI/CD
        // JSON's `unit`; verdictUnit() is what a verdict number is in, and the page mirrors it.
        assertEquals("msgs", shaped("msgs", "max", null).verdictUnit());
        assertEquals("B/min", shaped("B", "slope", null).verdictUnit());
        assertEquals("B", shaped("B", "slope", null).unit(), "the declared unit is untouched");
        assertEquals("/min", shaped("", "slope", null).verdictUnit(), "the catalogue's spelling of a bare count per minute");
        assertEquals("% ÷ load:throughput", shaped("%", "max", "load:throughput").verdictUnit());
        assertEquals("÷ load:throughput", shaped("", "max", "load:throughput").verdictUnit());
        assertEquals("B/min ÷ load:throughput", shaped("B", "slope", "load:throughput").verdictUnit());
        assertEquals("/min ÷ load:throughput", shaped("", "slope", "load:throughput").verdictUnit());
        assertEquals("msgs", shaped("msgs", null, null).verdictUnit(), "an unreadable reducer says nothing about a rate");

        // The criteria JUnit prints are in it too.
        ServerKpi slope = good().unit("B").reducer(Reducer.parse("slope"))
                .warn(Predicate.of(Predicate.Op.GT, 40)).fail(Predicate.of(Predicate.Op.GT, 50)).build();
        assertEquals("warn > 40 B/min  ·  fail > 50 B/min", slope.describeCriteria());
    }

    @Test
    void aWarningQuotesItsBandsInTheUnitTheyAreComparedIn() {
        // The sentence a reader acts on named "fail > 200 B" on a slope whose band is B per minute,
        // and a ratio's band with no divisor at all. The vectors pin the same sentences for the
        // mirror (server-parity-vectors.json, "SF16: ...").
        ServerKpi slope = good().unit("B").reducer(Reducer.parse("slope"))
                .warn(Predicate.of(Predicate.Op.GT, 500)).fail(Predicate.of(Predicate.Op.GT, 200)).build();
        List<KpiUsability> found = KpiUsability.check(slope);
        assertEquals(1, found.size(), found.toString());
        assertEquals("warn can never fire: fail > 200 B/min is tested first and already covers warn > 500 B/min. "
                + "Swapped values?", found.get(0).reasonText());

        ServerKpi ratio = good().unit("msgs").divideBy("load:throughput")
                .warn(Predicate.of(Predicate.Op.LT, 0)).build();
        found = KpiUsability.check(ratio);
        assertEquals(1, found.size(), found.toString());
        assertEquals(KpiUsability.Reason.BAND_NEGATIVE_ONLY, found.get(0).reason());
        assertTrue(found.get(0).reasonText().startsWith("warn < 0 msgs ÷ load:throughput can only fire on a negative value."),
                found.get(0).reasonText());
    }

    @Test
    void aBlankQueryAfterAMetricRefusalIsOneFindingNotTwo() {
        ServerKpi kpi = ServerKpi.builder(ServerSource.COLLECTION_ID, null).gate("db")
                .declarationProblem(KpiUsability.unknownMetric("x", "postgres@1", "up"))
                .reducer(Reducer.parse("max")).fail(Predicate.of(Predicate.Op.GT, 1)).build();
        List<KpiUsability> found = KpiUsability.check(kpi);
        assertEquals(1, found.size(), found.toString());
        assertEquals(KpiUsability.Reason.UNKNOWN_METRIC, found.get(0).reason());
    }

    @Test
    void aCatalogueMetricOnAnUndeclaredComponentIsTheComponentsProblemAloneNeverAlsoNoQuery() {
        // SF21. The metric brings its query from the component's type; with the component
        // undeclared there is no type to bring it from, and "no query - the server assert has
        // nothing to collect" pointed at a field the author had no reason to write.
        ServerKpi catalogue = ServerKpi.builder(ServerSource.COLLECTION_ID, null).metric("connections.used.pct")
                .gate("orders-dbb").fail(Predicate.of(Predicate.Op.GT, 90))
                .declarationProblem(KpiUsability.undeclaredComponent("orders-dbb", "orders-db")).build();
        List<KpiUsability> found = KpiUsability.check(catalogue);
        assertEquals(1, found.size(), found.toString());
        assertEquals(KpiUsability.Reason.UNDECLARED_COMPONENT, found.get(0).reason());
        assertEquals(found.get(0), KpiUsability.refusal(catalogue), "the refusal is that one sentence");

        // An assert that names neither a metric nor a query on an undeclared component has
        // genuinely written no query, and is told so beside the component.
        ServerKpi neither = ServerKpi.builder(ServerSource.COLLECTION_ID, null).gate("orders-dbb")
                .fail(Predicate.of(Predicate.Op.GT, 90))
                .declarationProblem(KpiUsability.undeclaredComponent("orders-dbb", "orders-db")).build();
        assertEquals(KpiUsability.Reason.MISSING_QUERY, KpiUsability.check(neither).get(1).reason());
    }

    @Test
    void aNegativeOnlyBandGivesTheServersAdviceNeverTheClientsFractionHint() {
        // U8b. The tail was the client's loadValidity hint, "a tolerance meant as a percentage is
        // written as a fraction - 0.2, not 20". A server % metric is 0-100, so following it turned a
        // 20 % floor into fail < 0.2, a band that almost never fires.
        ServerKpi pct = good().unit("%").fail(Predicate.of(Predicate.Op.LT, 0)).build();
        String sentence = KpiUsability.check(pct).get(0).reasonText();
        assertEquals("fail < 0 % can only fire on a negative value. If this metric is never negative the band never "
                + "fires and the server assert reports PASS on every run; a band is written in the unit the values "
                + "are judged in", sentence);
        assertFalse(sentence.contains("0.2"), sentence);
    }

    @Test
    void theDeclarationLabelIsTheMappersNameForTheAssertAndDefaultsToItsId() {
        // SF21: the evaluator's refusal of an id-less assert and the mapper's finding are one finding.
        assertEquals("lag", good().build().declarationLabel(), "a KPI built without one: its id");
        assertEquals("serverMetrics.asserts[3]", ServerKpi.builder(ServerSource.COLLECTION_ID, "lag").gate("db")
                .declarationLabel("serverMetrics.asserts[3]").build().declarationLabel());
        assertEquals("db-lag", ServerKpi.builder(ServerSource.COLLECTION_ID, "lag").gate("db")
                .declarationLabel(" ").build().declarationLabel(), "a blank label is no label");
    }
}
