package com.jmeterwebinsightreport.core.servermetrics.collect;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Decision 12: a CSV's layout and interval, read off the file when no flag gives them. */
class CsvShapeTest {

    @TempDir
    Path dir;

    private File csv(String text) throws Exception {
        Path p = dir.resolve("f" + text.hashCode() + ".csv");
        Files.write(p, text.getBytes(StandardCharsets.UTF_8));
        return p.toFile();
    }

    @Test
    void aLongFilesStepIsItsMostFrequentGapPerSeriesBlankValuesIncluded() throws Exception {
        StringBuilder sb = new StringBuilder("time,component,metric,instance,value\n");
        for (long t = 1700000000000L; t <= 1700000060000L; t += 5000) {
            sb.append(t).append(",app,cpu,a,50\n");
            sb.append(t).append(",app,cpu,b,\n");   // a gap in the values, still the export's interval
        }
        CsvShape s = CsvShape.sniff(csv(sb.toString()), null);
        assertFalse(s.wide());
        assertEquals(5000, s.stepMillis());
    }

    @Test
    void isoTimesWithOrWithoutAZoneAndInterleavedSeriesMeasureTheSame() throws Exception {
        String text = "time,component,metric,value\n"
                + "2026-10-20T10:00:00Z,app,cpu,1\n2026-10-20T10:00:00Z,db,conn,1\n"
                + "2026-10-20T10:00:10Z,app,cpu,1\n2026-10-20T10:00:10Z,db,conn,1\n"
                + "2026-10-20T10:00:20Z,app,cpu,1\n2026-10-20T10:00:20Z,db,conn,1\n";
        assertEquals(10000, CsvShape.sniff(csv(text), null).stepMillis());
        assertEquals(10000, CsvShape.sniff(csv(text.replace("Z,", ",")), null).stepMillis());
    }

    @Test
    void grafanasWideHeaderIsWideAndALongOneOrAnAmbiguousOneIsNot() throws Exception {
        CsvShape wide = CsvShape.sniff(csv("Time,app/cpu,app/mem/node-1\n"
                + "1700000000000,1,2\n1700000015000,1,2\n1700000030000,1,2\n1700000045000,1,2\n"), null);
        assertTrue(wide.wide());
        assertEquals(15000, wide.stepMillis());
        assertFalse(CsvShape.looksWide(new String[] {"Time", "cpu", "mem"}));            // raw names: not guessed
        assertFalse(CsvShape.looksWide(new String[] {"time", "component", "app/cpu"}));
        assertFalse(CsvShape.looksWide(new String[] {"timestamp", "app/cpu"}));
    }

    @Test
    void tooFewGapsNoTimeColumnOrAnUnreadableFileMeasureNothing() throws Exception {
        assertEquals(0, CsvShape.sniff(csv("time,component,metric,value\n1700000000000,a,b,1\n1700000001000,a,b,1\n"),
                null).stepMillis());
        assertEquals(0, CsvShape.sniff(csv("when,component,metric,value\n1,a,b,1\n2,a,b,1\n3,a,b,1\n4,a,b,1\n"),
                null).stepMillis());
        assertEquals(0, CsvShape.sniff(dir.resolve("missing.csv").toFile(), null).stepMillis());
    }

    @Test
    void aMappedTimeColumnAndInfluxAnnotationLinesAreRead() throws Exception {
        String influx = "#group,false,false,true\n#datatype,string,long,dateTime:RFC3339\n#default,_result,,\n"
                + ",result,table,_time,_value,_field,host\n"
                + ",,0,2026-10-20T10:00:00Z,1,cpu,h1\n,,0,2026-10-20T10:00:30Z,1,cpu,h1\n"
                + ",,0,2026-10-20T10:01:00Z,1,cpu,h1\n,,0,2026-10-20T10:01:30Z,1,cpu,h1\n";
        assertEquals(30000, CsvShape.sniff(csv(influx), "_time").stepMillis());
    }
}
