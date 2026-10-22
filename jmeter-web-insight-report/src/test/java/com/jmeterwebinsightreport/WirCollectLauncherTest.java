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
        assertEquals(dir.resolve("jm").toFile().getAbsoluteFile(), WirCollectLauncher.jmeterHome(jar, null, null));
    }

    @Test
    void elsewhereJmeterHomeIsReadAndAWrongOneIsAnErrorNeverAGuess() throws Exception {
        File loose = Files.write(dir.resolve("jmeter-web-insight-report-4.1.0.jar"), new byte[] {1}).toFile();
        WirCollectLauncher.Refusal unset = assertThrows(WirCollectLauncher.Refusal.class,
                () -> WirCollectLauncher.jmeterHome(loose, null, null));
        assertTrue(unset.getMessage().contains("JMETER_HOME is unset"), unset.getMessage());
        assertTrue(unset.getMessage().contains("is not on PATH"), unset.getMessage());
        // A wrong JMETER_HOME is an error even with a JMeter on PATH - and so is a blank one, which the
        // script refuses as set.
        String path = binWithJmeter("onpath").toString();
        WirCollectLauncher.Refusal wrong = assertThrows(WirCollectLauncher.Refusal.class,
                () -> WirCollectLauncher.jmeterHome(loose, dir.resolve("nowhere").toString(), path));
        assertTrue(wrong.getMessage().contains("has no lib/ext"), wrong.getMessage());
        WirCollectLauncher.Refusal blank = assertThrows(WirCollectLauncher.Refusal.class,
                () -> WirCollectLauncher.jmeterHome(loose, "  ", path));
        assertEquals("JMETER_HOME is set to '  ', which has no lib/ext - point it at the JMeter installation"
                + " (the folder holding bin and lib)", blank.getMessage());
        jar("jm", "x.jar");
        assertEquals(dir.resolve("jm").toFile(), WirCollectLauncher.jmeterHome(loose, dir.resolve("jm").toString(), path));
        // Homebrew's opt prefix: the install is its libexec, as the script's normalize_jmeter_home reads it.
        Path brew = dir.resolve("opt-jmeter");
        Files.createDirectories(brew.resolve("libexec").resolve("lib").resolve("ext"));
        assertEquals(brew.resolve("libexec").toFile(), WirCollectLauncher.jmeterHome(loose, brew.toString(), path));
    }

    private static final String LAUNCHER = File.pathSeparatorChar == ';' ? "jmeter.bat" : "jmeter";

    /** An executable {@code jmeter[.bat]} at {@code file}. */
    private static void launcher(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[] {1});
        assertTrue(file.toFile().setExecutable(true) || File.pathSeparatorChar == ';');
    }

    /** {@code <dir>/<home>/bin/jmeter[.bat]} in a JMeter install (a lib/ext beside bin); returns the bin. */
    private Path binWithJmeter(String home) throws Exception {
        jar(home, "x.jar");
        Path bin = dir.resolve(home).resolve("bin");
        launcher(bin.resolve(LAUNCHER));
        return bin;
    }

    @Test
    void withJmeterHomeUnsetTheJmeterOnPathIsTheInstallAsWirCollectShFindsIt() throws Exception {
        // F13: the old bat/sh pair found JMeter on PATH; the launcher does now, for the .bat and java -jar.
        File loose = Files.write(dir.resolve("jmeter-web-insight-report-4.1.0.jar"), new byte[] {1}).toFile();
        Path bin = binWithJmeter("jm");
        String path = String.join(File.pathSeparator, dir.resolve("empty-dir").toString(), "", "\"" + bin + "\"");
        assertEquals(dir.resolve("jm").toFile().getCanonicalFile(),
                WirCollectLauncher.jmeterHome(loose, null, path).getCanonicalFile());
        assertEquals(dir.resolve("jm").toFile().getCanonicalFile(),
                WirCollectLauncher.jmeterHome(loose, "", path).getCanonicalFile());
        // Homebrew: <prefix>/bin/jmeter with the install under <prefix>/libexec.
        Path brew = dir.resolve("brew");
        Files.createDirectories(brew.resolve("libexec").resolve("lib").resolve("ext"));
        launcher(brew.resolve("bin").resolve(LAUNCHER));
        assertEquals(brew.resolve("libexec").toFile().getCanonicalFile(),
                WirCollectLauncher.jmeterHome(loose, null, brew.resolve("bin").toString()).getCanonicalFile());
        // A jmeter on PATH that is in no install's bin: said, naming it.
        Path stray = dir.resolve("tools");
        launcher(stray.resolve(LAUNCHER));
        WirCollectLauncher.Refusal notInstall = assertThrows(WirCollectLauncher.Refusal.class,
                () -> WirCollectLauncher.jmeterHome(loose, null, stray.toString()));
        assertTrue(notInstall.getMessage().startsWith("JMETER_HOME is unset, and `jmeter` on PATH ("), notInstall.getMessage());
    }

    @Test
    void aJmeterOnPathThatIsASymlinkIsFollowedToItsInstall() throws Exception {
        // Homebrew's <prefix>/bin/jmeter -> ../Cellar/jmeter/<v>/bin/jmeter, whose install is the
        // Cellar's libexec; <prefix> itself holds neither lib/ext nor libexec.
        File loose = Files.write(dir.resolve("jmeter-web-insight-report-4.1.0.jar"), new byte[] {1}).toFile();
        Path cellar = dir.resolve("cellar");
        Files.createDirectories(cellar.resolve("libexec").resolve("lib").resolve("ext"));
        launcher(cellar.resolve("bin").resolve(LAUNCHER));
        Path prefixBin = dir.resolve("prefix").resolve("bin");
        Files.createDirectories(prefixBin);
        try {
            Files.createSymbolicLink(prefixBin.resolve(LAUNCHER), cellar.resolve("bin").resolve(LAUNCHER));
        } catch (Exception noSymlinks) {   // Windows without developer mode
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symbolic links cannot be made here");
        }
        assertEquals(cellar.resolve("libexec").toFile().getCanonicalFile(),
                WirCollectLauncher.jmeterHome(loose, null, prefixBin.toString()).getCanonicalFile());
    }

    @Test
    void aJmeterWithoutTheExecuteBitIsPassedOverAsTheShellPassesItOver() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(File.pathSeparatorChar != ';', "no execute bit on Windows");
        File loose = Files.write(dir.resolve("jmeter-web-insight-report-4.1.0.jar"), new byte[] {1}).toFile();
        jar("old", "x.jar");
        Path oldBin = dir.resolve("old").resolve("bin");
        Files.createDirectories(oldBin);
        Files.write(oldBin.resolve("jmeter"), new byte[] {1});
        assertTrue(oldBin.resolve("jmeter").toFile().setExecutable(false, false));
        Path bin = binWithJmeter("new");
        assertEquals(dir.resolve("new").toFile().getCanonicalFile(), WirCollectLauncher.jmeterHome(loose, null,
                oldBin + File.pathSeparator + bin).getCanonicalFile());
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

    @Test
    void aPluginJarRunFromOutsideLibExtIsTheOneThatRunsWhateverLibExtHolds() throws Exception {
        File installed = jar("jm", "jmeter-web-insight-report-4.0.15.jar");
        File ext = installed.getParentFile();
        Path release = dir.resolve("release");
        Files.createDirectories(release);
        File loose = Files.write(release.resolve("jmeter-web-insight-report-4.1.0.jar"), new byte[] {1}).toFile();
        assertEquals(loose, WirCollectLauncher.onePluginJar(ext, loose));
        jar("jm", "jmeter-web-insight-report-4.0.9.jar");   // two in lib/ext: not what is running
        assertEquals(loose, WirCollectLauncher.onePluginJar(ext, loose));
        Path empty = dir.resolve("bare").resolve("lib").resolve("ext");
        Files.createDirectories(empty);
        assertEquals(loose, WirCollectLauncher.onePluginJar(empty.toFile(), loose));
    }

    @Test
    void aPluginJarOutsideLibExtWithAnotherOnTheClasspathFromItsFolderIsRefusedNamingThem() throws Exception {
        // wir-collect.bat's classpath starts with its own folder's *, so the JVM ran either.
        File installed = jar("jm", "jmeter-web-insight-report-4.0.15.jar");
        Path release = dir.resolve("release");
        Files.createDirectories(release);
        File loose = Files.write(release.resolve("jmeter-web-insight-report-4.1.0.jar"), new byte[] {1}).toFile();
        File older = Files.write(release.resolve("jmeter-web-insight-report-4.0.9.jar"), new byte[] {1}).toFile();
        String bat = String.join(File.pathSeparator, older.getPath(), loose.getPath(), installed.getPath());
        WirCollectLauncher.Refusal two = assertThrows(WirCollectLauncher.Refusal.class,
                () -> WirCollectLauncher.onePluginJar(installed.getParentFile(), loose, bat));
        assertEquals(release.toFile().getCanonicalFile() + " holds 2 plugin JARs (jmeter-web-insight-report-4.0.9.jar,"
                + " jmeter-web-insight-report-4.1.0.jar) - the java command would run whichever comes first; keep"
                + " exactly one beside wir-collect", two.getMessage());
        // java -jar names its one JAR: a folder of several releases is no ambiguity there.
        assertEquals(loose, WirCollectLauncher.onePluginJar(installed.getParentFile(), loose, loose.getPath()));
        // lib/ext's JAR later on the bat's classpath is not from the folder, and changes nothing.
        String one = String.join(File.pathSeparator, loose.getPath(), installed.getPath());
        assertEquals(loose, WirCollectLauncher.onePluginJar(installed.getParentFile(), loose, one));
    }

    @Test
    void theFolderIsComparedAsTheFileSystemNamesItNotAsItIsSpelt() throws Exception {
        // The .bat called by a Windows 8.3 short path: the JVM expands %~dp0* in that spelling, while the
        // JAR's own location comes back long, and two JARs beside it ran whichever came first. Here a
        // `..` spelling stands in for the short one on every OS; on Windows the short name is tried too.
        File installed = jar("jm", "jmeter-web-insight-report-4.0.15.jar");
        Path release = dir.resolve("release folder");
        Files.createDirectories(release);
        File loose = Files.write(release.resolve("jmeter-web-insight-report-4.1.0.jar"), new byte[] {1}).toFile();
        Files.write(release.resolve("jmeter-web-insight-report-4.0.9.jar"), new byte[] {1});
        String spelt = dir.resolve("jm").resolve("..").resolve("release folder").toString();
        String bat = String.join(File.pathSeparator, spelt + File.separator + "jmeter-web-insight-report-4.0.9.jar",
                spelt + File.separator + "jmeter-web-insight-report-4.1.0.jar");
        WirCollectLauncher.Refusal two = assertThrows(WirCollectLauncher.Refusal.class,
                () -> WirCollectLauncher.onePluginJar(installed.getParentFile(), loose, bat));
        assertTrue(two.getMessage().contains("holds 2 plugin JARs"), two.getMessage());
        // One JAR named twice, in two spellings, is still one JAR.
        String same = String.join(File.pathSeparator, loose.getPath(), spelt + File.separator + loose.getName());
        assertEquals(loose, WirCollectLauncher.onePluginJar(installed.getParentFile(), loose, same));

        String shortName = windowsShortName(release);
        if (shortName != null && !shortName.equalsIgnoreCase(release.toString())) {
            String shortBat = String.join(File.pathSeparator, shortName + "\\jmeter-web-insight-report-4.0.9.jar",
                    shortName + "\\jmeter-web-insight-report-4.1.0.jar");
            WirCollectLauncher.Refusal viaShort = assertThrows(WirCollectLauncher.Refusal.class,
                    () -> WirCollectLauncher.onePluginJar(installed.getParentFile(), loose, shortBat));
            assertTrue(viaShort.getMessage().contains("holds 2 plugin JARs"), viaShort.getMessage());
        }
    }

    /** The folder's 8.3 short path on Windows, as cmd's {@code %~s} gives it; null elsewhere or when it has none. */
    private static String windowsShortName(Path folder) throws Exception {
        if (File.pathSeparatorChar != ';') {
            return null;
        }
        Process p = new ProcessBuilder("cmd", "/c", "for %I in (\"" + folder + "\") do @echo %~sI")
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        return p.waitFor() == 0 && !out.isEmpty() && !out.contains(" ") && new File(out).isDirectory() ? out : null;
    }
}
