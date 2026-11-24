package com.jmeterwebinsightreport.report.rundata;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** The JTLs the JMeter paths are held to (spec 001, baseline-4.4.1): committed files and JtlParserTest's shapes. */
final class BaselineInputs {

    static final String HEADER = "timeStamp,elapsed,label,responseCode,responseMessage,"
            + "threadName,dataType,success,failureMessage,bytes,sentBytes,grpThreads,allThreads,"
            + "URL,Latency,IdleTime,Connect";

    private BaselineInputs() {
    }

    static String tx(int n, int failing) {
        return "\"Number of samples in transaction : " + n + ", number of failing samples : " + failing + "\"";
    }

    /** Every input by its baseline name; the inline ones are written into {@code tmp}. */
    static Map<String, File> jtls(Path tmp) throws IOException {
        Map<String, File> m = new LinkedHashMap<>();
        m.put("goldens-plain", new File("../test-plans/goldens/plain.jtl"));
        for (String n : new String[]{"high-error-rate", "malformed-rows", "missing-optional-columns", "quoted-fields",
                "single-sampler", "transaction-controllers", "valid-small"}) {
            m.put("core-" + n, new File("../jmeter-report-core/src/test/resources/jtl/" + n + ".jtl"));
        }
        // JtlParserTest's two cut-off shapes (a controller the test end or a "Stop Thread" cut off)
        m.put("cut-off-at-end", write(tmp, "cut-off.jtl", HEADER + "\n"
                + "1700000000000,300,Search,200," + tx(1, 0) + ",TG 1-1,,true,,300,30,1,1,,0,0,0\n"
                + "1700000000000,300,A,200,OK,TG 1-1,text,true,,300,30,1,1,http://x/a,20,0,5\n"
                + "1700000001000,300,Search,200,,TG 1-1,,true,,300,30,1,1,,0,0,0\n"
                + "1700000001000,150,A,200,OK,TG 1-1,text,true,,300,30,1,1,http://x/a,20,0,5\n"
                + "1700000001150,150,B,200,OK,TG 1-1,text,true,,300,30,1,1,http://x/b,20,0,5\n"));
        m.put("cut-off-before-completed", write(tmp, "stopped-first.jtl", HEADER + "\n"
                + "1700000000000,598,Checkout,500,,Stopper 1-1,,false,,0,0,1,2,,0,0,0\n"
                + "1700000000000,598,Pay,500,Error,Stopper 1-1,text,false,,0,0,1,2,http://x/pay,0,0,0\n"
                + "1700000001000,300,Checkout,200," + tx(1, 0) + ",Shopper 2-1,,true,,0,0,1,1,,0,0,0\n"
                + "1700000001000,300,Pay,200,OK,Shopper 2-1,text,true,,0,0,1,1,http://x/pay,0,0,0\n"));
        m.put("statistical", write(tmp, "statistical.jtl",
                "timeStamp,elapsed,label,responseCode,responseMessage,threadName,success,SampleCount,ErrorCount\n"
                + "1700000000000,500,Search,500,Internal Server Error,TG 1-1,false,5,3\n"
                + "1700000001000,200,Search,200,OK,TG 1-1,true,2,0\n"));
        return m;
    }

    private static File write(Path tmp, String name, String text) throws IOException {
        Path p = tmp.resolve(name);
        Files.writeString(p, text, StandardCharsets.UTF_8);
        return p.toFile();
    }
}
