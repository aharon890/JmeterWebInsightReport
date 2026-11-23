package com.jmeterwebinsightreport.core.rundata;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.util.MinimalPrettyPrinter;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Writes run data (contracts/run-data-format.md): the {@code run} record first, then one record per line, LF, UTF-8.
 * A file whose name ends in {@code .gz} is gzip-compressed. Only fields a producer gave are written, so a reader
 * applies the same defaults the feed applies. Safe to call from several threads: a listener's samplers write at once.
 */
public final class RunDataWriter implements Closeable {

    private static final JsonFactory JSON = new JsonFactory();

    private final Writer out;
    private final JsonGenerator gen;

    public RunDataWriter(File file, RunHeader header) throws IOException {
        this(open(file), header);
    }

    public RunDataWriter(OutputStream stream, RunHeader header) throws IOException {
        this.out = new OutputStreamWriter(stream, StandardCharsets.UTF_8);
        this.gen = JSON.createGenerator(out);
        // One record per line: no separator between root values but the LF written after each
        gen.setPrettyPrinter(new MinimalPrettyPrinter(""));
        header(header);
    }

    private static OutputStream open(File file) throws IOException {
        OutputStream os = new BufferedOutputStream(new FileOutputStream(file));
        return file.getName().endsWith(".gz") ? new GZIPOutputStream(os, 1 << 16) : os;
    }

    private void header(RunHeader h) throws IOException {
        gen.writeStartObject();
        gen.writeStringField("type", RunDataFormat.TYPE_RUN);
        gen.writeStringField("formatVersion", h.formatVersion == null ? RunDataFormat.VERSION : h.formatVersion);
        gen.writeObjectFieldStart("tool");
        gen.writeStringField("name", h.toolName);
        if (h.toolVersion != null) {
            gen.writeStringField("version", h.toolVersion);
        }
        gen.writeEndObject();
        gen.writeStringField("testName", h.testName);
        gen.writeObjectFieldStart("producer");
        gen.writeStringField("name", h.producerName);
        if (h.producerVersion != null) {
            gen.writeStringField("version", h.producerVersion);
        }
        gen.writeEndObject();
        if (h.timeResolution != null && !"ms".equals(h.timeResolution)) {
            gen.writeStringField("timeResolution", h.timeResolution);
        }
        if (h.notMeasured != null && !h.notMeasured.isEmpty()) {
            gen.writeArrayFieldStart("notMeasured");
            for (String f : h.notMeasured) {
                gen.writeString(f);
            }
            gen.writeEndArray();
        }
        end();
    }

    public synchronized void sample(RunSample s) throws IOException {
        gen.writeStartObject();
        gen.writeStringField("type", RunDataFormat.TYPE_SAMPLE);
        string("id", s.id);
        string("parent", s.parent);
        if (s.parent != null && s.link == RunSample.Link.SUBRESULT) {
            gen.writeStringField("link", "subresult");
        }
        if (s.kind == RunSample.Kind.TRANSACTION) {
            gen.writeStringField("kind", "transaction");
        }
        gen.writeStringField("name", s.name);
        gen.writeNumberField("start", s.start);
        number("elapsed", s.elapsed);
        if (s.idle != null && s.idle != 0) {
            gen.writeNumberField("idle", s.idle);
        }
        number("wallTime", s.wallTime);
        if (s.success != null) {
            gen.writeBooleanField("success", s.success);
        }
        string("code", s.code);
        string("message", s.message);
        string("failureMessage", s.failureMessage);
        string("user", s.user);
        if (s.iteration != null) {
            gen.writeNumberField("iteration", s.iteration);
        }
        if (s.topLevel != null) {
            gen.writeBooleanField("topLevel", s.topLevel);
        }
        if (s.samples != 1) {
            gen.writeNumberField("samples", s.samples);
        }
        number("errors", s.errors);
        number("bytes", s.bytes);
        number("sentBytes", s.sentBytes);
        number("latency", s.latency);
        number("connect", s.connect);
        if (s.threads != null) {
            gen.writeNumberField("threads", s.threads);
        }
        string("url", s.url);
        string("method", s.method);
        if (s.timings != null && !s.timings.isEmpty()) {
            gen.writeObjectFieldStart("timings");
            for (Map.Entry<String, Double> t : s.timings.entrySet()) {
                // Hundredths written as such, with no float noise: 31.29, 0.15, 30
                gen.writeFieldName(t.getKey());
                gen.writeNumber(java.math.BigDecimal.valueOf(RunSample.hundredths(t.getValue()), 2)
                        .stripTrailingZeros().toPlainString());
            }
            gen.writeEndObject();
        }
        string("responseBody", s.responseBody);
        end();
    }

    public synchronized void users(long time, int active) throws IOException {
        gen.writeStartObject();
        gen.writeStringField("type", RunDataFormat.TYPE_USERS);
        gen.writeNumberField("time", time);
        gen.writeNumberField("active", active);
        end();
    }

    public synchronized void check(ToolRecords.Check c) throws IOException {
        gen.writeStartObject();
        gen.writeStringField("type", RunDataFormat.TYPE_CHECK);
        gen.writeNumberField("time", c.time);
        gen.writeStringField("name", c.name);
        string("group", c.group);
        gen.writeBooleanField("passed", c.passed);
        string("user", c.user);
        if (c.iteration != null) {
            gen.writeNumberField("iteration", c.iteration);
        }
        end();
    }

    public synchronized void threshold(ToolRecords.Threshold t) throws IOException {
        gen.writeStartObject();
        gen.writeStringField("type", RunDataFormat.TYPE_THRESHOLD);
        gen.writeStringField("metric", t.metric);
        gen.writeStringField("expression", t.expression);
        gen.writeBooleanField("passed", t.passed);
        end();
    }

    public synchronized void counter(String name, double value) throws IOException {
        gen.writeStartObject();
        gen.writeStringField("type", RunDataFormat.TYPE_COUNTER);
        gen.writeStringField("name", name);
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            gen.writeNumberField("value", (long) value);
        } else {
            gen.writeNumberField("value", value);
        }
        end();
    }

    private void string(String field, String value) throws IOException {
        if (value != null) {
            gen.writeStringField(field, value);
        }
    }

    private void number(String field, Long value) throws IOException {
        if (value != null) {
            gen.writeNumberField(field, value);
        }
    }

    private void end() throws IOException {
        gen.writeEndObject();
        gen.writeRaw('\n');
    }

    @Override
    public synchronized void close() throws IOException {
        gen.close();
        out.close();
    }
}
