package com.jmeterwebinsightreport.report.generator;

import org.junit.jupiter.api.Test;

import static com.jmeterwebinsightreport.report.generator.WebInsightReportGenerator.reportFilenameGlob;
import static com.jmeterwebinsightreport.report.generator.WebInsightReportGenerator.resolveReportFilename;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code resolveFilename} decides where all four artifacts land, and until this class existed it
 * had no test of its own — only four assertions reaching through
 * {@code WebInsightDataExporterTest}, which cared about the collision check rather than the
 * resolution. Nothing covered the extension cases, the colon, {@code .html}-only, the reserved
 * dashboard names, or {@code ${timestamp}} as a whole name.
 */
class WebInsightReportGeneratorTest {

    private static final String DEFAULT = "web-insight-report.html";

    // ---- the plain cases -----------------------------------------------------

    @Test
    void anAbsentNameFallsBackToTheDefault() {
        assertEquals(DEFAULT, resolveReportFilename(null));
        assertEquals(DEFAULT, resolveReportFilename(""));
    }

    @Test
    void aPlainNameGetsTheHtmlSuffixExactlyOnce() {
        assertEquals("myreport.html", resolveReportFilename("myreport"));
        assertEquals("myreport.html", resolveReportFilename("myreport.html"));
    }

    // ---- extensions (were case-sensitive and purely additive) -----------------

    @Test
    void theExtensionCheckIsCaseInsensitive() {
        // Was MyReport.HTML.html, with MyReport.HTML.json and MyReport.HTML.xml beside it.
        assertEquals("MyReport.html", resolveReportFilename("MyReport.HTML"));
        assertEquals("MyReport.html", resolveReportFilename("MyReport.Html"));
    }

    @Test
    void anotherExtensionIsReplacedRatherThanStackedOn() {
        assertEquals("report.html", resolveReportFilename("report.htm"));   // was report.htm.html
        assertEquals("report.html", resolveReportFilename("report.json"));  // was report.json.html
        assertEquals("report.html", resolveReportFilename("report.xml"));
    }

    @Test
    void aNameThatIsNothingButAnExtensionDoesNotProduceDotfiles() {
        // ".html" passed the old endsWith check untouched and yielded three dotfiles —
        // .html, .json and .xml — invisible to ls on Unix.
        assertEquals(DEFAULT, resolveReportFilename(".html"));
        assertEquals(DEFAULT, resolveReportFilename(".HTML"));
        assertEquals(DEFAULT, resolveReportFilename(".htm"));
    }

    @Test
    void aDotIsNotConfusedForAnExtension() {
        assertEquals("report.2026.html", resolveReportFilename("report.2026"));
    }

    // ---- paths (must stay rejected) -------------------------------------------

    @Test
    void aPathIsRejected() {
        for (String hostile : new String[]{
                "../../evil.html", "..\\evil", "sub/dir/x.html", "..", "../", "..\\",
                "x\\..\\y.html", "/etc/passwd", "C:\\abs\\evil.html"}) {
            assertEquals(DEFAULT, resolveReportFilename(hostile),
                    "should have fallen back for: " + hostile);
        }
    }

    @Test
    void aDoubleDotInsideAnOrdinaryNameStillWorks() {
        // Regression guard. Rejecting on a bare ".." substring turned these valid names into a
        // silent fallback, which is why the check tests for real path syntax instead.
        assertEquals("load..final.html", resolveReportFilename("load..final.html"));
        assertEquals("a..b.html", resolveReportFilename("a..b"));
    }

    @Test
    void urlEncodedTraversalIsRejectedRatherThanDecoded() {
        // Nothing decodes %2f, so this is just an unusual name — but '%' is outside the
        // allowlist, so it falls back rather than producing a file called "..%2fevil.html".
        assertEquals(DEFAULT, resolveReportFilename("..%2fevil.html"));
    }

    // ---- characters that used to escape the check -----------------------------

    @Test
    void aColonIsRejectedBeforeAnythingIsCreated() {
        // Was accepted here and thrown from outputFile.toPath() as InvalidPathException — an
        // IllegalArgumentException, so it escaped the declared "throws IOException" unchecked,
        // and only after the output directory existed and the payload had been written. On
        // Linux the same name was legal, so behaviour differed per platform.
        assertEquals(DEFAULT, resolveReportFilename("rep:ort.html"));
        assertEquals(DEFAULT, resolveReportFilename("C:evil.html"));
        assertEquals(DEFAULT, resolveReportFilename("evil.html:ads"));
    }

    @Test
    void otherWindowsReservedCharactersAreRejectedToo() {
        for (String hostile : new String[]{"a<b.html", "a>b.html", "a|b.html", "a?b.html",
                "a\"b.html", "a*b.html"}) {
            assertEquals(DEFAULT, resolveReportFilename(hostile),
                    "should have fallen back for: " + hostile);
        }
    }

    @Test
    void aLiteralAsteriskCannotActAsAWildcard() {
        // The collision check turns the resolved name into a glob. A '*' the operator typed
        // would have been treated as a wildcard there, so a directory holding repXXXort.json
        // matched "rep*ort.html". Rejecting the character is stronger than escaping it.
        assertEquals(DEFAULT, resolveReportFilename("rep*ort.html"));
        assertEquals(DEFAULT, reportFilenameGlob("rep*ort.html"));
    }

    @Test
    void nonAsciiNamesKeepWorking() {
        // The allowlist is \p{L}\p{N}, not A-Za-z0-9, so this is not collateral damage.
        assertEquals("отчёт.html", resolveReportFilename("отчёт"));
        assertEquals("レポート.html", resolveReportFilename("レポート.html"));
        assertEquals("informe-año.html", resolveReportFilename("informe-año.html"));
    }

    @Test
    void spacesUnderscoresAndHyphensAreAllowed() {
        assertEquals("my report_v2-final.html", resolveReportFilename("my report_v2-final"));
    }

    // ---- reserved dashboard names (N2) ---------------------------------------

    @Test
    void aNameJmeterOwnDashboardUsesIsRejected() {
        // Verified live: -Jwebinsight.report.filename=index.html replaced JMeter's 9,672-byte
        // dashboard entry point with our 1.3 MB report, exit 0, no warning.
        assertEquals(DEFAULT, resolveReportFilename("index.html"));
        assertEquals(DEFAULT, resolveReportFilename("index"));
        assertEquals(DEFAULT, resolveReportFilename("INDEX.HTML"));

        // =statistics is the more dangerous half: the .json sibling would land on top of
        // statistics.json, the file most CI pipelines actually read.
        assertEquals(DEFAULT, resolveReportFilename("statistics"));
        assertEquals(DEFAULT, resolveReportFilename("statistics.html"));
        assertEquals(DEFAULT, resolveReportFilename("statistics.json"));
    }

    @Test
    void aReportIsStillProducedUnderTheFallbackName() {
        // Both halves of C31: rejected, AND a report still exists. Rejecting into nothing would
        // be a worse outcome than the clobber, which is what happens today for =statistics.
        assertEquals(DEFAULT, resolveReportFilename("index.html"));
        assertTrue(resolveReportFilename("index.html").endsWith(".html"));
    }

    @Test
    void directoriesJmeterWritesAreNotReserved() {
        // content/ and sbadmin2-1.0.7/ are directories, and everything we write carries an
        // extension, so there is nothing to collide with. Rejecting them would be a false
        // positive on a report legitimately called "content".
        assertEquals("content.html", resolveReportFilename("content"));
        assertEquals("sbadmin2.html", resolveReportFilename("sbadmin2"));
    }

    // ---- ${timestamp} --------------------------------------------------------

    @Test
    void theTimestampIsSubstitutedWithSecondGranularity() {
        String resolved = resolveReportFilename("run_${timestamp}.html");
        assertTrue(resolved.matches("run_\\d{8}_\\d{6}\\.html"),
                "expected run_yyyyMMdd_HHmmss.html but got: " + resolved);
    }

    @Test
    void theStampCarriesSecondsNotJustMinutes() {
        // The whole point of the change: at yyyyMMdd_HHmm two back-to-back runs produced one
        // file, and run-config-tests.sh slept 61 seconds to work around it. A stamp of
        // yyyyMMdd_HHmmss is 15 characters; the old one was 13.
        String stamp = resolveReportFilename("run_${timestamp}.html")
                .replace("run_", "").replace(".html", "");
        assertEquals(15, stamp.length(), "expected yyyyMMdd_HHmmss but got: " + stamp);
        assertTrue(stamp.matches("\\d{8}_\\d{6}"), stamp);
    }

    /**
     * A placeholder at the end of a name does not make the name end where the placeholder was.
     *
     * <p>Validation ran on the configured string with {@code ${timestamp}} <em>deleted</em>, so
     * {@code backup.${timestamp}} was measured as {@code "backup."}, tripped the trailing-dot
     * rule — which exists because Windows silently strips a trailing dot — and was replaced by
     * the shared default name. The resolved name it was rejected on behalf of,
     * {@code backup.20260101_120000.html}, ends in neither a dot nor a space and is legal on
     * every platform.
     *
     * <p>That failure mode is the exact opposite of what {@code ${timestamp}} is for: the
     * operator asked for one file per run and silently got one file shared by all of them, with
     * each run overwriting the last.
     */
    @Test
    void aPlaceholderAtTheEndOfANameIsNotATrailingDot() {
        assertTrue(resolveReportFilename("backup.${timestamp}").matches("backup\\.\\d{8}_\\d{6}\\.html"),
                "got: " + resolveReportFilename("backup.${timestamp}"));
        assertTrue(resolveReportFilename("run ${timestamp}").matches("run \\d{8}_\\d{6}\\.html"),
                "a space before the placeholder is not a trailing space either: "
                        + resolveReportFilename("run ${timestamp}"));

        // ...and the glob has to agree, or the -g -o exporter looks for a file the listener
        // never wrote. Both sides validate against the same canonical stamp precisely so that
        // one cannot accept a name the other rejects.
        assertEquals("backup.*.html", reportFilenameGlob("backup.${timestamp}"));
        assertEquals("run *.html", reportFilenameGlob("run ${timestamp}"));
    }

    /** A real trailing dot or space is still rejected — the rule was mis-applied, not wrong. */
    @Test
    void anActualTrailingDotOrSpaceIsStillRejected() {
        assertEquals(DEFAULT, resolveReportFilename("report."));
        assertEquals(DEFAULT, resolveReportFilename("report "));
        assertEquals(DEFAULT, resolveReportFilename("${timestamp}_report."));
        assertEquals(DEFAULT, resolveReportFilename("${timestamp} "));
    }

    @Test
    void theGlobKeepsItsLiteralSegments() {
        assertEquals("ts_*.html", reportFilenameGlob("ts_${timestamp}.html"));
        assertEquals("run_*.html", reportFilenameGlob("run_${timestamp}"));
        assertEquals("plain.html", reportFilenameGlob("plain.html"));
        assertEquals(DEFAULT, reportFilenameGlob(null));
        assertEquals(DEFAULT, reportFilenameGlob(""));
    }

    @Test
    void aWholeNameTimestampStillResolvesToSomethingUsable() {
        // '${timestamp}' as the entire name yields glob "*.html" and therefore json glob
        // "*.json", which matched JMeter's own statistics.json and made the exporter skip —
        // producing NO report at all, exit 0. The glob is unavoidable here; the guard against
        // matching an unrelated file lives in WebInsightDataExporter.listenerReportExists.
        assertEquals("*.html", reportFilenameGlob("${timestamp}"));
        assertTrue(resolveReportFilename("${timestamp}").matches("\\d{8}_\\d{6}\\.html"),
                resolveReportFilename("${timestamp}"));
    }

    @Test
    void aRejectedNameContainingTheTimestampFallsBackWithoutLeakingTheStamp() {
        // Validation runs on the string with ${timestamp} removed, so a hostile name is caught
        // regardless of where the placeholder sits.
        assertEquals(DEFAULT, resolveReportFilename("../${timestamp}.html"));
        assertEquals(DEFAULT, resolveReportFilename("rep:${timestamp}.html"));
    }

    // ---- the payload filename (F1) -------------------------------------------

    @Test
    void thePayloadNameIsDerivedFromTheReportName() {
        // Two large reports in one directory used to share one payload file, so the second run
        // won and the first report rendered the other run's charts under its own header cards.
        assertEquals("alpha-data.js",
                WebInsightReportGenerator.externalDataFilename("alpha.html"));
        assertEquals("beta-data.js",
                WebInsightReportGenerator.externalDataFilename("beta.html"));
        assertEquals("run_20260101_010101-data.js",
                WebInsightReportGenerator.externalDataFilename("run_20260101_010101.html"));
    }

    @Test
    void theDefaultReportNameKeepsTheDocumentedPayloadName() {
        // Not cosmetic: E20 in run-all-tests.sh asserts this literal name, both that the file
        // exists and that the HTML references it, and README/FEATURES.md document it. Deriving
        // it would have renamed the payload for every default run.
        assertEquals("web-insight-data.js",
                WebInsightReportGenerator.externalDataFilename("web-insight-report.html"));
    }

    @Test
    void twoDifferentReportsCannotShareAPayload() {
        assertNotEquals(
                WebInsightReportGenerator.externalDataFilename(resolveReportFilename("alpha")),
                WebInsightReportGenerator.externalDataFilename(resolveReportFilename("beta")));
    }

    // ---- the glob and the resolver agree -------------------------------------

    @Test
    void theGlobAndTheResolverAgreeOnEveryRejection() {
        // The exporter asks reportFilenameGlob what the listener would have written. If the two
        // disagreed about a rejected name, the collision check would look for a file that could
        // never exist and both generators would run.
        for (String name : new String[]{"../../evil.html", "rep:ort.html", "index.html",
                "statistics", ".html", "rep*ort.html", "CON.html", "report ", "report.",
                null, ""}) {
            assertEquals(resolveReportFilename(name), reportFilenameGlob(name),
                    "resolver and glob disagree for: " + name);
        }
    }

    // ---- the platform-divergent names the character allowlist still admitted ------

    /**
     * Windows silently strips a trailing dot or space rather than rejecting the name.
     *
     * <p>So {@code "report "} either fails or quietly becomes {@code "report"} — and behaves
     * perfectly on Linux. That is the same one-configuration-two-platforms class the separator
     * and colon checks already exist to close, which is why it is rejected everywhere rather
     * than only where it breaks.
     */
    @Test
    void aTrailingDotOrSpaceIsRejected() {
        assertEquals(DEFAULT, resolveReportFilename("report "));
        assertEquals(DEFAULT, resolveReportFilename("report."));
        assertEquals(DEFAULT, resolveReportFilename("my report  "));

        // ...but they are perfectly fine anywhere else in the name.
        assertEquals("my report.html", resolveReportFilename("my report"));
        assertEquals("report.2026.html", resolveReportFilename("report.2026"));
    }

    /**
     * Names Windows resolves to a device, in any directory, with or without an extension.
     *
     * <p>{@code CON.html} and {@code COM1.html} pass a character allowlist and then fail — or
     * worse, silently succeed while writing to a device — only on Windows. The rule is not
     * "equals CON": everything up to the first dot counts, so {@code CON.anything} is the device
     * too.
     */
    @Test
    void windowsDeviceNamesAreRejectedOnEveryPlatform() {
        for (String device : new String[]{"CON", "con", "PRN", "AUX", "NUL", "nul",
                "COM1", "com9", "LPT1", "lpt9"}) {
            assertEquals(DEFAULT, resolveReportFilename(device),
                    device + " is a device, not a file");
            assertEquals(DEFAULT, resolveReportFilename(device + ".html"),
                    device + ".html is still that device");
        }
        assertEquals(DEFAULT, resolveReportFilename("CON.report.html"),
                "everything up to the first dot decides");

        // Near-misses that are ordinary filenames and must survive.
        assertEquals("console.html", resolveReportFilename("console"));
        assertEquals("com10.html", resolveReportFilename("com10"));
        assertEquals("my-con.html", resolveReportFilename("my-con"));
        assertEquals("nullable.html", resolveReportFilename("nullable"));
    }

    /**
     * Nothing bounded the resolved name, so a long one failed at write time with an NIO path
     * error — after the render, and with the {@code .json}, {@code .xml} and {@code -data.js}
     * siblings derived from it also over the limit.
     */
    @Test
    void anOverlongNameIsRejectedBeforeAnythingIsRendered() {
        StringBuilder tooLong = new StringBuilder();
        for (int i = 0; i < 181; i++) {
            tooLong.append('a');
        }
        assertEquals(DEFAULT, resolveReportFilename(tooLong.toString()));

        // The limit itself is fine — an off-by-one here would reject a legal name.
        String atTheLimit = tooLong.substring(0, 180);
        assertEquals(atTheLimit + ".html", resolveReportFilename(atTheLimit));
    }

    @Test
    void theLengthLimitIsMeasuredWithoutTheTimestampPlaceholder() {
        // ${timestamp} is ours and expands to 15 characters; the operator did not type 175 of
        // them. Validating the substituted string would reject names that are perfectly legal.
        //
        // This is the ONE check that still measures the stripped string, and it is the reason
        // resolveFilename keeps two of them. Every other check asks what the name will look like
        // on disk, where the stamp is fifteen real characters — which is why a trailing
        // ${timestamp} is no longer read as a trailing dot. The limit asks a different question:
        // whether the operator asked for too much. 180 is already a conservative reading of a
        // 255-byte component cap, so the fifteen characters of headroom this spends are there.
        StringBuilder name = new StringBuilder("${timestamp}");
        for (int i = 0; i < 170; i++) {
            name.append('a');
        }
        assertTrue(resolveReportFilename(name.toString()).endsWith("aaa.html"),
                "a name that is only over the limit once OUR placeholder expands must survive");
    }
}
