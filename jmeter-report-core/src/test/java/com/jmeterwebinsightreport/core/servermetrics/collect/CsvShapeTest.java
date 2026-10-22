package com.jmeterwebinsightreport.core.servermetrics.collect;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Decision 12: a CSV's layout, read off its header when no flag gives it. The step is measured from
 * the reader's own index ({@code CsvSourceTest}'s measured-step cases).
 */
class CsvShapeTest {

    @TempDir
    Path dir;

    private File csv(String text) throws Exception {
        Path p = dir.resolve("f" + text.hashCode() + ".csv");
        Files.write(p, text.getBytes(StandardCharsets.UTF_8));
        return p.toFile();
    }

    @Test
    void grafanasWideHeaderIsWideAndALongOneOrAnAmbiguousOneIsNot() throws Exception {
        assertTrue(CsvShape.looksWide(csv("Time,app/cpu,app/mem/node-1\n1700000000000,1,2\n")));
        assertFalse(CsvShape.looksWide(csv("time,component,metric,value\n1700000000000,a,b,1\n")));
        assertFalse(CsvShape.looksWide(new String[] {"Time", "cpu", "mem"}));            // raw names: not guessed
        assertFalse(CsvShape.looksWide(new String[] {"time", "component", "app/cpu"}));
        assertFalse(CsvShape.looksWide(new String[] {"timestamp", "app/cpu"}));
    }

    @Test
    void theHeaderIsTheLineTheReaderWouldTakeForOne() throws Exception {
        // A byte order mark, a blank line and a '#' line before it, and a quoted cell in it.
        String bom = String.valueOf((char) 0xFEFF);
        assertTrue(CsvShape.looksWide(csv(bom + "\n# exported from Grafana\nTime,\"app/cpu\",app/mem\n1,2,3\n")));
    }

    @Test
    void aFileThatCannotBeReadIsNotWide() throws Exception {
        assertFalse(CsvShape.looksWide(dir.resolve("missing.csv").toFile()));
        assertFalse(CsvShape.looksWide(csv("")));
    }
}
