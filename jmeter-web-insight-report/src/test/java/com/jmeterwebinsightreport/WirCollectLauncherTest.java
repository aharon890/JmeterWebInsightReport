package com.jmeterwebinsightreport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Decision 13: {@code java -jar <plugin jar>} finds JMeter and refuses what wir-collect.sh refuses,
 * each in a sentence. The one-JAR rule is checked by the launcher itself, so it holds even when a
 * wildcard classpath picked the launcher class out of whichever JAR sorts first.
 */
class WirCollectLauncherTest {

    @TempDir
    Path dir;

    private File jar(String home, String name) throws Exception {
        Path ext = dir.resolve(home).resolve("lib").resolve("ext");
        Files.createDirectories(ext);
        return Files.write(ext.resolve(name), new byte[] {1}).toFile();
    }

    @Test
    void jmeterIsFoundFromTheJarsPlaceInLibExt() throws Exception {
        File jar = jar("jm", "jmeter-web-insight-report-4.1.0.jar");
        assertEquals(dir.resolve("jm").toFile().getAbsoluteFile(), WirCollectLauncher.jmeterHome(jar, null));
    }

    @Test
    void elsewhereJmeterHomeIsReadAndAWrongOneIsAnErrorNeverAGuess() throws Exception {
        File loose = Files.write(dir.resolve("jmeter-web-insight-report-4.1.0.jar"), new byte[] {1}).toFile();
        WirCollectLauncher.Refusal unset = assertThrows(WirCollectLauncher.Refusal.class,
                () -> WirCollectLauncher.jmeterHome(loose, null));
        assertTrue(unset.getMessage().contains("JMETER_HOME is unset"), unset.getMessage());
        WirCollectLauncher.Refusal wrong = assertThrows(WirCollectLauncher.Refusal.class,
                () -> WirCollectLauncher.jmeterHome(loose, dir.resolve("nowhere").toString()));
        assertTrue(wrong.getMessage().contains("has no lib/ext"), wrong.getMessage());
        jar("jm", "x.jar");
        assertEquals(dir.resolve("jm").toFile(), WirCollectLauncher.jmeterHome(loose, dir.resolve("jm").toString()));
    }

    @Test
    void exactlyOnePluginJarOrASentenceNamingThem() throws Exception {
        File one = jar("jm", "jmeter-web-insight-report-4.1.0.jar");
        File ext = one.getParentFile();
        assertEquals(one, WirCollectLauncher.onePluginJar(ext, one));
        jar("jm", "jmeter-web-insight-report-4.0.9.jar");
        WirCollectLauncher.Refusal two = assertThrows(WirCollectLauncher.Refusal.class,
                () -> WirCollectLauncher.onePluginJar(ext, one));
        assertTrue(two.getMessage().contains("holds 2 plugin JARs"), two.getMessage());
        assertTrue(two.getMessage().contains("jmeter-web-insight-report-4.0.9.jar"), two.getMessage());

        Path empty = dir.resolve("empty").resolve("lib").resolve("ext");
        Files.createDirectories(empty);
        WirCollectLauncher.Refusal none = assertThrows(WirCollectLauncher.Refusal.class,
                () -> WirCollectLauncher.onePluginJar(empty.toFile(), new File("not-a-plugin.jar")));
        assertTrue(none.getMessage().startsWith("no jmeter-web-insight-report-*.jar in "), none.getMessage());
    }
}
