package com.jmeterwebinsightreport.report.template;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmeterwebinsightreport.core.assertion.SamplerWords;
import com.jmeterwebinsightreport.core.model.ReportData;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The page's words per tool (spec 001, decision 7, contracts/page-words.md): one table,
 * {@code templates/page-words.json}, with a column per tool. A JMeter run's column is today's text exactly; a k6 run's
 * is k6's own words (Requests, VU, Max VUs, Group); any other tool gets the neutral column with its name. The renderer
 * puts the run's column in the template's context ({@code words}) and in the page ({@code window.WIR_WORDS}) for
 * report.js, so the template and the script read one table. A later tool adds a column, with no other change.
 */
public final class PageWords {

    static final String RESOURCE = "/templates/page-words.json";
    static final String JMETER = "jmeter";
    static final String OTHER = "other";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Map<String, String>> TABLE = load();

    private PageWords() {
    }

    /** The words of a run recorded by {@code toolName}: JMeter's when it is null or {@code jmeter}. */
    public static Map<String, String> forTool(String toolName) {
        String tool = toolName == null || toolName.trim().isEmpty() ? JMETER : toolName.trim().toLowerCase();
        Map<String, String> column = TABLE.get(TABLE.containsKey(tool) ? tool : OTHER);
        Map<String, String> words = new LinkedHashMap<>();
        column.forEach((k, v) -> words.put(k, v.replace("{tool}", tool)));
        return Collections.unmodifiableMap(words);
    }

    /**
     * The judge's word for a sampler on a run {@code toolName} recorded: this table's {@code sampler} and
     * {@code samplers}, so the few sentences the judge writes into the CI/CD JSON and JUnit say what the page says
     * ("request" on a k6 run; contracts/page-words.md, done 2026-11-26).
     */
    public static SamplerWords samplerWords(String toolName) {
        return SamplerWords.of(forTool(toolName));
    }

    /** {@link #samplerWords(String)} for the tool that recorded {@code run}: JMeter's when there is none. */
    public static SamplerWords samplerWords(ReportData run) {
        return samplerWords(run == null || run.getMetadata() == null ? null : run.getMetadata().getToolName());
    }

    /** The words as the page's script reads them: a JSON object. */
    public static String json(Map<String, String> words) {
        try {
            return JSON.writeValueAsString(words);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("the page's words could not be written as JSON", e);
        }
    }

    static List<String> columns() {
        return new ArrayList<>(TABLE.keySet());
    }

    static Map<String, String> column(String column) {
        return TABLE.get(column);
    }

    static Set<String> keys() {
        return TABLE.get(JMETER).keySet();
    }

    private static Map<String, Map<String, String>> load() {
        try (InputStream in = PageWords.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                // A missing table would put keys on the page instead of words: refuse, as a missing template is
                throw new IllegalStateException("the report's words table is missing: " + RESOURCE);
            }
            JsonNode root = JSON.readTree(in);
            Map<String, Map<String, String>> table = new LinkedHashMap<>();
            for (JsonNode c : root.path("columns")) {
                table.put(c.asText(), new LinkedHashMap<>());
            }
            Iterator<Map.Entry<String, JsonNode>> words = root.path("words").fields();
            while (words.hasNext()) {
                Map.Entry<String, JsonNode> w = words.next();
                for (Map.Entry<String, Map<String, String>> c : table.entrySet()) {
                    JsonNode v = w.getValue().get(c.getKey());
                    if (v != null && v.isTextual()) {
                        c.getValue().put(w.getKey(), v.asText());
                    }
                }
            }
            if (!table.containsKey(JMETER) || !table.containsKey(OTHER)) {
                throw new IllegalStateException(RESOURCE + " needs a jmeter and an other column: " + table.keySet());
            }
            Set<String> keys = new LinkedHashSet<>(table.get(JMETER).keySet());
            table.replaceAll((k, v) -> Collections.unmodifiableMap(v));
            for (Map.Entry<String, Map<String, String>> c : table.entrySet()) {
                if (!c.getValue().keySet().equals(keys)) {
                    throw new IllegalStateException(RESOURCE + ": the " + c.getKey() + " column has other keys than "
                            + "jmeter's");
                }
            }
            return Collections.unmodifiableMap(table);
        } catch (IOException e) {
            throw new UncheckedIOException("the report's words table could not be read: " + RESOURCE, e);
        }
    }
}
