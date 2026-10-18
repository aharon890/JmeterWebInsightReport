package com.jmeterwebinsightreport.core.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The set, not the file.
 *
 * <p>Per-file atomicity says nothing about four files. Generation used to write HTML, then
 * serialise and write the payload, then serialise and write the JSON, then build and write the
 * XML — with all the expensive work happening BETWEEN the writes. A kill or a full disk anywhere
 * in that span left this run's HTML beside the previous run's JSON, with no concurrency involved
 * at all.
 */
class ArtifactPublicationTest {

    @TempDir
    Path dir;

    private File file(String name) {
        return new File(dir.toFile(), name);
    }

    private String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private long strayTempFiles() throws IOException {
        try (java.util.stream.Stream<Path> entries = Files.list(dir)) {
            return entries.filter(p -> p.getFileName().toString().startsWith(".")
                    && p.getFileName().toString().endsWith(".tmp")).count();
        }
    }

    @Test
    void nothingIsVisibleUntilPublish() throws IOException {
        File html = file("report.html");
        File json = file("report.json");

        try (ArtifactPublication publication = new ArtifactPublication()) {
            publication.stage(html, "<html>new</html>");
            publication.stage(json, "{\"new\":true}");

            assertFalse(html.exists(), "staged is not published");
            assertFalse(json.exists(), "staged is not published");

            assertTrue(publication.publish().isEmpty());

            assertEquals("<html>new</html>", read(html));
            assertEquals("{\"new\":true}", read(json));
        }
        assertEquals(0, strayTempFiles());
    }

    /**
     * The window that used to be the whole render is now four renames.
     *
     * <p>An abandoned publication — the process gave up between staging and publishing — must
     * leave the previous run's set completely intact. Before staging existed, this was the
     * mixed-set case: the HTML had already landed and the JSON never did.
     */
    @Test
    void anAbandonedPublicationLeavesThePreviousSetUntouched() throws IOException {
        File html = file("report.html");
        File json = file("report.json");
        AtomicFileWriter.write(html, "<html>PREVIOUS</html>");
        AtomicFileWriter.write(json, "{\"run\":\"PREVIOUS\"}");

        try (ArtifactPublication publication = new ArtifactPublication()) {
            publication.stage(html, "<html>NEW</html>");
            publication.stage(json, "{\"run\":\"NEW\"}");
            // ...and then something goes wrong before publish(). close() runs.
        }

        assertEquals("<html>PREVIOUS</html>", read(html));
        assertEquals("{\"run\":\"PREVIOUS\"}", read(json));
        assertEquals(0, strayTempFiles(),
                "and no debris — a leftover staging file makes the next -g -o run refuse to start");
    }

    @Test
    void publishOrderIsStagingOrder() throws IOException {
        // The payload is staged first and therefore published first: the page that loads it must
        // never appear in the directory ahead of it, or a reader opening the report mid-publish
        // gets a <script src> pointing at nothing.
        File data = file("web-insight-data.js");
        File html = file("report.html");

        try (ArtifactPublication publication = new ArtifactPublication()) {
            publication.stage(data, "window.__WIR_DATA__={};");
            publication.stage(html, "<html></html>");

            assertTrue(publication.isStaged(data));
            assertTrue(publication.isStaged(html));
            assertFalse(publication.isStaged(file("nothing.json")));

            publication.publish();
        }
        assertTrue(data.exists());
        assertTrue(html.exists());
    }

    /**
     * One artifact that cannot be published must not cost the operator the others.
     *
     * <p>Same judgement the JSON and XML writers have always made, applied to the publish step:
     * a report is still worth having when its CI/CD sibling fails. What must not happen is the
     * failure being silent, so every one is returned.
     */
    @Test
    void aFailedPublishIsReportedAndTheRestStillLand() throws IOException {
        File html = file("report.html");
        File blocked = file("report.json");
        assertTrue(blocked.mkdir(), "a directory in the .json's place — the move cannot succeed");

        List<ArtifactPublication.Failure> failures;
        try (ArtifactPublication publication = new ArtifactPublication()) {
            publication.stage(html, "<html>ok</html>");
            publication.stage(blocked, "{}");
            failures = publication.publish();
        }

        assertEquals(1, failures.size());
        assertEquals(blocked, failures.get(0).target());
        assertNotNull(failures.get(0).cause());

        // The message must name the TARGET. The NIO file exceptions carry the path they failed
        // on, which for a move is the SOURCE — so this reported itself as
        // "access denied: .report.json.26572-f3daf593.tmp", naming an internal staging file the
        // operator has never heard of, while the thing that was wrong was the destination.
        String message = failures.get(0).cause().getMessage();
        assertTrue(message.contains(blocked.getAbsolutePath()), message);
        assertFalse(message.contains(".tmp"), "the staging file is an implementation detail: "
                + message);
        assertEquals("<html>ok</html>", read(html), "the report still published");
        assertTrue(blocked.isDirectory(), "and the thing in the way is untouched");
        assertEquals(0, strayTempFiles(), "the unpublished staging file is still cleaned up");
    }

    /**
     * Nothing outlives the report.
     *
     * <p>With a directory sitting at the HTML's path, the HTML failed its rename and the CI/CD
     * JSON published happily behind it — leaving a machine-readable summary of a report that
     * does not exist, which a pipeline reads and believes. That is the mixed-set outcome this
     * class exists to prevent, arriving from the inside rather than from another process.
     */
    @Test
    void nothingIsPublishedOnceTheReportItselfHasFailed() throws IOException {
        File html = file("report.html");
        File json = file("report.json");
        assertTrue(html.mkdir(), "a directory where the report goes — the move cannot succeed");

        List<ArtifactPublication.Failure> failures;
        try (ArtifactPublication publication = new ArtifactPublication()) {
            publication.stage(html, "<html></html>");
            publication.stage(json, "{}");
            failures = publication.publish(html);
        }

        assertEquals(1, failures.size(), "it stopped at the report rather than carrying on");
        assertEquals(html, failures.get(0).target());
        assertFalse(json.exists(),
                "a summary of a report that never landed must not be left behind");
        assertEquals(0, strayTempFiles(), "and the abandoned staging files are cleaned up");
    }

    @Test
    void closingAfterAFullPublishIsHarmless() throws IOException {
        File html = file("report.html");
        ArtifactPublication publication = new ArtifactPublication();
        publication.stage(html, "<html></html>");
        assertTrue(publication.publish().isEmpty());

        publication.close();
        publication.close();

        assertTrue(html.exists(), "close must not undo a completed publish");
    }
}
