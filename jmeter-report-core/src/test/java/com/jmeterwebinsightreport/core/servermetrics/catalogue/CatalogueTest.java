package com.jmeterwebinsightreport.core.servermetrics.catalogue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmeterwebinsightreport.core.assertion.Predicate;
import com.jmeterwebinsightreport.core.servermetrics.Across;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The built-in catalogue, read as it ships. A malformed file is a build defect rather than a
 * user fault, so everything a reader of the catalogue trusts is checked here: that it loads,
 * that every metric says what it is, and that every query template expands into a query with
 * no slot left over and its brackets balanced — with a match and without one.
 */
class CatalogueTest {

    private final Catalogue catalogue = Catalogue.builtIn();

    /**
     * The page's copy is the shipped files, in index order: report.js resolves a metric to its
     * query from these, so a page built from anything else could resolve it differently.
     */
    @Test
    @SuppressWarnings("unchecked")
    void pageJsonIsTheShippedFilesInIndexOrder() {
        java.util.Map<String, Object> page = Catalogue.builtIn().pageJson();
        assertEquals(new ArrayList<>(Catalogue.builtIn().types().keySet()), page.get("order"));
        java.util.Map<String, Object> types = (java.util.Map<String, Object>) page.get("types");
        for (CatalogueType t : Catalogue.builtIn().types().values()) {
            java.util.Map<String, Object> file = (java.util.Map<String, Object>) types.get(t.key());
            assertNotNull(file, t.key());
            assertEquals(t.key(), file.get("type"));
            List<Object> metrics = (List<Object>) file.get("metrics");
            assertEquals(t.metrics().size(), metrics.size(), t.key() + ": every metric reaches the page");
        }
    }

    @Test
    void theBuiltInTypesLoadInIndexOrder() {
        assertEquals(Arrays.asList("postgres", "kafka", "redis", "host-linux", "aws-ec2", "oracle", "influxdb2",
                        "influxdb3", "jvm", "http-service", "kubernetes-pod", "mysql"),
                new ArrayList<>(catalogue.types().keySet()));
        assertEquals("postgres@1", catalogue.type("postgres").ref());
        assertEquals("kafka@1", catalogue.type("kafka").ref());
        assertEquals("redis@1", catalogue.type("redis").ref());
        assertEquals("host-linux@1", catalogue.type("host-linux").ref());
        assertEquals("aws-ec2@1", catalogue.type("aws-ec2").ref());
        assertEquals("oracle@1", catalogue.type("oracle").ref());
        assertEquals("influxdb2@1", catalogue.type("influxdb2").ref());
        assertEquals("influxdb3@1", catalogue.type("influxdb3").ref());
        assertEquals("jvm@1", catalogue.type("jvm").ref());
        assertEquals("http-service@1", catalogue.type("http-service").ref());
        assertEquals("kubernetes-pod@1", catalogue.type("kubernetes-pod").ref());
        assertEquals("mysql@1", catalogue.type("mysql").ref());
    }

    @Test
    void customIsKnownButHasNoCatalogue() {
        assertTrue(catalogue.knows("custom"));
        assertNull(catalogue.type("custom"));
        assertFalse(catalogue.knows("nosuchdb"));
        assertNull(catalogue.type("nosuchdb"));
        assertEquals("postgres, kafka, redis, host-linux, aws-ec2, oracle, influxdb2, influxdb3, jvm, http-service,"
                        + " kubernetes-pod, mysql, custom",
                catalogue.vocabulary());
    }

    @Test
    void everyMetricSaysWhatItIs() {
        for (CatalogueType type : catalogue.types().values()) {
            assertFalse(type.label().isEmpty(), type + " label");
            assertFalse(type.summary().isEmpty(), type + " summary");
            assertFalse(type.source().isEmpty(), type + " names no published list its metrics were written from");
            for (CatalogueMetric m : type.metrics().values()) {
                String where = type.key() + "." + m.name();
                assertTrue(m.name().matches("[a-z0-9_.]+"), where + ": a metric name is lower-case dotted");
                assertFalse(m.label().isEmpty(), where + " label");
                assertFalse(m.category().isEmpty(), where + " category");
                assertFalse(m.description().isEmpty(), where + " description");
                assertFalse(m.dimension().isEmpty(), where + " dimension");
                assertNotNull(m.reducer(), where + " reducer");
                assertNotNull(m.across(), where + " across");
                // Answerable by some source: Prometheus by its query, an export from a tool that
                // names it, or a series the tester computes as the description says, where no
                // tool has a single metric for it. A metric with none of these could come only
                // from a CSV someone wrote by hand under the catalogue's own name, guessing at what
                // it means, which is no way to ship one.
                assertNull(unanswerable(m), where);
                assertEquals(type.key(), m.type(), where + " knows its type");
            }
        }
    }

    @Test
    void everyTypeNamesTheExporterItsQueriesAssume() {
        // SF46: the Add component dialog's type card names it. It was only inside `source`, a
        // maintainer's note the page never showed, so a Kafka card said nothing about the two
        // exporters its metric names come from.
        assertEquals("postgres_exporter", catalogue.type("postgres").exporter());
        String kafka = catalogue.type("kafka").exporter();
        assertTrue(kafka.contains("kafka_exporter") && kafka.contains("Strimzi"), kafka);
        assertEquals("redis_exporter", catalogue.type("redis").exporter());
        assertEquals("node_exporter", catalogue.type("host-linux").exporter());
        // A type with no prometheus query names what its metrics come from: CloudWatch's namespaces,
        // and the CloudWatch agent, which alone publishes the ENA driver's allowance counters.
        assertEquals("CloudWatch AWS/EC2 and AWS/EBS + the agent for ENA", catalogue.type("aws-ec2").exporter());
        // Five of Oracle's metrics read series only a custom metrics file adds. The card names that
        // file rather than the chapter that gives it, which a reader of the offline page cannot open,
        // and does not say "custom": a card is found by its text, and that word is the Custom card's.
        assertEquals("Oracle Database Metrics Exporter 2.x + a TOML for 5 metrics", catalogue.type("oracle").exporter());
        // InfluxDB exports its own: no exporter, and a version the card must name.
        assertEquals("InfluxDB's own /metrics (v2)", catalogue.type("influxdb2").exporter());
        assertEquals("InfluxDB's own /metrics (v3 Core)", catalogue.type("influxdb3").exporter());
        // The JVM and HTTP types assume Micrometer's names, as Spring Boot Actuator serves them.
        assertEquals("Micrometer (Spring Boot Actuator's /actuator/prometheus)", catalogue.type("jvm").exporter());
        assertEquals("Micrometer's http.server.requests (Spring Boot Actuator)",
                catalogue.type("http-service").exporter());
        // A pod's KPIs divide a cAdvisor series by a kube-state-metrics one: the card names both.
        assertEquals("cAdvisor (the kubelet's) and kube-state-metrics", catalogue.type("kubernetes-pod").exporter());
        assertEquals("mysqld_exporter", catalogue.type("mysql").exporter());
        for (CatalogueType type : catalogue.types().values()) {
            assertTrue(type.exporter().length() <= 60, type + ": a card line, not a paragraph: " + type.exporter());
            assertFalse(type.exporter().equals(type.source()), type + ": the prerequisite, not the maintainer's note");
            // Playwright's hasText matches a substring, case-insensitively: a catalogue card whose
            // text says "custom" is also found as the Custom card, and a click on either is ambiguous.
            for (String text : Arrays.asList(type.label(), type.summary(), type.exporter())) {
                assertFalse(text.toLowerCase(java.util.Locale.ROOT).contains("custom"), type + ": " + text);
            }
        }
        // A new type cannot ship without one.
        IllegalStateException none = assertThrows(IllegalStateException.class, () -> Catalogue.readType("postgres",
                new ObjectMapper().readTree("{\"type\":\"postgres\",\"version\":1,\"metrics\":[{\"name\":\"a\","
                        + "\"queries\":{\"prometheus\":\"pg_up\"}}]}")));
        assertEquals("postgres.json names no exporter", none.getMessage());
    }

    @Test
    void eachTypeRecommendsSomething() {
        for (CatalogueType type : catalogue.types().values()) {
            assertTrue(type.metrics().values().stream().anyMatch(CatalogueMetric::recommended),
                    type + " recommends nothing, so the picker's first view is empty");
        }
    }

    /** Only a slot an interpolation scope understands may survive expansion. */
    private static final Pattern SLOT = Pattern.compile("\\$\\{([^}]*)}");

    @Test
    void everyTemplateExpandsCleanlyWithAndWithoutAMatch() {
        for (CatalogueType type : catalogue.types().values()) {
            for (CatalogueMetric m : type.metrics().values()) {
                for (String match : Arrays.asList(null, "instance=\"db-1:9187\"")) {
                    String q = m.query(Catalogue.PROMETHEUS, match);
                    if (q == null) {
                        continue;   // no template to expand: its identity is the neutral one
                    }
                    String where = type.key() + "." + m.name() + (match == null ? "" : " with a match");
                    Matcher slot = SLOT.matcher(q);
                    while (slot.find()) {
                        String name = slot.group(1);
                        assertTrue(name.startsWith("env.") || name.startsWith("run.") || name.startsWith("secret."),
                                where + ": slot ${" + name + "} survives expansion");
                    }
                    assertBalanced(where, q);
                    if (match != null && m.queries().get(Catalogue.PROMETHEUS).contains("match")) {
                        assertTrue(q.contains(match), where + ": the match never reached the query");
                    }
                }
            }
        }
    }

    @Test
    void safeDefaultsPointTheWayTheMetricIsWorse() {
        for (CatalogueType type : catalogue.types().values()) {
            for (CatalogueMetric m : type.metrics().values()) {
                Predicate fail = m.safeFail();
                if (fail == null) continue;
                boolean up = fail.op() == Predicate.Op.GT || fail.op() == Predicate.Op.GTE;
                assertEquals(m.direction() == CatalogueMetric.Direction.HIGHER, up,
                        type.key() + "." + m.name() + ": a safe fail band against its own direction");
            }
        }
    }

    @Test
    void aMetricWorseWhenLowerFoldsItsInstancesToTheLowestOne() {
        // SF2. Postgres up's own safe default is fail < 1, and with across max it PASSed while one
        // of two databases was down: the live one hid the dead one. Declared in the file, per metric.
        CatalogueType postgres = catalogue.type("postgres");
        assertEquals(Across.MIN, postgres.metric("up").across());
        assertEquals(Across.MIN, postgres.metric("cache.hit.pct").across());
        assertEquals(Across.MAX, postgres.metric("connections.used.pct").across(), "higher is worse: max, the default");

        // And none can ship the other way round. A metric worse when lower whose query keeps
        // instances apart ("by (...)") must fold them with min; one that sums or takes the min
        // over a cluster is a single series, and its fold decides nothing.
        for (CatalogueType type : catalogue.types().values()) {
            for (CatalogueMetric m : type.metrics().values()) {
                String q = m.queries().get(Catalogue.PROMETHEUS);
                if (m.direction() == CatalogueMetric.Direction.LOWER && q != null && q.contains(" by (")) {
                    assertEquals(Across.MIN, m.across(), type.key() + "." + m.name()
                            + " is worse when lower and keeps instances apart, so across max would report"
                            + " the healthiest one - declare \"across\": \"min\"");
                }
            }
        }
    }

    @Test
    void aPaddedQueryCannotShipBecauseAQueryIsASeriesIdentity() {
        // U7's other half: Collector.collectCatalogue reads these templates without ServerKpi's
        // trim, and the page reads the file as shipped. Refused, so the two can never key one
        // metric two ways.
        ObjectMapper json = new ObjectMapper();
        IllegalStateException padded = assertThrows(IllegalStateException.class, () -> Catalogue.readType("postgres",
                json.readTree("{\"type\":\"postgres\",\"version\":1,\"metrics\":[{\"name\":\"a\","
                        + "\"queries\":{\"prometheus\":\"pg_up \"}}]}")));
        assertTrue(padded.getMessage().contains("postgres.a: the prometheus query starts or ends with whitespace"),
                padded.getMessage());
        assertThrows(IllegalStateException.class, () -> Catalogue.readType("postgres",
                json.readTree("{\"type\":\"postgres\",\"version\":1,\"metrics\":[{\"name\":\"a\","
                        + "\"queries\":{\"prometheus\":\"\\u00a0pg_up\"}}]}")));
        IllegalStateException across = assertThrows(IllegalStateException.class, () -> Catalogue.readType("postgres",
                json.readTree("{\"type\":\"postgres\",\"version\":1,\"metrics\":[{\"name\":\"a\",\"across\":\"worst\"}]}")));
        assertEquals("postgres.a: across 'worst' is not one of max, min, avg", across.getMessage());
    }

    /**
     * F2. Every metric bounded above declares its ceiling, and nothing else does: a 0/1 flag is 1,
     * a share of something is 100. Nothing judges by a ceiling; the page reads it so it never
     * proposes a band the metric cannot reach, so one declared where the metric can pass it would
     * hide every band above it.
     *
     * <p>Unbounded on purpose: kafka {@code brokers} (a count), {@code controller.active} (2
     * during a split brain) and {@code request.handler.idle}, a meter's one-minute rate of idle
     * time that Kafka marks when each wait ends (KafkaRequestHandler), so a wait that straddles a
     * tick lands in the next one and the rate can pass 100 a little; redis {@code memory.used.pct} (buffers count, so it passes 100) and
     * its fragmentation ratio; host-linux {@code load.per.core} (load can be many times the cores);
     * aws-ec2 {@code cpu.credit.balance}, whose most depends on the instance size; oracle
     * {@code sessions.active.per.cpu} (sessions queue past one per CPU) and {@code db.cpu.pct}: the
     * Oracle reference counts {@code CPU used by this session} per user call, so a long call's CPU can
     * land in one minute, and {@code cpu_count} may be set below the host's CPUs; jvm
     * {@code gc.pause.time.pct}, because Micrometer records a pause when it ends, so one longer than
     * the rate's minute lands in it whole; kubernetes-pod {@code cpu.limit.used.pct}, a rate estimated
     * over a minute of scrapes against a limit the kernel enforces per scheduling period; and mysql
     * {@code connections.used.pct}: MySQL permits {@code max_connections + 1} connections, one kept
     * for CONNECTION_ADMIN, and more on its administrative interface. http-service
     * {@code latency.p95} is a quantile of Micrometer's timer histogram, whose top bucket is 30 s
     * only until {@code maximum-expected-value} moves it, so it declares none.
     *
     * <p>A latency p95 read off InfluxDB's own histograms is bounded too: {@code histogram_quantile}
     * reads no further than the top finite bucket, and both versions' duration histograms stop at
     * 10 s (v2: Prometheus' default buckets; v3: {@code DurationHistogramOptions::default}).
     */
    @Test
    void theBoundedMetricsDeclareTheirCeilingAndNoOtherDoes() {
        java.util.Map<String, Double> found = new java.util.LinkedHashMap<>();
        for (CatalogueType type : catalogue.types().values()) {
            for (CatalogueMetric m : type.metrics().values()) {
                if (m.ceiling() != null) found.put(type.key() + "." + m.name(), m.ceiling());
            }
        }
        java.util.Map<String, Double> expected = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> why = new java.util.LinkedHashMap<>();
        bounded(expected, why, "postgres.up", 1, "a 0/1 flag: the exporter reached the database or did not");
        bounded(expected, why, "postgres.connections.used.pct", 100, "a share of max_connections, which refuses the next one");
        bounded(expected, why, "postgres.rollback.ratio.pct", 100, "a share of the transactions that finished");
        bounded(expected, why, "postgres.cache.hit.pct", 100, "a share of the blocks read");
        bounded(expected, why, "kafka.network.processor.idle", 100,
                "a share of the network threads' time: Kafka caps each processor's io-wait ratio at 1 (SocketServer)");
        bounded(expected, why, "redis.up", 1, "a 0/1 flag: the exporter reached Redis or did not");
        bounded(expected, why, "redis.clients.connected.pct", 100, "a share of maxclients, which refuses the next one");
        bounded(expected, why, "redis.keyspace.hit.pct", 100, "a share of the read lookups");
        bounded(expected, why, "redis.cpu.main.pct", 100, "one thread's share of one core");
        bounded(expected, why, "redis.replication.link.up", 1, "a 0/1 flag: the replica's link is up or down");
        bounded(expected, why, "host-linux.cpu.busy.pct", 100, "a share of the CPUs' time");
        bounded(expected, why, "host-linux.cpu.iowait.pct", 100, "a share of the CPUs' time");
        bounded(expected, why, "host-linux.cpu.steal.pct", 100, "a share of the CPUs' time");
        bounded(expected, why, "host-linux.memory.available.pct", 100, "a share of the memory there is");
        bounded(expected, why, "host-linux.disk.busy.pct", 100, "a share of wall-clock time");
        bounded(expected, why, "host-linux.filesystem.free.pct", 100, "a share of the filesystem's size");
        bounded(expected, why, "host-linux.tcp.retransmit.pct", 100, "a share of the segments sent");
        bounded(expected, why, "host-linux.fd.used.pct", 100, "a share of fs.file-max");
        bounded(expected, why, "aws-ec2.ebs.burst.balance.pct", 100, "a share of the volume's burst bucket");
        bounded(expected, why, "aws-ec2.ebs.limit.exceeded", 1,
                "the largest of 0/1 flags, the instance's two and every attached volume's two: an EBS limit was"
                        + " exceeded or not");
        bounded(expected, why, "aws-ec2.status.check.failed", 1, "a 0/1 flag: AWS's checks passed or failed");
        bounded(expected, why, "oracle.up", 1, "a 0/1 flag: the exporter reached the database or did not");
        bounded(expected, why, "oracle.sessions.used.pct", 100, "a share of SESSIONS, which refuses the next logon");
        bounded(expected, why, "oracle.processes.used.pct", 100, "a share of PROCESSES, which refuses the next process");
        bounded(expected, why, "oracle.tablespace.used.pct", 100, "a share of the tablespace's largest size");
        bounded(expected, why, "oracle.cache.buffer.hit.pct", 100, "a share of the logical reads");
        for (String influx : Arrays.asList("influxdb2", "influxdb3")) {
            bounded(expected, why, influx + ".up", 1, "a 0/1 flag: Prometheus scraped the server or did not");
            bounded(expected, why, influx + ".write.latency.p95", 10, "a quantile of a histogram whose top bucket is 10 s");
            bounded(expected, why, influx + ".write.errors.pct", 100, "a share of the write requests");
            bounded(expected, why, influx + ".query.latency.p95", 10, "a quantile of a histogram whose top bucket is 10 s");
            bounded(expected, why, influx + ".query.errors.pct", 100, "a share of the queries");
        }
        bounded(expected, why, "influxdb3.query.permit.wait.p95", 10, "a quantile of a histogram whose top bucket is 10 s");
        bounded(expected, why, "jvm.heap.used.pct", 100, "used over the heap's maximum: G1's old pool, and generational"
                + " ZGC's and Shenandoah's old pool, carry the whole heap's (their young pools, which repeat it, are left"
                + " out), and Serial's and Parallel's pools each stay under their own");
        bounded(expected, why, "jvm.cpu.process.pct", 100,
                "a share of every CPU the JVM sees: getProcessCpuLoad is documented in [0, 1]");
        bounded(expected, why, "http-service.errors.5xx.pct", 100, "a share of the requests");
        bounded(expected, why, "kubernetes-pod.cpu.throttled.pct", 100, "a share of the CFS periods: a throttled one is one");
        bounded(expected, why, "kubernetes-pod.memory.limit.used.pct", 100,
                "a share of the memory limit, at which the kernel OOM-kills the container");
        bounded(expected, why, "mysql.up", 1, "a 0/1 flag: the exporter reached MySQL or did not");
        bounded(expected, why, "mysql.buffer.pool.hit.pct", 100, "a share of the logical reads");
        for (java.util.Map.Entry<String, Double> e : expected.entrySet()) {
            assertEquals(e.getValue(), found.get(e.getKey()), e.getKey() + " is bounded: " + why.get(e.getKey()));
        }
        assertEquals(expected, found, "a ceiling no reason above accounts for");
    }

    private static void bounded(java.util.Map<String, Double> expected, java.util.Map<String, String> why, String metric,
                                double ceiling, String reason) {
        expected.put(metric, ceiling);
        why.put(metric, reason);
    }

    /**
     * F2. A ceiling is read so a malformed one cannot ship: the page stops proposing bands at it,
     * so one that is not a number, one on a slope (judged per minute, not in the metric's unit) or
     * one the file's own safe default already sits past would each mislead it in silence.
     */
    @Test
    void aCeilingThatCouldMisleadTheProposalsCannotShip() throws Exception {
        ObjectMapper json = new ObjectMapper();
        IllegalStateException text = assertThrows(IllegalStateException.class, () -> Catalogue.readType("postgres",
                json.readTree("{\"type\":\"postgres\",\"version\":1,\"exporter\":\"e\",\"metrics\":[{\"name\":\"a\","
                        + "\"ceiling\":\"100\",\"queries\":{\"prometheus\":\"q\"}}]}")));
        assertEquals("postgres.a: ceiling \"100\" is not a finite number", text.getMessage());
        // A JSON number can still be infinite: Jackson reads 1e400 as Infinity, which is no bound.
        IllegalStateException infinite = assertThrows(IllegalStateException.class, () -> Catalogue.readType("postgres",
                json.readTree("{\"type\":\"postgres\",\"version\":1,\"exporter\":\"e\",\"metrics\":[{\"name\":\"a\","
                        + "\"ceiling\":1e400,\"queries\":{\"prometheus\":\"q\"}}]}")));
        assertTrue(infinite.getMessage().startsWith("postgres.a: ceiling ")
                && infinite.getMessage().endsWith(" is not a finite number"), infinite.getMessage());
        // An explicit null is no ceiling, as a null is no value in every other key readMetric reads.
        assertNull(Catalogue.readType("postgres", json.readTree("{\"type\":\"postgres\",\"version\":1,\"exporter\":\"e\","
                + "\"metrics\":[{\"name\":\"a\",\"ceiling\":null,\"queries\":{\"prometheus\":\"q\"}}]}"))
                .metrics().get("a").ceiling());

        IllegalStateException slope = assertThrows(IllegalStateException.class, () -> Catalogue.readType("postgres",
                json.readTree("{\"type\":\"postgres\",\"version\":1,\"exporter\":\"e\",\"metrics\":[{\"name\":\"a\","
                        + "\"reduce\":\"slope\",\"ceiling\":100,\"queries\":{\"prometheus\":\"q\"}}]}")));
        assertEquals("postgres.a: a slope is judged per minute, not in the metric's unit, so it cannot declare a ceiling",
                slope.getMessage());

        IllegalStateException dead = assertThrows(IllegalStateException.class, () -> Catalogue.readType("postgres",
                json.readTree("{\"type\":\"postgres\",\"version\":1,\"exporter\":\"e\",\"metrics\":[{\"name\":\"a\","
                        + "\"ceiling\":100,\"safeDefault\":{\"fail\":{\"gt\":100}},\"queries\":{\"prometheus\":\"q\"}}]}")));
        assertEquals("postgres.a: safe fail band > 100 can never fire under the metric's ceiling of 100",
                dead.getMessage());
        // The warn band is held to it too, and gte past it is as dead as gt at it.
        assertThrows(IllegalStateException.class, () -> Catalogue.readType("postgres",
                json.readTree("{\"type\":\"postgres\",\"version\":1,\"exporter\":\"e\",\"metrics\":[{\"name\":\"a\","
                        + "\"ceiling\":100,\"safeDefault\":{\"warn\":{\"gte\":100.5}},\"queries\":{\"prometheus\":\"q\"}}]}")));
        // The ceiling is written as the band beside it is (L12, Predicate.trim): plain digits, never
        // saturated at 2^63 and never in E notation, so one sentence holds one spelling.
        IllegalStateException huge = assertThrows(IllegalStateException.class, () -> Catalogue.readType("postgres",
                json.readTree("{\"type\":\"postgres\",\"version\":1,\"exporter\":\"e\",\"metrics\":[{\"name\":\"a\","
                        + "\"ceiling\":1e19,\"safeDefault\":{\"fail\":{\"gt\":1e19}},\"queries\":{\"prometheus\":\"q\"}}]}")));
        assertEquals("postgres.a: safe fail band > 10000000000000000000 can never fire under the metric's ceiling of "
                + "10000000000000000000", huge.getMessage());
        IllegalStateException decimal = assertThrows(IllegalStateException.class, () -> Catalogue.readType("postgres",
                json.readTree("{\"type\":\"postgres\",\"version\":1,\"exporter\":\"e\",\"metrics\":[{\"name\":\"a\","
                        + "\"ceiling\":12345678.5,\"safeDefault\":{\"fail\":{\"gt\":12345678.5}},\"queries\":{\"prometheus\":\"q\"}}]}")));
        assertEquals("postgres.a: safe fail band > 12345678.5 can never fire under the metric's ceiling of 12345678.5",
                decimal.getMessage());

        // What can still fire loads: gte AT the ceiling, a band below it, and a downward band.
        for (String safe : new String[] {"{\"fail\":{\"gte\":100}}", "{\"fail\":{\"gt\":99}}", "{\"fail\":{\"lt\":100}}"}) {
            Catalogue.readType("postgres", json.readTree("{\"type\":\"postgres\",\"version\":1,\"exporter\":\"e\","
                    + "\"metrics\":[{\"name\":\"a\",\"ceiling\":100,\"safeDefault\":" + safe
                    + ",\"queries\":{\"prometheus\":\"q\"}}]}"));
        }
    }

    /**
     * Strimzi's JMX rules export a BrokerTopicMetrics counter twice under one name: the broker's
     * all-topics total ({@code name=(.+)PerSec\w*><>Count}, no labels) and one series per topic
     * ({@code name=(.+)PerSec\w*, (.+)=(.+)><>Count}, label {@code topic}). A sum over the bare
     * name adds the total to its own parts and reads twice the rate. The catalogue sums the
     * per-topic series, so a component matched to one topic still has data.
     */
    @Test
    void aBrokerTopicCounterIsSummedOverItsTopicsAndNotAlsoOverTheBrokersOwnTotal() {
        CatalogueMetric messages = catalogue.type("kafka").metric("messages.in.rate");
        assertEquals("sum(rate(kafka_server_brokertopicmetrics_messagesin_total{topic!=\"\"}[1m]))",
                messages.query(Catalogue.PROMETHEUS, null));
        assertEquals("sum(rate(kafka_server_brokertopicmetrics_messagesin_total{topic!=\"\",namespace=\"kafka\"}[1m]))",
                messages.query(Catalogue.PROMETHEUS, "namespace=\"kafka\""));
        assertEquals("sum(rate(kafka_server_brokertopicmetrics_bytesin_total{topic!=\"\"}[1m]))",
                catalogue.type("kafka").metric("bytes.in.rate").query(Catalogue.PROMETHEUS, null));

        // And no query over one can ship without choosing.
        for (CatalogueType type : catalogue.types().values()) {
            for (CatalogueMetric m : type.metrics().values()) {
                String q = m.queries().get(Catalogue.PROMETHEUS);
                if (q != null && q.contains("kafka_server_brokertopicmetrics_")) {
                    assertTrue(q.contains("topic!=\"\""), type.key() + "." + m.name() + " sums the broker's"
                            + " all-topics total together with its per-topic series, twice the real rate: " + q);
                }
            }
        }
    }

    @Test
    void matchExpansion() {
        assertEquals("pg_up", CatalogueMetric.expandMatch("pg_up${match}", null));
        assertEquals("pg_up", CatalogueMetric.expandMatch("pg_up${match}", "  "));
        assertEquals("pg_up{instance=\"a\"}", CatalogueMetric.expandMatch("pg_up${match}", "instance=\"a\""));
        assertEquals("x{state=\"active\"}", CatalogueMetric.expandMatch("x{state=\"active\"${andMatch}}", null));
        assertEquals("x{state=\"active\",instance=\"a\"}",
                CatalogueMetric.expandMatch("x{state=\"active\"${andMatch}}", "instance=\"a\""));
    }

    @Test
    void aMalformedFileIsRefused() throws Exception {
        ObjectMapper json = new ObjectMapper();
        assertThrows(IllegalStateException.class, () -> Catalogue.readType("postgres",
                json.readTree("{\"type\":\"kafka\",\"version\":1,\"metrics\":[{\"name\":\"a\"}]}")));
        assertThrows(IllegalStateException.class, () -> Catalogue.readType("postgres",
                json.readTree("{\"type\":\"postgres\",\"version\":1,\"metrics\":[]}")));
        assertThrows(IllegalStateException.class, () -> Catalogue.readType("postgres",
                json.readTree("{\"type\":\"postgres\",\"version\":1,\"metrics\":[{\"name\":\"a\"},{\"name\":\"a\"}]}")));
        assertThrows(IllegalStateException.class, () -> Catalogue.readType("postgres",
                json.readTree("{\"type\":\"postgres\",\"version\":1,\"metrics\":[{\"name\":\"a\",\"reduce\":\"maxx\"}]}")));
        assertThrows(IllegalStateException.class, () -> Catalogue.readType("postgres",
                json.readTree("{\"type\":\"postgres\",\"version\":0,\"metrics\":[{\"name\":\"a\"}]}")));
    }

    // ---- each tool's name for a KPI, and the identity of one with no query ----------------

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A postgres file with one metric, {@code a}, carrying {@code fields} beside its name. */
    private static CatalogueType typeWith(String fields) throws Exception {
        return Catalogue.readType("postgres", JSON.readTree("{\"type\":\"postgres\",\"version\":1,\"exporter\":\"e\","
                + "\"metrics\":[{\"name\":\"a\"" + (fields.isEmpty() ? "" : "," + fields) + "}]}"));
    }

    private static String refusal(String fields) {
        return assertThrows(IllegalStateException.class, () -> typeWith(fields)).getMessage();
    }

    /**
     * A user exports server metrics from whatever tool they have, and each names a KPI its own
     * way and hands over its own kind of number: a raw cumulative counter (Telegraf, OTel), a rate
     * per second or a raw total (Datadog), a sum per period (CloudWatch), a fraction where the
     * catalogue says %. The catalogue says, per tool, which metric it is and what kind of number.
     */
    @Test
    void aMetricNamesItsKpiInEachToolThatHasOne() throws Exception {
        assertEquals(Arrays.asList("telegraf", "datadog", "otel", "cloudwatch"), Catalogue.TOOLS);
        assertEquals("gauge, counter, rate, sum-per-period", ToolName.Kind.vocabulary());

        CatalogueMetric m = typeWith("\"names\":{"
                + "\"datadog\":{\"metric\":\"postgresql.percent_usage_connections\",\"kind\":\"gauge\",\"scale\":100},"
                + "\"telegraf\":null,"
                + "\"cloudwatch\":{\"metric\":\"AWS/RDS:DatabaseConnections\",\"kind\":\"sum-per-period\"},"
                + "\"otel\":{\"metric\":\"postgresql.backends\",\"kind\":\"counter\",\"scale\":null}}").metric("a");
        ToolName datadog = m.name("datadog");
        assertEquals("datadog", datadog.tool());
        assertEquals("postgresql.percent_usage_connections", datadog.metric());
        assertEquals(ToolName.Kind.GAUGE, datadog.kind());
        assertEquals(100.0, datadog.scale());
        assertEquals(ToolName.Kind.SUM_PER_PERIOD, m.name("cloudwatch").kind());
        assertEquals("sum-per-period", m.name("cloudwatch").kind().key());
        assertEquals(ToolName.Kind.COUNTER, m.name("otel").kind());
        assertEquals(1.0, m.name("otel").scale(), "no scale, or a null one, is 1");

        // An explicit null: the tool has no single-metric equivalent of this KPI - known none.
        assertNull(m.name("telegraf"));
        assertTrue(m.declares("telegraf"));
        assertTrue(m.declaresNone("telegraf"));
        assertFalse(m.declaresNone("datadog"));
        // names() is every tool that has one, in TOOLS order whatever the file's.
        assertEquals(Arrays.asList("datadog", "otel", "cloudwatch"), new ArrayList<>(m.names().keySet()));

        // An absent tool is not declared, which is not the same as none.
        CatalogueMetric bare = typeWith("\"queries\":{\"prometheus\":\"q\"}").metric("a");
        assertNull(bare.name("datadog"));
        assertFalse(bare.declares("datadog"));
        assertFalse(bare.declaresNone("datadog"));
        assertTrue(bare.names().isEmpty());
        // "names": null is no names block, as a null is no value in every other key readMetric reads.
        assertTrue(typeWith("\"names\":null,\"queries\":{\"prometheus\":\"q\"}").metric("a").names().isEmpty());
    }

    /** The catalogue is ours, so a names block is read strictly: a typo is a build defect. */
    @Test
    void aMalformedNamesBlockCannotShip() {
        String kinds = "gauge, counter, rate, sum-per-period";
        assertEquals("postgres.a: names [] is not an object of tool names", refusal("\"names\":[]"));
        assertEquals("postgres.a: names tool 'newrelic' is not one of telegraf, datadog, otel, cloudwatch",
                refusal("\"names\":{\"newrelic\":null}"));
        assertEquals("postgres.a: names.datadog \"x\" is neither an object nor null",
                refusal("\"names\":{\"datadog\":\"x\"}"));
        assertEquals("postgres.a: names.datadog has no metric",
                refusal("\"names\":{\"datadog\":{\"kind\":\"gauge\"}}"));
        assertEquals("postgres.a: names.datadog has no metric",
                refusal("\"names\":{\"datadog\":{\"metric\":\"  \",\"kind\":\"gauge\"}}"));
        assertEquals("postgres.a: names.datadog has no metric",
                refusal("\"names\":{\"datadog\":{\"metric\":null,\"kind\":\"gauge\"}}"));
        assertEquals("postgres.a: names.datadog metric 42 is not text",
                refusal("\"names\":{\"datadog\":{\"metric\":42,\"kind\":\"gauge\"}}"));
        // A metric name is what a file is searched for, so it is refused padded, as a query is.
        assertEquals("postgres.a: names.datadog metric \"pg.x \" starts or ends with whitespace or an invisible"
                        + " character - write it without",
                refusal("\"names\":{\"datadog\":{\"metric\":\"pg.x \",\"kind\":\"gauge\"}}"));
        String zeroWidth = "pg.x" + (char) 0x200B;
        assertTrue(refusal("\"names\":{\"datadog\":{\"metric\":\"" + zeroWidth + "\",\"kind\":\"gauge\"}}")
                .startsWith("postgres.a: names.datadog metric \"" + zeroWidth + "\" starts or ends with whitespace"));
        assertEquals("postgres.a: names.datadog has no kind - one of " + kinds,
                refusal("\"names\":{\"datadog\":{\"metric\":\"m\"}}"));
        assertEquals("postgres.a: names.datadog kind 'delta' is not one of " + kinds,
                refusal("\"names\":{\"datadog\":{\"metric\":\"m\",\"kind\":\"delta\"}}"));
        assertEquals("postgres.a: names.datadog kind 1 is not one of " + kinds,
                refusal("\"names\":{\"datadog\":{\"metric\":\"m\",\"kind\":1}}"));
        for (String scale : new String[] {"0", "-1", "\"100\""}) {
            assertEquals("postgres.a: names.datadog scale " + scale + " is not a finite number above 0",
                    refusal("\"names\":{\"datadog\":{\"metric\":\"m\",\"kind\":\"gauge\",\"scale\":" + scale + "}}"));
        }
        // Jackson reads 1e400 as Infinity, which scales nothing.
        String infinite = refusal("\"names\":{\"datadog\":{\"metric\":\"m\",\"kind\":\"gauge\",\"scale\":1e400}}");
        assertTrue(infinite.startsWith("postgres.a: names.datadog scale ")
                && infinite.endsWith(" is not a finite number above 0"), infinite);
        assertEquals("postgres.a: names.datadog key 'unit' is not one of metric, kind, scale, note",
                refusal("\"names\":{\"datadog\":{\"metric\":\"m\",\"kind\":\"gauge\",\"unit\":\"%\"}}"));
    }

    /**
     * A name alone is not always enough: Telegraf's cpu input writes one series per core beside
     * the total, a CloudWatch export needs its statistic and dimension, an OpenTelemetry metric may
     * be off by default. The entry's optional {@code note} says which series to export, as written.
     */
    @Test
    void aToolNameMaySayWhichSeriesOfTheToolToExport() throws Exception {
        CatalogueMetric m = typeWith("\"names\":{"
                + "\"telegraf\":{\"metric\":\"cpu.usage_steal\",\"kind\":\"gauge\",\"note\":\"tag cpu=cpu-total\"},"
                + "\"datadog\":{\"metric\":\"system.cpu.stolen\",\"kind\":\"gauge\",\"note\":null},"
                + "\"otel\":{\"metric\":\"system.cpu.utilization\",\"kind\":\"gauge\",\"scale\":100}}").metric("a");
        assertEquals("tag cpu=cpu-total", m.name("telegraf").note());
        assertNull(m.name("datadog").note(), "a null note is no note, as a null is no value in every other key");
        assertNull(m.name("otel").note(), "no note");
        // At most 160 characters, counted as a reader counts them: one per code point.
        StringBuilder at160 = new StringBuilder();
        while (at160.length() < 158) at160.append('x');
        at160.appendCodePoint(0x1F600).append('y');   // an emoji is two chars and one character
        assertEquals(161, at160.length());
        assertEquals(160, at160.codePointCount(0, at160.length()));
        assertEquals(at160.toString(), typeWith(noted(JSON.writeValueAsString(at160.toString())))
                .metric("a").name("telegraf").note());
        // Inner whitespace and punctuation are the note's own.
        assertEquals("AWS/EC2, statistic Minimum; dimension InstanceId", typeWith(
                noted("\"AWS/EC2, statistic Minimum; dimension InstanceId\"")).metric("a").name("telegraf").note());
    }

    /** A names entry with {@code note} set to the JSON text {@code value}, for a refusal. */
    private static String noted(String value) {
        return "\"names\":{\"telegraf\":{\"metric\":\"cpu.usage_steal\",\"kind\":\"gauge\",\"note\":" + value + "}}";
    }

    /**
     * A note says which series of the tool to export when its name alone is not enough, and a
     * tester reads it as written: so it is read as strictly as the name it qualifies. A note that
     * is not text, says nothing, is padded, spans lines or runs past a line is refused by place.
     */
    @Test
    void aMalformedNoteCannotShip() {
        assertEquals("postgres.a: names.telegraf note 42 is not text", refusal(noted("42")));
        assertEquals("postgres.a: names.telegraf note {\"tag\":\"cpu\"} is not text", refusal(noted("{\"tag\":\"cpu\"}")));
        for (String blank : new String[] {"\"\"", "\"   \""}) {
            assertEquals("postgres.a: names.telegraf note is blank - leave it out when there is nothing to say",
                    refusal(noted(blank)), blank);
        }
        assertEquals("postgres.a: names.telegraf note \" tag cpu=cpu-total\" starts or ends with whitespace or an"
                + " invisible character - write it without", refusal(noted("\" tag cpu=cpu-total\"")));
        String zeroWidth = "tag cpu=cpu-total" + (char) 0x200B;
        assertTrue(refusal(noted("\"" + zeroWidth + "\"")).startsWith("postgres.a: names.telegraf note \"" + zeroWidth
                + "\" starts or ends with whitespace"));
        // One line: the picker and a CI log each show it as one.
        for (String broken : new String[] {"\"tag cpu=cpu-total\\nand per core\"", "\"tag cpu=cpu-total\\r\\nper core\"",
                "\"tag cpu=cpu-total\\u2028per core\""}) {
            assertEquals("postgres.a: names.telegraf note spans more than one line - write it on one",
                    refusal(noted(broken)), broken);
        }
        // Short: a note qualifies a name, it is not the type's source.
        StringBuilder long161 = new StringBuilder();
        while (long161.length() < 161) long161.append('x');
        assertEquals("postgres.a: names.telegraf note is 161 characters - at most 160",
                refusal(noted("\"" + long161 + "\"")));
    }

    // ---- each tool's naming convention (Catalogue's javadoc, index.json, Tutorial 13) ------------

    /** {@code <measurement>.<field>}: what {@code --csv-columns metric=_measurement+_field} joins. */
    private static final Pattern TELEGRAF_NAME = Pattern.compile("[a-z][a-z0-9_]*\\.[A-Za-z0-9_][A-Za-z0-9_.\\-]*");
    /** Datadog's and OpenTelemetry's own metric name: dotted, lower case. */
    private static final Pattern DOTTED_NAME = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+");
    /** {@code <Namespace>:<MetricName>}; the statistic and the dimension go in the note. */
    private static final Pattern CLOUDWATCH_NAME = Pattern.compile("(AWS/[A-Za-z0-9]+|CWAgent):[A-Za-z][A-Za-z0-9_%.\\-]*");

    /** Null when {@code metric} is written the way {@code tool}'s export writes it, else why not. */
    static String conventionProblem(String tool, String metric) {
        switch (tool) {
            case "telegraf":
                return TELEGRAF_NAME.matcher(metric).matches() ? null : "telegraf names a metric <measurement>.<field>,"
                        + " as --csv-columns metric=_measurement+_field joins them: " + metric;
            case "datadog":
            case "otel":
                return DOTTED_NAME.matcher(metric).matches() ? null
                        : tool + " names a metric by its own dotted lower-case name: " + metric;
            case "cloudwatch":
                return CLOUDWATCH_NAME.matcher(metric).matches() ? null : "cloudwatch names a metric"
                        + " <Namespace>:<MetricName> (AWS/EC2:CPUCreditBalance, CWAgent:mem_used_percent), the statistic"
                        + " and dimension in the note: " + metric;
            default:
                return "no convention for tool " + tool;
        }
    }

    /**
     * One shape per tool, so a tester can export by the name as written: Telegraf
     * {@code <measurement>.<field>}, Datadog and OpenTelemetry the metric's own name, CloudWatch
     * {@code <Namespace>:<MetricName>} with the statistic and dimension in the note. The rule bites
     * on each shape a slip would produce.
     */
    @Test
    void theNamingConventionRefusesANameInAnotherToolsShape() {
        assertNull(conventionProblem("telegraf", "postgresql.deadlocks"));
        assertNull(conventionProblem("telegraf", "redis_replication.lag"));
        assertNull(conventionProblem("telegraf", "kafka_controller.OfflinePartitionsCount.Value"), "a jolokia2 field");
        assertNotNull(conventionProblem("telegraf", "postgresql_deadlocks"), "a Prometheus-style name, no field");
        assertNotNull(conventionProblem("telegraf", "postgresql deadlocks"));
        assertNotNull(conventionProblem("telegraf", ".deadlocks"));
        assertNull(conventionProblem("datadog", "postgresql.percent_usage_connections"));
        assertNull(conventionProblem("datadog", "system.load.norm.1"));
        assertNotNull(conventionProblem("datadog", "postgresql:percent_usage_connections"));
        assertNotNull(conventionProblem("otel", "postgresql_deadlocks_total"), "the name as exported to Prometheus");
        assertNull(conventionProblem("cloudwatch", "AWS/EC2:CPUCreditBalance"));
        assertNull(conventionProblem("cloudwatch", "CWAgent:mem_used_percent"));
        assertNull(conventionProblem("cloudwatch", "AWS/EC2:EBSIOBalance%"));
        assertNotNull(conventionProblem("cloudwatch", "CPUCreditBalance"), "no namespace");
        assertNotNull(conventionProblem("cloudwatch", "AWS/EC2 CPUCreditBalance"));
        assertNotNull(conventionProblem("cloudwatch", "AWS/EC2.CPUCreditBalance"));
        assertNotNull(conventionProblem("cloudwatch", "AWS/EC2:CPUCreditBalance:Minimum"), "the statistic is the note's");
        assertEquals("cloudwatch names a metric <Namespace>:<MetricName> (AWS/EC2:CPUCreditBalance,"
                + " CWAgent:mem_used_percent), the statistic and dimension in the note: CPUCreditBalance",
                conventionProblem("cloudwatch", "CPUCreditBalance"));
    }

    @Test
    void everyShippedToolNameFollowsItsToolsConvention() {
        int named = 0;
        for (CatalogueType type : catalogue.types().values()) {
            for (CatalogueMetric m : type.metrics().values()) {
                for (ToolName n : m.names().values()) {
                    assertNull(conventionProblem(n.tool(), n.metric()), type.key() + "." + m.name());
                    named++;
                }
            }
        }
        assertTrue(named > 0, "the shipped catalogue names no metric in any tool");
    }

    /**
     * AWS defines {@code AWS/EC2 CPUUtilization} as guest plus hypervisor time, measured against a
     * full vCPU on a burstable instance: not the guest's CPU busy, so no metric reads it as that.
     * The guest's own is the CloudWatch agent's {@code cpu_usage_active}.
     */
    @Test
    void theHypervisorsCpuUtilizationIsNotTheGuestsCpuBusy() {
        for (CatalogueType type : catalogue.types().values()) {
            for (CatalogueMetric m : type.metrics().values()) {
                ToolName cw = m.name("cloudwatch");
                assertFalse(cw != null && cw.metric().equals("AWS/EC2:CPUUtilization"), type.key() + "." + m.name());
            }
        }
        assertEquals("CWAgent:cpu_usage_active", catalogue.type("host-linux").metric("cpu.busy.pct").name("cloudwatch").metric());
    }

    /**
     * The OpenTelemetry postgresql receiver's {@code postgresqlreceiver.preciselagmetrics} gate is
     * beta since v0.89.0, so on: its scraper records replication lag as {@code postgresql.wal.delay}
     * (a double, off by default) and never as {@code postgresql.wal.lag}, which a current collector
     * therefore does not emit. A tester who enables the metric gets it under the first name.
     */
    @Test
    void theOtelReplicationLagIsTheNameACurrentCollectorRecords() {
        ToolName otel = catalogue.type("postgres").metric("replication.lag.seconds").name("otel");
        assertEquals("postgresql.wal.delay", otel.metric());
        assertEquals(ToolName.Kind.GAUGE, otel.kind());
        assertEquals(1.0, otel.scale(), "seconds, the catalogue's unit");
        assertTrue(otel.note().startsWith("off by default"), otel.note());
    }

    /** How the page and the docs name each tool, in the sentence a computed KPI's description says. */
    private static final java.util.Map<String, String> TOOL_LABELS = new java.util.LinkedHashMap<>();
    static {
        TOOL_LABELS.put("telegraf", "Telegraf");
        TOOL_LABELS.put("datadog", "Datadog");
        TOOL_LABELS.put("otel", "OpenTelemetry");
        TOOL_LABELS.put("cloudwatch", "CloudWatch");
    }

    /**
     * Null when {@code m} is answerable by some source, else why not. Prometheus answers it by its
     * query, and a tool's export by the name the catalogue gives it there. A metric with neither is
     * answered only by a series the tester computes in a tool (a formula, a metric-math expression)
     * and exports under the catalogue's own name, which {@code --csv} reads with or without
     * {@code --from}: so a tool must say it has no single metric for it ({@code null}), and the
     * description must say how to compute it, in one pinned sentence:
     * {@code No single <Tool> metric: <what to combine, and how>, and export the result as <name>.}
     * — the tool one the metric declares none for, the sentence the description's last, and the
     * name the metric's own, so the file's {@code metric} column is spelled as it is read. Last means
     * no sentence ends inside it (REV-17): a {@code .}, {@code ?} or {@code !} before a space, which
     * a dotted name, a decimal or {@code PERIOD(m1)} is not. Otherwise {@code .+} ran from one
     * sentence's "No single ..." to another's export, and the pinned sentence was never written.
     */
    static String unanswerable(CatalogueMetric m) {
        if (m.query(Catalogue.PROMETHEUS, null) != null || !m.names().isEmpty()) {
            return null;
        }
        List<String> none = new ArrayList<>();
        for (String tool : Catalogue.TOOLS) {
            if (m.declaresNone(tool)) none.add(TOOL_LABELS.get(tool));
        }
        if (none.isEmpty()) {
            return "is answerable by no source: no prometheus query and no tool's name for it";
        }
        Matcher said = Pattern.compile("No single (\\S+) metric: (?:(?![.?!]\\s).)+, and export the result as "
                + Pattern.quote(m.name()) + "\\.$").matcher(m.description());
        if (said.find() && none.contains(said.group(1))) {
            return null;
        }
        return "has no prometheus query and no tool's single metric, so its description must say how to"
                + " compute it, last: \"No single " + String.join("|", none) + " metric: <what to combine,"
                + " and how>, and export the result as " + m.name() + ".\"";
    }

    /**
     * A metric with no prometheus query is answered only by a file exported from a tool: by the
     * tool's name for it, or, where every tool it declares has no single metric for it, by the
     * series its description says how to compute ({@link #unanswerable}). A names block of nulls
     * with nothing said would leave it to a CSV someone wrote by hand under the catalogue's own
     * name, guessing what it means. {@link #everyMetricSaysWhatItIs} holds the same rule over
     * every metric; a change to one changes both. What this adds is that the shipped catalogue has
     * both kinds: aws-ec2's three named CloudWatch metrics and four of kafka's
     * ({@link #theKafkaKpisStrimzisRulesCannotAnswerHaveNoPrometheusQuery}), and aws-ec2's four
     * metric-math KPIs ({@link #awsEc2ShipsTheResearchsSevenKpisFourComputedWithMetricMath}).
     */
    @Test
    void aMetricWithNoPrometheusQueryNamesSomeToolsMetricOrSaysHowToComputeIt() {
        int named = 0;
        List<String> computed = new ArrayList<>();
        for (CatalogueType type : catalogue.types().values()) {
            for (CatalogueMetric m : type.metrics().values()) {
                if (!m.neutral()) continue;
                assertNull(unanswerable(m), type.key() + "." + m.name()
                        + " - only a hand-written CSV could answer it");
                if (m.names().isEmpty()) computed.add(m.qualifiedName());
                else named++;
            }
        }
        assertTrue(named > 0, "aws-ec2's named metrics are CloudWatch's, with no prometheus query");
        assertEquals(Arrays.asList("aws-ec2.ebs.limit.exceeded", "aws-ec2.ebs.iops", "aws-ec2.ebs.throughput.rate",
                "aws-ec2.network.allowance.exceeded"), computed);
    }

    /** The rule, on one metric "a" of a postgres-keyed type: each shape it refuses, and the one it takes. */
    @Test
    void aMetricNoToolHasSaysHowToComputeItOrCannotShip() throws Exception {
        String say = "No single CloudWatch metric: add AWS/EBS VolumeReadOps and VolumeWriteOps with metric math,"
                + " (m1 + m2) / PERIOD(m1), and export the result as a.";
        String none = "\"names\":{\"cloudwatch\":null}";
        assertNull(unanswerable(typeWith(none + ",\"description\":\"Disk ops. " + say + "\"").metric("a")));
        // Said by a tool the metric declares none for, and in the tools' own words.
        assertNull(unanswerable(typeWith("\"names\":{\"telegraf\":null,\"otel\":null},\"description\":\""
                + "No single OpenTelemetry metric: sum system.disk.operations over direction, as a rate,"
                + " and export the result as a.\"").metric("a")));

        String refused = "has no prometheus query and no tool's single metric, so its description must say how"
                + " to compute it, last: \"No single CloudWatch metric: <what to combine, and how>, and export the"
                + " result as a.\"";
        // Nothing said.
        assertEquals(refused, unanswerable(typeWith(none + ",\"description\":\"Disk ops.\"").metric("a")));
        // Said, but not last: the export is the last thing to do, so it is the last thing said.
        assertEquals(refused, unanswerable(typeWith(none + ",\"description\":\"" + say + " Then read it.\"").metric("a")));
        // Nor is a sentence that only ends the way the pinned one does (REV-17): a sentence ends inside
        // the match, so "No single ...:" opens one sentence and the export closes another.
        assertEquals(refused, unanswerable(typeWith(none + ",\"description\":\"No single CloudWatch metric: add"
                + " AWS/EBS VolumeReadOps and VolumeWriteOps. Divide by the period, and export the result as a.\"")
                .metric("a")));
        assertEquals(refused, unanswerable(typeWith(none + ",\"description\":\"No single CloudWatch metric: which"
                + " two? Add them, and export the result as a.\"").metric("a")));
        // A '.' inside a name, a number or an expression is no sentence's end.
        assertNull(unanswerable(typeWith(none + ",\"description\":\"No single CloudWatch metric: add disk.read.ops"
                + " and 0.5 x disk.write.ops, and export the result as a.\"").metric("a")));
        // Exported under another metric's name, which --csv would never read as this one.
        assertEquals(refused, unanswerable(typeWith(none + ",\"description\":\""
                + say.replace("as a.", "as b.") + "\"").metric("a")));
        // Said for a tool the metric does not declare none for: CloudWatch was never asked.
        assertEquals(refused, unanswerable(typeWith(none + ",\"description\":\""
                + say.replace("CloudWatch", "Datadog") + "\"").metric("a")));
        // No tool declared at all: nobody has said a tool lacks it, so there is nothing to compute it in.
        assertEquals("is answerable by no source: no prometheus query and no tool's name for it",
                unanswerable(typeWith("\"description\":\"" + say + "\"").metric("a")));
        // A query or a tool's name answers it, whatever the description says.
        assertNull(unanswerable(typeWith("\"queries\":{\"prometheus\":\"q\"}," + none).metric("a")));
        assertNull(unanswerable(typeWith("\"names\":{\"cloudwatch\":{\"metric\":\"AWS/EBS:VolumeReadOps\","
                + "\"kind\":\"sum-per-period\"}}").metric("a")));
    }

    /**
     * The research ({@code hosts-linux-aws.md} §2b) has seven aws-ec2 KPIs. Four are each two to
     * five CloudWatch metrics with no single one to name, so they ship with {@code cloudwatch: null}
     * and a description that gives the metric-math expression, every metric named in it checked
     * on 2026-10-10 against AWS's pages: Amazon EBS's CloudWatch metrics (VolumeReadOps,
     * VolumeWriteOps, VolumeReadBytes, VolumeWriteBytes, VolumeIOPSExceededCheck,
     * VolumeThroughputExceededCheck, "divide the total ... by the number of seconds in that
     * period"; the two checks carry the VolumeId dimension, so every volume is its own series),
     * EC2's instance metrics (EBSReadOps, EBSWriteOps, EBSReadBytes, EBSWriteBytes,
     * InstanceEBSIOPSExceededCheck, InstanceEBSThroughputExceededCheck, Nitro and not bare metal),
     * the CloudWatch agent's network performance metrics (the ENA driver's five *_allowance_exceeded
     * counters, prefixed ethtool_ by its Linux ethtool plugin, "aggregate counters", listed in the
     * ethtool section's metrics_include), EC2's ENA page ("the cumulative number of packets queued or
     * dropped on each network interface since the last driver reset"), the agent's configuration
     * reference (append_dimensions adds InstanceId, and then no host) and the metric-math reference
     * (PERIOD, RATE; METRICS() is every metric in the request, no expression; MAX and SUM of an
     * array are a series per point).
     * Recommended per the research's rows, five: its summary line said four.
     *
     * <p>Rechecked on 2026-10-16 against the same pages (NIT-6, REV-16): the volume checks are
     * "supported for all volume types, except magnetic (standard), attached to Nitro instances" and
     * "not supported with Multi-Attach enabled volumes"; AWS/EC2's EBS metrics are "for volumes that
     * are attached to Nitro-based instances that are not bare metal instances", the EBSReadOps route
     * included; the agent publishes "the hostname ... as a dimension ... unless you are using the
     * append_dimensions field", and "omit_hostname" set to true prevents it "even if you are not
     * using append_dimensions"; and RATE is "the difference between the latest data point value and
     * the previous data point value, divided by the time difference", so the RATE across a driver
     * reset is negative and, inside the SUM, cancels a real exceed. ABS and SUM take an array and IF
     * does not, so each rate is clamped at 0 as (r + |r|) / 2, summed: (SUM(RATE) + SUM(ABS(RATE))) / 2.
     */
    @Test
    void awsEc2ShipsTheResearchsSevenKpisFourComputedWithMetricMath() {
        CatalogueType ec2 = catalogue.type("aws-ec2");
        assertEquals(Arrays.asList("cpu.credit.balance", "ebs.burst.balance.pct", "ebs.limit.exceeded", "ebs.iops",
                "ebs.throughput.rate", "status.check.failed", "network.allowance.exceeded"),
                new ArrayList<>(ec2.metrics().keySet()));
        List<String> recommended = new ArrayList<>();
        for (CatalogueMetric m : ec2.metrics().values()) {
            if (m.recommended()) recommended.add(m.name());
            // aws-ec2 names CloudWatch only (Tutorial 13): no other tool is declared, named or null.
            for (String tool : Catalogue.TOOLS) {
                if (!tool.equals("cloudwatch")) assertFalse(m.declares(tool), m.qualifiedName() + " " + tool);
            }
            assertTrue(m.neutral(), m.qualifiedName() + ": aws-ec2 has no prometheus query");
        }
        assertEquals(Arrays.asList("cpu.credit.balance", "ebs.burst.balance.pct", "ebs.limit.exceeded",
                "status.check.failed", "network.allowance.exceeded"), recommended);

        java.util.Map<String, List<String>> cloudWatch = new java.util.LinkedHashMap<>();
        // AWS/EC2's EBS totals are 5-minute data under basic monitoring ("divide this number by 300"):
        // exported at AWS/EBS's natural 60 s, each datapoint is still five minutes' sum and PERIOD(m1)
        // divides it by 60, five times the rate. So that route names the period it needs.
        // ...and both are AWS/EC2's EBS metrics, which are for Nitro instances that are not bare metal.
        cloudWatch.put("ebs.iops", Arrays.asList("AWS/EBS VolumeReadOps and VolumeWriteOps", "statistic Sum",
                "AWS/EC2 EBSReadOps and EBSWriteOps for the instance's total on a Nitro instance that is not bare"
                        + " metal, at a period of at least 300 s under basic monitoring", "(m1 + m2) / PERIOD(m1)"));
        cloudWatch.put("ebs.throughput.rate", Arrays.asList("AWS/EBS VolumeReadBytes and VolumeWriteBytes",
                "statistic Sum", "AWS/EC2 EBSReadBytes and EBSWriteBytes for the instance's total on a Nitro"
                        + " instance that is not bare metal, at a period of at least 300 s under basic monitoring",
                "(m1 + m2) / PERIOD(m1)"));
        // The instance checks are "for volumes attached to Nitro-based instances that are not bare metal".
        // The volume checks carry VolumeId, one series per volume: a root and a data volume are two
        // checks each beside the instance's two, six series, every one in the MAX. They are published
        // for no magnetic volume and no Multi-Attach enabled one, whose limit the MAX therefore never sees.
        cloudWatch.put("ebs.limit.exceeded", Arrays.asList(
                "AWS/EC2 InstanceEBSIOPSExceededCheck and InstanceEBSThroughputExceededCheck",
                "AWS/EBS VolumeIOPSExceededCheck and VolumeThroughputExceededCheck", "statistic Maximum",
                "for every volume attached to the instance (one series per VolumeId)",
                "Nitro instances only (the instance checks not on bare metal)",
                "AWS/EBS publishes neither volume check for a magnetic (standard) or Multi-Attach enabled volume,"
                        + " so such a volume's limit goes unseen",
                "on a graph holding only those", "MAX(METRICS())"));
        // ethtool is the agent's Linux plugin; on Windows the counters are ENA Packets Shaping's, named otherwise.
        // The driver counts "on each network interface since the last driver reset", and the plugin
        // tags each series with its interface and driver; the agent adds its hostname (not with
        // omit_hostname true), or, when its append_dimensions is set, what that adds instead (InstanceId
        // in AWS's own example config). So
        // no series is per InstanceId alone, and an instance with several ENIs has five per interface,
        // every one in the sum - each rate clamped at 0, or a driver reset's negative rate cancels a
        // real exceed on another series (REV-16).
        cloudWatch.put("network.allowance.exceeded", Arrays.asList("ethtool_bw_in_allowance_exceeded",
                "ethtool_bw_out_allowance_exceeded", "ethtool_pps_allowance_exceeded",
                "ethtool_conntrack_allowance_exceeded", "ethtool_linklocal_allowance_exceeded",
                "on Linux, list the five in the CloudWatch agent's ethtool metrics_include",
                "cumulative counters since the driver's last reset, one series per network interface: dimensions"
                        + " interface and driver, plus the agent's hostname (unless its omit_hostname is true) or"
                        + " what its append_dimensions adds instead, InstanceId in AWS's example; statistic Maximum",
                "for every interface of the instance", "on a graph holding only those",
                "each series' rate clamped at 0, since a driver reset makes it negative and in the sum it would"
                        + " cancel a real exceed",
                "60 * (SUM(RATE(METRICS())) + SUM(ABS(RATE(METRICS())))) / 2"));
        for (java.util.Map.Entry<String, List<String>> e : cloudWatch.entrySet()) {
            CatalogueMetric m = ec2.metric(e.getKey());
            assertTrue(m.declaresNone("cloudwatch") && m.names().isEmpty(), m.name() + ": no single CloudWatch metric");
            assertTrue(m.description().contains("No single CloudWatch metric: "), m.description());
            assertTrue(m.description().endsWith(", and export the result as " + m.name() + "."), m.description());
            assertEquals(Across.MAX, m.across(), m.name() + ": the worst instance, as the research folds it");
            for (String said : e.getValue()) {
                assertTrue(m.description().contains(said), m.name() + " says " + said + ": " + m.description());
            }
        }
        CatalogueMetric iops = ec2.metric("ebs.iops");
        assertEquals("ops/s", iops.unit());
        assertEquals("p95", iops.reducer().key());
        assertEquals("per volume", iops.dimension());
        CatalogueMetric bytes = ec2.metric("ebs.throughput.rate");
        assertEquals("B/s", bytes.unit());
        assertEquals("p95", bytes.reducer().key());
        for (CatalogueMetric m : Arrays.asList(iops, bytes)) {
            assertNull(m.safeWarn(), m.name() + ": compare it with what is provisioned");
            assertNull(m.safeFail(), m.name() + ": compare it with what is provisioned");
            assertEquals(CatalogueMetric.Direction.HIGHER, m.direction());
        }
        CatalogueMetric limit = ec2.metric("ebs.limit.exceeded");
        assertEquals("", limit.unit());
        assertEquals("max", limit.reducer().key());
        assertEquals(Double.valueOf(1), limit.ceiling(),
                "the largest of 0/1 checks, however many volumes: MAX, never their sum");
        // Not a fixed four-term array, which holds one volume's checks and leaves out every other's.
        assertFalse(limit.description().contains("[m1, m2, m3, m4]"), limit.description());
        assertFalse(limit.description().contains("each volume's"), limit.description());
        assertTrue(ec2.source().contains("the limit KPI is the MAX of the instance's two and every attached"
                + " volume's two, never their sum"), ec2.source());
        CatalogueMetric ena = ec2.metric("network.allowance.exceeded");
        // Neither a series the agent does not publish nor a sum over one interface's five of several.
        assertFalse(ena.description().contains("per InstanceId"), ena.description());
        assertFalse(ena.description().contains("[m1, m2, m3, m4, m5]"), ena.description());
        // Nor the unclamped sum, which a driver reset on one interface pulls below another's exceed.
        assertFalse(ena.description().contains("60 * SUM(RATE(METRICS()))"), ena.description());
        assertTrue(ec2.source().contains("cumulative on each network interface since the last driver reset"),
                ec2.source());
        assertTrue(ec2.source().contains("one series per interface (dimensions interface and driver, plus the"
                + " agent's hostname unless its omit_hostname is true, or in its place what the agent's"
                + " append_dimensions adds, such as InstanceId)"), ec2.source());
        assertTrue(ec2.source().contains("(append_dimensions, which also stops the hostname dimension, as"
                + " omit_hostname does)"), ec2.source());
        assertTrue(ec2.source().contains("the volume checks are not published for magnetic (standard) or"
                + " Multi-Attach enabled volumes"), ec2.source());
        assertTrue(ec2.source().contains("ABS of an array"), ec2.source());
        // The type's one-line summary names every EBS KPI it ships, throughput among them.
        assertEquals("CPU credits, EBS burst, IOPS, throughput and limits, network allowances and status checks:"
                + " what AWS sees outside the guest", ec2.summary());
        assertEquals("/min", ena.unit());
        assertEquals("max", ena.reducer().key());
        assertNull(ena.ceiling());
        for (CatalogueMetric m : Arrays.asList(limit, ena)) {
            assertEquals(Predicate.Op.GT, m.safeWarn().op());
            assertEquals(0.0, m.safeWarn().value());
            assertNull(m.safeFail(), m.name() + ": a warn, as the research says");
            assertEquals("per instance", m.dimension());
        }
        assertFalse(ec2.source().contains("later version"), "the four ship now: " + ec2.source());
        // AWS/EBS's Nitro-only per-minute averages are a second route to IOPS and throughput: the
        // maintainer's note says why the descriptions give the form every instance publishes.
        assertTrue(ec2.source().contains("VolumeAvgIOPS (ops/s) and VolumeAvgThroughput (KiB/s)"), ec2.source());
        // And why the AWS/EC2 route names its period, where the source tells a maintainer to set --step.
        assertTrue(ec2.source().contains("AWS/EC2's EBSReadOps, EBSWriteOps, EBSReadBytes and EBSWriteBytes are"
                + " 5-minute data under basic monitoring"), ec2.source());
        assertTrue(ec2.source().contains("so that route needs a period of at least 300 s there"), ec2.source());
    }

    /**
     * The kafka type assumes the JMX exporter with Strimzi's rules, which leave RequestMetrics out
     * ({@code type=(?!RequestMetrics)}) and read RequestHandlerAvgIdlePercent from the meter's
     * MeanRate, an average since the broker started; kafka_exporter has no lag in seconds. A query
     * under that assumption would read nothing, or a number that barely moves in a test, so these
     * four ship with no prometheus query, each answered by the tools that have a current series. A
     * description that offered Prometheus nothing would leave its user guessing, so each says so.
     */
    @Test
    void theKafkaKpisStrimzisRulesCannotAnswerHaveNoPrometheusQuery() {
        CatalogueType kafka = catalogue.type("kafka");
        List<String> neutral = new ArrayList<>();
        for (CatalogueMetric m : kafka.metrics().values()) {
            if (m.neutral()) neutral.add(m.name());
        }
        assertEquals(Arrays.asList("consumer.lag.seconds.max", "request.produce.latency.p99",
                "request.fetch_consumer.latency.p99", "request.handler.idle"), neutral);

        CatalogueMetric produce = kafka.metric("request.produce.latency.p99");
        assertEquals("kafka:request.produce.latency.p99", produce.identity(null));
        assertEquals("kafka.request.produce.time.99percentile", produce.name("datadog").metric());
        assertEquals("kafka.request.time.99p", produce.name("otel").metric());
        assertTrue(produce.name("otel").note().startsWith("type=produce"), produce.name("otel").note());
        assertTrue(produce.declaresNone("cloudwatch"), "MSK publishes a mean, no percentile");
        CatalogueMetric fetch = kafka.metric("request.fetch_consumer.latency.p99");
        assertEquals("kafka.request.fetch_consumer.time.99percentile", fetch.name("datadog").metric());
        // One OTel metric holds both request types; a file holds one series per name, and the
        // produce latency reads it (twoMetricsThatShareAToolsNameReadOneSeries).
        assertFalse(fetch.declares("otel"), "kafka.request.time.99p is the produce latency's");
        for (CatalogueMetric m : Arrays.asList(produce, fetch)) {
            assertEquals("ms", m.unit());
            assertTrue(m.description().contains("Strimzi's rules leave request metrics out")
                    && m.description().contains("custom"), m.name() + ": " + m.description());
            assertFalse(m.recommended(), m.name() + ": the type's own exporter cannot answer it");
        }
        CatalogueMetric handler = kafka.metric("request.handler.idle");
        assertEquals("kafka.request.handler.avg.idle.pct.rate", handler.name("datadog").metric());
        assertTrue(handler.name("datadog").note().startsWith("the one-minute rate"), handler.name("datadog").note());
        assertFalse(handler.recommended());
        // Its sibling is a gauge of the current ratio, so it keeps Strimzi's series.
        assertEquals("100 * min(kafka_network_socketserver_networkprocessoravgidle_percent)",
                kafka.metric("network.processor.idle").query(Catalogue.PROMETHEUS, null));
        assertFalse(kafka.metric("network.processor.idle").recommended());
        assertTrue(kafka.source().contains("(?!RequestMetrics)") && kafka.source().contains("MeanRate"),
                kafka.source());
    }

    /**
     * A KRaft cluster with dedicated controller nodes reports ActiveControllerCount from the
     * controllers only (QuorumControllerMetrics, which only ControllerServer creates): scraped from
     * the brokers alone there is no series at all, so the assert is NOT_JUDGED / NO_MATCH, a skip
     * and not a configuration error. The silent skip is the risk, not a false FAIL, and the picker
     * says so.
     */
    @Test
    void controllerActiveSaysAKraftClusterIsReadOnItsControllers() {
        CatalogueMetric active = catalogue.type("kafka").metric("controller.active");
        assertTrue(active.description().contains("KRaft") && active.description().contains("controllers"),
                active.description());
        assertTrue(active.description().contains("no series") && active.description().contains("NOT_JUDGED"),
                active.description());
        assertEquals(Predicate.Op.LT, active.safeFail().op());
    }

    /**
     * PostgreSQL 17 moved the checkpoint counters to pg_stat_checkpointer: postgres_exporter stops
     * emitting pg_stat_bgwriter_checkpoints_req_total there, and its stat_checkpointer collector is
     * off by default. The query reads either series, never both, and the description says what 17
     * needs.
     */
    @Test
    void requestedCheckpointsReadEitherPostgresVersionsSeriesAndSayWhat17Needs() {
        CatalogueMetric checkpoints = catalogue.type("postgres").metric("checkpoints.requested");
        String q = checkpoints.query(Catalogue.PROMETHEUS, null);
        assertEquals("sum by (instance) (increase(pg_stat_bgwriter_checkpoints_req_total[1m])) or"
                + " sum by (instance) (increase(pg_stat_checkpointer_num_requested_total[1m]))", q);
        assertTrue(checkpoints.description().contains("stat_checkpointer collector, which is off by default"),
                checkpoints.description());
    }

    /**
     * Within one type, the metrics that name one tool's metric read one series: a file holds one
     * series per instance under a name, so two metrics reading it with different filters, folds or
     * conversions would each read the other's rows. Two that share it must say the same of it.
     */
    static List<String> sharedNameProblems(CatalogueType type) {
        List<String> problems = new ArrayList<>();
        java.util.Map<String, CatalogueMetric> first = new java.util.LinkedHashMap<>();
        for (CatalogueMetric m : type.metrics().values()) {
            for (ToolName n : m.names().values()) {
                CatalogueMetric before = first.putIfAbsent(n.tool() + " " + n.metric(), m);
                if (before == null) continue;
                ToolName b = before.name(n.tool());
                if (b.kind() != n.kind() || b.scale() != n.scale() || !java.util.Objects.equals(b.note(), n.note())) {
                    problems.add(type.key() + ": " + before.name() + " and " + m.name() + " both read " + n.tool() + "'s "
                            + n.metric() + ", as two different series");
                }
            }
        }
        return problems;
    }

    @Test
    void twoMetricsThatShareAToolsNameReadOneSeries() throws Exception {
        CatalogueType clash = Catalogue.readType("kafka", JSON.readTree("{\"type\":\"kafka\",\"version\":1,"
                + "\"exporter\":\"e\",\"metrics\":["
                + "{\"name\":\"lag.max\",\"names\":{\"datadog\":{\"metric\":\"kafka.consumer_lag\",\"kind\":\"gauge\","
                + "\"note\":\"max by consumer_group, topic\"}}},"
                + "{\"name\":\"lag.total\",\"names\":{\"datadog\":{\"metric\":\"kafka.consumer_lag\",\"kind\":\"gauge\","
                + "\"note\":\"sum by consumer_group, topic\"}}},"
                + "{\"name\":\"lag.final\",\"reduce\":\"last\",\"names\":{\"datadog\":{\"metric\":\"kafka.consumer_lag\","
                + "\"kind\":\"gauge\",\"note\":\"sum by consumer_group, topic\"}}}]}"));
        assertEquals(Arrays.asList("kafka: lag.max and lag.total both read datadog's kafka.consumer_lag, as two"
                + " different series", "kafka: lag.max and lag.final both read datadog's kafka.consumer_lag, as two"
                + " different series"), sharedNameProblems(clash));
        for (CatalogueType type : catalogue.types().values()) {
            assertEquals(new ArrayList<String>(), sharedNameProblems(type), type.key());
        }
    }

    /**
     * Every query was read with asText(), so an object loaded as "" and a number as "42": a
     * metric that shipped with a query nobody wrote, asked of Prometheus as written.
     */
    @Test
    void aQueryThatIsNotTextCannotShip() {
        assertEquals("postgres.a: the prometheus query {\"expr\":\"pg_up\"} is not text",
                refusal("\"queries\":{\"prometheus\":{\"expr\":\"pg_up\"}}"));
        assertEquals("postgres.a: the prometheus query 42 is not text", refusal("\"queries\":{\"prometheus\":42}"));
        assertEquals("postgres.a: the prometheus query null is not text - leave the backend out when the metric has"
                + " no query for it", refusal("\"queries\":{\"prometheus\":null}"));
        assertEquals("postgres.a: the prometheus query is empty - leave the backend out when the metric has no"
                + " query for it", refusal("\"queries\":{\"prometheus\":\"\"}"));
        assertEquals("postgres.a: queries \"pg_up\" is not an object of backend queries",
                refusal("\"queries\":\"pg_up\""));
    }

    /**
     * A KPI with no Prometheus query is still a series, and a series needs an identity: the page,
     * the payload and the evaluator find it by one. The neutral one is {@code <type>:<metric>}, the
     * component's match in braces when it has one. A metric WITH a query keeps it as its identity,
     * character for character, so no committed payload, golden or collectionId moves.
     */
    @Test
    void aMetricWithNoPrometheusQueryIsKnownByItsNeutralIdentity() throws Exception {
        CatalogueMetric m = typeWith("\"names\":{\"datadog\":{\"metric\":\"postgresql.x\",\"kind\":\"gauge\"}}")
                .metric("a");
        assertTrue(m.neutral());
        assertEquals("postgres", m.type());
        assertEquals("postgres.a", m.qualifiedName());
        assertNull(m.query(Catalogue.PROMETHEUS, null));
        assertEquals("postgres:a", m.identity(null));
        assertEquals("postgres:a", m.identity("  "));
        assertEquals("postgres:a{instance=\"db-1\"}", m.identity(" instance=\"db-1\"\t"));
        // Trimmed as String.trim trims, so a no-break space stays: the page's javaTrim keeps it too.
        char nbsp = (char) 0xA0;
        assertEquals("postgres:a{instance=\"db-1\"" + nbsp + "}", m.identity("\tinstance=\"db-1\"" + nbsp + " "));
        // A query for another backend is no prometheus query.
        assertTrue(typeWith("\"queries\":{\"influx\":\"SELECT 1\"}").metric("a").neutral());

        // Every built-in metric: one with a query is known by it, as before; one a later file ships
        // with only tools' names is known by its type and name - a shape this pins, not refuses.
        for (CatalogueType type : catalogue.types().values()) {
            for (CatalogueMetric metric : type.metrics().values()) {
                String where = type.key() + "." + metric.name();
                for (String match : Arrays.asList(null, "  ", "instance=\"db-1:9187\"", " instance=\"db-1:9187\" ")) {
                    if (metric.neutral()) {
                        assertTrue(metric.identity(match).startsWith(type.key() + ":" + metric.name()),
                                where + " is known by its neutral identity: " + metric.identity(match));
                    } else {
                        assertEquals(metric.query(Catalogue.PROMETHEUS, match), metric.identity(match),
                                where + " is known by its prometheus query, as before");
                    }
                }
            }
        }

        // The page reads the file as read, so it gets names with no change of its own.
        Catalogue built = Catalogue.fromFiles(java.util.Collections.singletonMap("postgres", JSON.readTree(
                "{\"type\":\"postgres\",\"version\":1,\"exporter\":\"e\",\"metrics\":[{\"name\":\"a\","
                        + "\"names\":{\"telegraf\":null,\"otel\":{\"metric\":\"o\",\"kind\":\"rate\"}}}]}")));
        assertTrue(built.type("postgres").metric("a").neutral());
        assertEquals("{\"order\":[\"postgres\"],\"types\":{\"postgres\":{\"type\":\"postgres\",\"version\":1,"
                        + "\"exporter\":\"e\",\"metrics\":[{\"name\":\"a\",\"names\":{\"telegraf\":null,"
                        + "\"otel\":{\"metric\":\"o\",\"kind\":\"rate\"}}}]}}}",
                JSON.writeValueAsString(built.pageJson()));
    }

    // ---- oracle, influxdb2 and influxdb3 ---------------------------------------------------------

    /**
     * Telegraf has no input that names these KPIs: no Oracle input at all ({@code inputs.sql} runs
     * whatever query the tester writes, under the names they give it), and {@code inputs.influxdb}
     * reads InfluxDB 1.x only, while 2.x and 3.x go through {@code inputs.prometheus}, whose names
     * depend on its {@code metric_version}. Said once, in the type's source, and declared none on
     * every metric rather than repeated fourteen times.
     */
    @Test
    void telegrafNamesNoOracleOrInfluxDbMetricAndEachSourceSaysWhy() {
        for (String key : Arrays.asList("oracle", "influxdb2", "influxdb3")) {
            for (CatalogueMetric m : catalogue.type(key).metrics().values()) {
                assertTrue(m.declaresNone("telegraf"), key + "." + m.name() + ": Telegraf has no metric to name");
            }
        }
        assertTrue(catalogue.type("oracle").source().contains("Telegraf has no Oracle input"),
                catalogue.type("oracle").source());
        for (String key : Arrays.asList("influxdb2", "influxdb3")) {
            String source = catalogue.type(key).source();
            assertTrue(source.contains("inputs.influxdb reads InfluxDB 1.x only")
                    && source.contains("metric_version"), key + ": " + source);
        }
    }

    /**
     * Two types, one set of ids: an annotations file reads the same against either version and
     * only its {@code type} changes. A shared id means the same thing on both — label, unit,
     * reducer, fold, direction, ceiling, recommendation and safe bands — and only the query, which
     * the two engines' metrics make different, differs. Each version adds four of its own.
     */
    @Test
    void influxDb2AndInfluxDb3ShareTenKpiIdsAndEachAddsFourOfItsOwn() {
        List<String> shared = Arrays.asList("up", "write.requests.rate", "write.points.rate", "write.latency.p95",
                "write.errors.pct", "write.rejected", "query.requests.rate", "query.latency.p95", "query.errors.pct",
                "memory.used.bytes");
        List<String> v2 = new ArrayList<>(shared);
        v2.addAll(Arrays.asList("series.count", "compactions.failed", "query.queued", "disk.growth"));
        List<String> v3 = new ArrayList<>(shared);
        v3.addAll(Arrays.asList("query.permit.wait.p95", "query.ooms", "objectstore.errors", "panics"));
        CatalogueType two = catalogue.type("influxdb2");
        CatalogueType three = catalogue.type("influxdb3");
        assertEquals(v2, new ArrayList<>(two.metrics().keySet()));
        assertEquals(v3, new ArrayList<>(three.metrics().keySet()));
        for (String id : shared) {
            CatalogueMetric a = two.metric(id);
            CatalogueMetric b = three.metric(id);
            assertEquals(a.label(), b.label(), id + " label");
            assertEquals(a.category(), b.category(), id + " category");
            assertEquals(a.unit(), b.unit(), id + " unit");
            assertEquals(a.reducer().key(), b.reducer().key(), id + " reducer");
            assertEquals(a.across(), b.across(), id + " across");
            assertEquals(a.direction(), b.direction(), id + " direction");
            assertEquals(a.ceiling(), b.ceiling(), id + " ceiling");
            assertEquals(a.recommended(), b.recommended(), id + " recommended");
            assertEquals(String.valueOf(a.safeWarn()), String.valueOf(b.safeWarn()), id + " safe warn");
            assertEquals(String.valueOf(a.safeFail()), String.valueOf(b.safeFail()), id + " safe fail");
            assertFalse(a.query(Catalogue.PROMETHEUS, null).equals(b.query(Catalogue.PROMETHEUS, null))
                    && !id.equals("up"), id + ": the two engines' metrics differ, so their queries do");
        }
        // The research's rows: nine recommended on 2.x, ten on 3.x (its summary line said nine for both).
        assertEquals(9, two.metrics().values().stream().filter(CatalogueMetric::recommended).count());
        assertEquals(10, three.metrics().values().stream().filter(CatalogueMetric::recommended).count());

        // One label, two vocabularies: v2's status is the class, v3's a word (status_response_writer.go,
        // trace_http/src/metrics.rs).
        assertTrue(two.metric("write.errors.pct").queries().get(Catalogue.PROMETHEUS).contains("status=\"5XX\""));
        assertTrue(three.metric("write.errors.pct").queries().get(Catalogue.PROMETHEUS)
                .contains("status=\"server_error\""));
    }

    /**
     * Only names that were verified ship. Datadog has no InfluxDB check of its own; OpenTelemetry
     * reaches /metrics only through its Prometheus receiver, whose names were not verified; and
     * Amazon Timestream for InfluxDB's CloudWatch namespace is unverified (and it publishes 2.x
     * engine metrics only), so no InfluxDB metric names any of the three — a 2.x one may say
     * CloudWatch has none.
     */
    @Test
    void anInfluxDbMetricNamesNoDatadogOtelOrCloudWatchMetric() {
        for (String key : Arrays.asList("influxdb2", "influxdb3")) {
            for (CatalogueMetric m : catalogue.type(key).metrics().values()) {
                String where = key + "." + m.name();
                assertFalse(m.declares("datadog"), where + ": Datadog has no InfluxDB check");
                assertFalse(m.declares("otel"), where + ": OTel's names through its Prometheus receiver are unverified");
                assertNull(m.name("cloudwatch"), where + ": Timestream for InfluxDB's namespace is unverified");
                if (key.equals("influxdb3")) {
                    assertFalse(m.declares("cloudwatch"), where + ": Timestream publishes 2.x engine metrics only");
                }
            }
        }
        assertTrue(catalogue.type("influxdb2").source().contains("namespace"), catalogue.type("influxdb2").source());
        // Enterprise is closed source: the names were read in Core's, and the type says so.
        String v3 = catalogue.type("influxdb3").source();
        assertTrue(v3.contains("Enterprise") && v3.contains("unverified"), v3);
    }

    /**
     * In the Oracle exporter 2.x, {@code oracledb_wait_time_<class>} is a counter of seconds
     * waited ({@code round(time_waited/100,3)}, {@code metricstype} counter), so a query rates it:
     * seconds waited per second is the average number of sessions waiting. The predecessor
     * iamseth/oracledb_exporter used the same names for a per-interval gauge, which these misread.
     */
    @Test
    void theOracleExportersWaitTimeIsACounterAndIsRated() {
        CatalogueType oracle = catalogue.type("oracle");
        assertEquals("sum by (instance) (rate(oracledb_wait_time_user_io[1m]))",
                oracle.metric("wait.user_io.sessions").query(Catalogue.PROMETHEUS, null));
        int waits = 0;
        for (CatalogueMetric m : oracle.metrics().values()) {
            String q = m.queries().get(Catalogue.PROMETHEUS);
            if (q != null && q.contains("oracledb_wait_time_")) {
                waits++;
                assertTrue(q.matches(".*rate\\(oracledb_wait_time_[a-z_]+\\$\\{match}\\[1m]\\).*"), m.name() + ": " + q);
            }
        }
        assertEquals(3, waits, "concurrency, user I/O and commit");
        assertTrue(oracle.source().contains("iamseth/oracledb_exporter"), oracle.source());
    }

    /**
     * The exporter reads a permanent tablespace's used_percent from dba_tablespace_usage_metrics
     * (0-100) and computes a TEMPORARY one as (size - free) / size, a 0-1 fraction, under the same
     * name: a TEMP tablespace 99 % full reads 0.99 and passes any band. The query leaves TEMP out.
     */
    @Test
    void theOracleTablespaceQueryLeavesOutTheTemporaryRowsTheExporterReportsAsAFraction() {
        CatalogueMetric tablespace = catalogue.type("oracle").metric("tablespace.used.pct");
        assertEquals("max by (instance) (oracledb_tablespace_used_percent{type!=\"TEMPORARY\"})",
                tablespace.query(Catalogue.PROMETHEUS, null));
        assertTrue(tablespace.description().contains("TEMP"), tablespace.description());
    }

    /** The exporter's default metrics (2.5.0's default-metrics.toml) the oracle queries read. */
    private static final List<String> ORACLE_DEFAULT_SERIES = Arrays.asList("oracledb_up",
            "oracledb_resource_current_utilization", "oracledb_resource_limit_value", "oracledb_sessions_value",
            "oracledb_db_system_value", "oracledb_wait_time_concurrency", "oracledb_wait_time_user_io",
            "oracledb_wait_time_commit", "oracledb_tablespace_used_percent", "oracledb_cache_hit_ratio_value");

    /**
     * Five Oracle KPIs read series the exporter's defaults lack: blocked sessions, DB CPU, log
     * switches, hard parses and deadlocks. They come from the custom metrics file Tutorial 13 gives,
     * so the queries and that file must name the same series: a context renamed in one would leave
     * the other reading nothing, in silence. The names are derived from the tutorial's TOML by the
     * exporter's own rule (collector.go): {@code oracledb_<context>_<field>}, or, with
     * {@code fieldtoappend}, {@code oracledb_<context>_<the row's value, cleaned>}. Each of the five
     * says in its description that it needs the file.
     */
    @Test
    void theOracleQueriesReadEitherADefaultSeriesOrOneTheTutorialsCustomMetricsDefine() throws Exception {
        java.util.Set<String> custom = customMetricNames(repoFile("docs/Tutorial/13-Server-Tab.md"));
        assertEquals(new java.util.TreeSet<>(Arrays.asList("oracledb_blocked_sessions_value",
                "oracledb_sysstat_cpu_used_by_this_session", "oracledb_sysstat_parse_count_hard",
                "oracledb_sysstat_enqueue_deadlocks", "oracledb_log_switches_last_minute")), custom);
        Pattern series = Pattern.compile("oracledb_[a-z0-9_]+");
        java.util.Set<String> read = new java.util.TreeSet<>();
        List<String> needsFile = new ArrayList<>();
        for (CatalogueMetric m : catalogue.type("oracle").metrics().values()) {
            Matcher s = series.matcher(m.queries().get(Catalogue.PROMETHEUS));
            boolean customSeries = false;
            while (s.find()) {
                read.add(s.group());
                customSeries |= custom.contains(s.group());
                assertTrue(custom.contains(s.group()) || ORACLE_DEFAULT_SERIES.contains(s.group()),
                        m.name() + " reads " + s.group() + ", which neither the defaults nor the tutorial's file define");
            }
            if (customSeries) {
                needsFile.add(m.name());
                assertTrue(m.description().contains("custom metrics file"), m.name() + ": " + m.description());
            } else {
                assertFalse(m.description().contains("custom metrics file"), m.name() + ": " + m.description());
            }
        }
        assertTrue(read.containsAll(custom), "every series the tutorial's file adds is read: " + read);
        assertEquals(Arrays.asList("sessions.blocked", "db.cpu.pct", "log.switches.per.min", "parses.hard.rate",
                "deadlocks"), needsFile);
        assertTrue(catalogue.type("oracle").source().contains("custom metrics file"), catalogue.type("oracle").source());
    }

    /**
     * The tutorial's requests count what the two metrics' descriptions say. A count of every
     * session whose {@code blocking_session_status} is VALID also counts sessions waiting on a
     * commit, which name LGWR as their blocker: under a commit-heavy load it climbs with
     * {@code wait.commit.sessions} while its description says locks. Application is the wait class
     * Oracle defines as lock waits from row-level locking or explicit lock commands. And
     * {@code v$log_history} holds a row only for a log already switched out, so the log the latest
     * switch opened, CURRENT in {@code v$log}, is counted from there: without it k switches in the
     * last minute read k - 1, and a minute with one switch reads 0.
     */
    @Test
    void theTutorialsOracleRequestsCountWhatTheirDescriptionsSay() throws Exception {
        java.util.Map<String, String> requests = customRequests(repoFile("docs/Tutorial/13-Server-Tab.md"));
        String blocked = requests.get("blocked_sessions");
        assertTrue(blocked.contains("where blocking_session_status = 'VALID' and wait_class = 'Application'"), blocked);
        String switches = requests.get("log_switches");
        assertTrue(switches.contains("from v$log_history where first_time > sysdate - 1/1440"), switches);
        assertTrue(switches.contains("from v$log where status = 'CURRENT' and first_time > sysdate - 1/1440"),
                switches);
        // UNION, not UNION ALL: a log in both views is one switch, not two.
        assertTrue(switches.contains(" union select ") && !switches.contains("union all"), switches);
    }

    /**
     * Host CPU is not the database's CPU: Datadog's host_cpu_utilization, OTel's
     * host.cpu.utilization and AWS/RDS CPUUtilization include every process on the machine, so no
     * Oracle KPI reads them as its own. And a tool name that needs something switched on says so.
     */
    @Test
    void anOracleToolNameIsTheDatabasesOwnAndSaysWhatItNeeds() {
        List<String> host = Arrays.asList("oracle.host_cpu_utilization", "oracledb.host.cpu.utilization",
                "AWS/RDS:CPUUtilization");
        CatalogueType oracle = catalogue.type("oracle");
        for (CatalogueMetric m : oracle.metrics().values()) {
            for (ToolName n : m.names().values()) {
                assertFalse(host.contains(n.metric()), "oracle." + m.name() + " reads host CPU: " + n);
            }
        }
        for (String tool : Arrays.asList("datadog", "otel", "cloudwatch")) {
            assertTrue(oracle.metric("db.cpu.pct").declaresNone(tool), tool);
        }
        // Datadog's Oracle check sends these sysmetrics only with Database Monitoring on (sysmetrics.go).
        for (String id : Arrays.asList("processes.used.pct", "parses.hard.rate", "deadlocks")) {
            assertTrue(oracle.metric(id).name("datadog").note().contains("dbm: true"), id);
        }
        // Performance Insights' load metrics exist only with it on, and only while there is load.
        ToolName load = oracle.metric("sessions.active.per.cpu").name("cloudwatch");
        assertEquals("AWS/RDS:DBLoadRelativeToNumVCPUs", load.metric());
        assertTrue(load.note().contains("Performance Insights"), load.note());
    }

    // ---- jvm, http-service, kubernetes-pod and mysql ---------------------------------------------

    /** Every series name {@code pattern} finds in the type's prometheus queries is one of {@code series}, and each is read. */
    private void assertQueriesReadExactly(String key, Pattern pattern, List<String> series) {
        java.util.Set<String> read = new java.util.TreeSet<>();
        for (CatalogueMetric m : catalogue.type(key).metrics().values()) {
            Matcher s = pattern.matcher(m.queries().get(Catalogue.PROMETHEUS));
            while (s.find()) {
                read.add(s.group());
                assertTrue(series.contains(s.group()), key + "." + m.name() + " reads " + s.group()
                        + ", a name the type's exporter was not checked to publish");
            }
        }
        assertEquals(new java.util.TreeSet<>(series), read, key + ": every checked series is read");
    }

    /**
     * The jvm and http-service types read Micrometer's names as its Prometheus registry writes them,
     * which is what Spring Boot Actuator serves: a gauge ends in its base unit, a timer in _seconds
     * with _count, _sum and _max (and _bucket with a histogram). Another library names the same
     * things otherwise, so each source says the type assumes Micrometer and what to do without it.
     */
    @Test
    void theJvmAndHttpTypesReadMicrometersPrometheusNames() {
        assertQueriesReadExactly("jvm", Pattern.compile("(jvm|process)_[a-z_]+"), Arrays.asList("jvm_memory_used_bytes",
                "jvm_memory_max_bytes", "jvm_gc_live_data_size_bytes", "jvm_gc_pause_seconds_max",
                "jvm_gc_pause_seconds_sum", "jvm_gc_pause_seconds_count", "jvm_threads_live_threads", "process_cpu_usage"));
        assertQueriesReadExactly("http-service", Pattern.compile("http_[a-z_]+"), Arrays.asList(
                "http_server_requests_seconds_count", "http_server_requests_seconds_sum",
                "http_server_requests_seconds_max", "http_server_requests_seconds_bucket"));
        for (String key : Arrays.asList("jvm", "http-service")) {
            String source = catalogue.type(key).source();
            assertTrue(source.contains("Micrometer") && source.contains("custom query"), key + ": " + source);
        }
    }

    /**
     * Micrometer passes a memory pool's {@code MemoryUsage.getMax()} through, -1 for a pool with no
     * maximum (G1's eden and survivors), and holds {@code jvm.gc.live.data.size} at 0 until the first
     * collection that measures it. Summed as they come, the first would shrink the heap's maximum and
     * the second would read as growth from nothing, so each query keeps only the values above 0.
     * Generational ZGC and generational Shenandoah give their young pool the whole heap's maximum as
     * well as their old pool (OpenJDK's zServiceability.cpp, shenandoahMemoryPool.cpp), so the
     * maxima would add up to twice the heap and a JVM about to run out would read half full: the
     * denominator leaves those two young pools out, by the {@code id} Micrometer gives a pool.
     */
    @Test
    void theJvmQueriesLeaveOutMicrometersPlaceholderValues() {
        CatalogueType jvm = catalogue.type("jvm");
        assertEquals("100 * sum by (instance) (jvm_memory_used_bytes{area=\"heap\"}) / sum by (instance)"
                        + " (jvm_memory_max_bytes{area=\"heap\",id!~\"ZGC Young Generation|Shenandoah Young Gen\"} > 0)",
                jvm.metric("heap.used.pct").query(Catalogue.PROMETHEUS, null));
        assertEquals("100 * sum by (instance) (jvm_memory_used_bytes{area=\"heap\",job=\"orders\"}) / sum by (instance)"
                        + " (jvm_memory_max_bytes{area=\"heap\",id!~\"ZGC Young Generation|Shenandoah Young Gen\","
                        + "job=\"orders\"} > 0)",
                jvm.metric("heap.used.pct").query(Catalogue.PROMETHEUS, "job=\"orders\""));
        assertTrue(jvm.source().contains("ZGC Young Generation") && jvm.source().contains("Shenandoah Young Gen"),
                jvm.source());
        assertEquals("sum by (instance) (jvm_gc_live_data_size_bytes > 0)",
                jvm.metric("heap.live.growth").query(Catalogue.PROMETHEUS, null));
        assertEquals("sum by (instance) (jvm_gc_live_data_size_bytes{job=\"orders\"} > 0)",
                jvm.metric("heap.live.growth").query(Catalogue.PROMETHEUS, "job=\"orders\""));
        assertEquals("slope", jvm.metric("heap.live.growth").reducer().key());
    }

    /**
     * Micrometer publishes the request histogram only when it is switched on, so the p95 says how,
     * and is not offered first. No tool names these KPIs: Datadog's APM metrics carry the span name,
     * which depends on the integration, and OpenTelemetry's http.server.request.duration is a
     * histogram the rate, the share, the mean and the p95 are each computed from.
     */
    @Test
    void theHttpP95SaysItNeedsTheHistogramAndNoToolHasASingleMetric() {
        CatalogueType http = catalogue.type("http-service");
        CatalogueMetric p95 = http.metric("latency.p95");
        assertTrue(p95.description().contains("percentiles-histogram.http.server.requests=true"), p95.description());
        assertFalse(p95.recommended(), "Spring Boot leaves the histogram off");
        for (CatalogueMetric m : http.metrics().values()) {
            for (String tool : Arrays.asList("telegraf", "datadog", "otel")) {
                assertTrue(m.declaresNone(tool), m.name() + ": " + tool);
            }
            assertFalse(m.declares("cloudwatch"), m.name());
        }
        assertTrue(http.source().contains("trace.<SPAN_NAME>") && http.source().contains("http.server.request.duration"),
                http.source());
    }

    /**
     * Micrometer creates a timer for a tag set the first time it records one, and status is a tag:
     * a service that has returned no 5xx since it started has no 5xx series. Divided as they come,
     * the share would have no data on a healthy run (NO_MATCH, which decides nothing), and a 5xx
     * that first appears partway through would cover too little of the window to judge. The
     * numerator falls back to 0 per instance that served anything, as restarts.oom does, so a
     * healthy run passes rather than going unmatched; the first 5xx of a new series are still
     * not counted by rate(), and the description and the source say so.
     */
    @Test
    void theHttp5xxShareReadsZeroOnAServiceThatHasNeverFailed() {
        CatalogueMetric share = catalogue.type("http-service").metric("errors.5xx.pct");
        assertEquals("100 * (sum by (instance) (rate(http_server_requests_seconds_count{status=~\"5..\"}[1m])) or 0 * sum"
                        + " by (instance) (rate(http_server_requests_seconds_count[1m]))) / sum by (instance)"
                        + " (rate(http_server_requests_seconds_count[1m]))",
                share.query(Catalogue.PROMETHEUS, null));
        assertEquals("100 * (sum by (instance) (rate(http_server_requests_seconds_count{status=~\"5..\",job=\"orders\"}[1m]))"
                        + " or 0 * sum by (instance) (rate(http_server_requests_seconds_count{job=\"orders\"}[1m]))) / sum by"
                        + " (instance) (rate(http_server_requests_seconds_count{job=\"orders\"}[1m]))",
                share.query(Catalogue.PROMETHEUS, "job=\"orders\""));
        assertTrue(share.description().contains("not counted"), share.description());
        assertTrue(catalogue.type("http-service").source().contains("rate() takes as the baseline"),
                catalogue.type("http-service").source());
    }

    /**
     * A pod's KPIs are per container, and three divide a cAdvisor series by a kube-state-metrics
     * one. The two exporters share namespace, pod and container and little else, so every query
     * groups by exactly those, and the source says to match on them.
     */
    @Test
    void theKubernetesQueriesArePerContainerAndJoinOnTheLabelsBothExportersCarry() {
        CatalogueType pod = catalogue.type("kubernetes-pod");
        Pattern by = Pattern.compile("(by|on) \\(([^)]*)\\)");
        for (CatalogueMetric m : pod.metrics().values()) {
            String q = m.queries().get(Catalogue.PROMETHEUS);
            Matcher g = by.matcher(q);
            int groups = 0;
            while (g.find()) {
                groups++;
                assertEquals("namespace, pod, container", g.group(2), m.name() + ": " + q);
            }
            assertTrue(groups > 0, m.name() + " keeps no container apart: " + q);
            assertEquals("per container", m.dimension(), m.name());
        }
        assertTrue(pod.source().contains("match on namespace, pod or container"), pod.source());
        // The page never shows the source: the card says it too, and so does every metric whose
        // safe default fails the run, which without a match judges every container in the cluster.
        assertTrue(pod.summary().contains("match by namespace/pod"), pod.summary());
        for (CatalogueMetric m : pod.metrics().values()) {
            if (m.safeFail() != null) {
                assertTrue(m.description().contains("Without a match"), m.name() + ": " + m.description());
            }
        }
        assertEquals(Predicate.Op.GT, pod.metric("restarts").safeFail().op());
        assertEquals(0.0, pod.metric("restarts").safeFail().value());
    }

    /**
     * kube-state-metrics holds a container's last termination reason until the next one, so a kill
     * before the run would read as one during it. The query counts the minute's restarts only while
     * that reason is OOMKilled, and every other container reads 0 rather than nothing: a healthy run
     * passes rather than going unmatched. The metric is experimental, and says so.
     */
    @Test
    void theOomRestartsCountOnlyRestartsWhoseLastTerminationWasAnOomKill() {
        CatalogueMetric oom = catalogue.type("kubernetes-pod").metric("restarts.oom");
        assertEquals("sum by (namespace, pod, container) (increase(kube_pod_container_status_restarts_total[1m]) and on"
                        + " (namespace, pod, container) kube_pod_container_status_last_terminated_reason{reason=\"OOMKilled\"})"
                        + " or 0 * sum by (namespace, pod, container) (kube_pod_container_status_restarts_total)",
                oom.query(Catalogue.PROMETHEUS, null));
        assertTrue(oom.query(Catalogue.PROMETHEUS, "namespace=\"shop\"")
                .contains("{reason=\"OOMKilled\",namespace=\"shop\"}"), oom.query(Catalogue.PROMETHEUS, "namespace=\"shop\""));
        assertTrue(oom.description().contains("experimental"), oom.description());
        assertTrue(catalogue.type("kubernetes-pod").source().contains("EXPERIMENTAL"));
        assertEquals(Predicate.Op.GT, oom.safeFail().op());
    }

    /**
     * mysqld_exporter names the lag after the column it reads, and MySQL 8.4 removed SHOW SLAVE
     * STATUS: there the exporter falls back to SHOW REPLICA STATUS, whose column is
     * Seconds_Behind_Source, while older MySQL and MariaDB answer Seconds_Behind_Master (8.0.22 to 8.3
     * rename the column only in SHOW REPLICA STATUS: SHOW SLAVE STATUS keeps the old names, WL#14171).
     * The query reads either, so a replica reports on every version.
     */
    @Test
    void theMysqlReplicaLagReadsTheColumnUnderEitherName() {
        CatalogueType mysql = catalogue.type("mysql");
        assertEquals("max by (instance) (mysql_slave_status_seconds_behind_master{instance=\"db-2\"} or"
                        + " mysql_slave_status_seconds_behind_source{instance=\"db-2\"})",
                mysql.metric("replication.lag.seconds").query(Catalogue.PROMETHEUS, "instance=\"db-2\""));
        assertTrue(mysql.source().contains("MySQL 8.4") && mysql.source().contains("MariaDB"), mysql.source());
        // max_connections + 1 may connect, and more on the administrative interface: no ceiling.
        CatalogueMetric used = mysql.metric("connections.used.pct");
        assertNull(used.ceiling());
        assertTrue(used.description().contains("past 100"), used.description());
        assertEquals(Predicate.Op.GT, mysql.metric("connections.refused").safeFail().op());
        // Telegraf's names hold with metric_version = 2 only, and each says so.
        for (CatalogueMetric m : mysql.metrics().values()) {
            ToolName telegraf = m.name("telegraf");
            if (telegraf != null) {
                assertTrue(telegraf.note().contains("metric_version = 2"), m.name() + ": " + telegraf.note());
            }
        }
    }

    /**
     * The series names the exporter makes of every {@code ```toml} block in a markdown file, by its
     * rule in collector.go: {@code oracledb_<context>_<metricsdesc key>}, or with
     * {@code fieldtoappend} {@code oracledb_<context>_<cleanName(value)>} — for a {@code gv$sysstat}
     * request, each quoted statistic name in it.
     */
    static java.util.Set<String> customMetricNames(java.nio.file.Path markdown) throws Exception {
        String text = new String(java.nio.file.Files.readAllBytes(markdown), java.nio.charset.StandardCharsets.UTF_8);
        java.util.Set<String> names = new java.util.TreeSet<>();
        Matcher block = Pattern.compile("```toml\\n(.*?)```", Pattern.DOTALL).matcher(text.replace("\r\n", "\n"));
        while (block.find()) {
            for (String metric : block.group(1).split("\\[\\[metric]]")) {
                Matcher context = Pattern.compile("context\\s*=\\s*\"([^\"]*)\"").matcher(metric);
                if (!context.find()) continue;
                Matcher append = Pattern.compile("fieldtoappend\\s*=\\s*\"([^\"]*)\"").matcher(metric);
                if (append.find()) {
                    Matcher request = Pattern.compile("request\\s*=\\s*'''(.*?)'''", Pattern.DOTALL).matcher(metric);
                    assertTrue(request.find(), "a fieldtoappend block with no request");
                    Matcher value = Pattern.compile("'([^']*)'").matcher(request.group(1));
                    while (value.find()) {
                        names.add("oracledb_" + context.group(1) + "_" + exporterCleanName(value.group(1)));
                    }
                } else {
                    Matcher desc = Pattern.compile("metricsdesc\\s*=\\s*\\{([^}]*)}").matcher(metric);
                    assertTrue(desc.find(), "a metric block with no metricsdesc");
                    Matcher key = Pattern.compile("([A-Za-z0-9_]+)\\s*=").matcher(desc.group(1));
                    while (key.find()) {
                        names.add("oracledb_" + context.group(1) + "_" + key.group(1).toLowerCase(java.util.Locale.ROOT));
                    }
                }
            }
        }
        return names;
    }

    /**
     * Each {@code [[metric]]} context in the {@code ```toml} blocks of a markdown file, and its
     * request with its whitespace collapsed to single spaces.
     */
    static java.util.Map<String, String> customRequests(java.nio.file.Path markdown) throws Exception {
        String text = new String(java.nio.file.Files.readAllBytes(markdown), java.nio.charset.StandardCharsets.UTF_8);
        java.util.Map<String, String> requests = new java.util.LinkedHashMap<>();
        Matcher block = Pattern.compile("```toml\\n(.*?)```", Pattern.DOTALL).matcher(text.replace("\r\n", "\n"));
        while (block.find()) {
            for (String metric : block.group(1).split("\\[\\[metric]]")) {
                Matcher context = Pattern.compile("context\\s*=\\s*\"([^\"]*)\"").matcher(metric);
                if (!context.find()) continue;
                Matcher request = Pattern.compile("request\\s*=\\s*'''(.*?)'''", Pattern.DOTALL).matcher(metric);
                assertTrue(request.find(), context.group(1) + ": a metric block with no request");
                requests.put(context.group(1), request.group(1).trim().replaceAll("\\s+", " "));
            }
        }
        return requests;
    }

    /** The exporter's cleanName (collector.go): spaces to underscores, ( ) / * dropped, lower case. */
    static String exporterCleanName(String s) {
        return s.replace(" ", "_").replace("(", "").replace(")", "").replace("/", "").replace("*", "")
                .toLowerCase(java.util.Locale.ROOT);
    }

    @Test
    void theExportersCleanNameIsMirrored() {
        assertEquals("user_io", exporterCleanName("User I/O"));
        assertEquals("parse_count_hard", exporterCleanName("parse count (hard)"));
        assertEquals("cpu_used_by_this_session", exporterCleanName("CPU used by this session"));
    }

    /**
     * A file of this repository, found above the module the test runs in. The Oracle queries are
     * held to Tutorial 13's custom metrics TOML, so these tests need a full checkout.
     */
    static java.nio.file.Path repoFile(String relative) {
        java.nio.file.Path start = java.nio.file.Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (java.nio.file.Path d = start; d != null; d = d.getParent()) {
            java.nio.file.Path p = d.resolve(relative);
            if (java.nio.file.Files.isRegularFile(p)) return p;
        }
        throw new AssertionError(relative + " not found above " + start + ": the oracle catalogue's queries are"
                + " held to the custom metrics TOML in that tutorial, so this test needs the repository's docs/");
    }

    private static void assertBalanced(String where, String q) {
        List<Character> stack = new ArrayList<>();
        boolean inString = false;
        for (int i = 0; i < q.length(); i++) {
            char c = q.charAt(i);
            if (c == '"' && (i == 0 || q.charAt(i - 1) != '\\')) inString = !inString;
            if (inString) continue;
            if (c == '(' || c == '{' || c == '[') stack.add(c);
            if (c == ')' || c == '}' || c == ']') {
                assertFalse(stack.isEmpty(), where + ": unbalanced '" + c + "' in " + q);
                char open = stack.remove(stack.size() - 1);
                assertEquals("({[".indexOf(open), ")}]".indexOf(c), where + ": mismatched brackets in " + q);
            }
        }
        assertTrue(stack.isEmpty(), where + ": unclosed bracket in " + q);
        assertFalse(inString, where + ": unclosed quote in " + q);
    }
}
