# Server Asserts for Common Components

The metric catalogue: each component type, its metrics, the queries behind them and what each tool
calls them. The [Server tab](13-Server-Tab.md) says how asserts are judged and shown;
[Getting Server Data In](14-Server-Data.md) how to add the data.


## The catalogue

Twelve component types are built in. A catalogue metric carries what it means (its label and a
sentence for the picker), its unit, its reducer, how instances fold, which way is worse, its
Prometheus query (with the component's `match` slotted in) and each tool's name for it. Most fold
with `max`, the instance that is worst when higher is worse. Where lower is worse — an `up` flag, a
hit ratio, free memory or disk, a credit balance — they fold with `min`, so one database that went
down fails `up`'s `fail < 1` however healthy its twin was. The bands are still yours: the right
number depends on the hardware, the workload and what "healthy" means for that service. A safe
default ships only where a healthy machine meets it whatever it runs. For example: `up` fails below
1 on every type that has it, and so do Kafka's active controller, Redis's replication link and EBS
burst balance; a fail on any deadlock, OOM kill, panic, failed status check or compaction,
under-replicated, under-min-ISR or offline partition, unclean leader election, container restart,
or connection Redis or MySQL refused at its limit; a warn on swapping, on ISR shrinks, on failed
produce requests, on rejected writes and object-store errors, on an EC2 instance that hit its EBS
limit or a network allowance, and on any server 5xx. How many 5xx should fail the run is your
service's error budget, so `http-service` ships no fail band for them. An InfluxDB server's own write
and query error share is different: it warns above 0% and fails above 1%. Every safe default is in
the catalogue files linked below. NIC errors and drops have none: the kernel also counts a
frame of an unknown protocol as dropped, so a healthy host's rate need not be 0. Write a generous
first guess, collect, then drag.

| `type` | What it is for | What to run | Recommended metrics |
|---|---|---|---|
| `postgres` | A PostgreSQL server: connections, transactions, locks, cache, replication, checkpoints | postgres_exporter (prometheus-community), default collectors; on PostgreSQL 17 and later `checkpoints.requested` also needs its `stat_checkpointer` collector, which is off by default | `up`, `connections.used.pct`, `transaction.longest.seconds`, `locks.waiting`, `deadlocks`, `cache.hit.pct` |
| `kafka` | A Kafka cluster: consumer lag and groups, brokers, partitions and ISR, controller, request latency, broker threads, throughput | kafka_exporter for lag, consumer groups, brokers and partitions; the JMX exporter with Strimzi's rules for broker internals. The request latency p99s, `request.handler.idle` and `consumer.lag.seconds.max` have no Prometheus query: collect them from an export of a tool the catalogue names for each (Datadog names all four) | `consumer.lag.max`, `consumer.lag.final`, `brokers`, `partitions.under_replicated`, `partitions.under_min_isr`, `partitions.offline`, `isr.shrinks`, `elections.unclean`, `requests.produce.failed` |
| `redis` | A Redis node: memory against maxmemory, evictions, hit ratio, connections, main-thread CPU, replication | redis_exporter (oliver006) | `up`, `memory.used.pct`, `keys.evicted`, `connections.rejected`, `keyspace.hit.pct`, `command.latency.avg`, `cpu.main.pct`, `slowlog.entries` |
| `host-linux` | A Linux machine or VM, seen from inside: CPU, steal, memory, swap, OOM kills, disk, filesystems, network | node_exporter, default collectors | `cpu.busy.pct`, `cpu.steal.pct`, `load.per.core`, `memory.available.pct`, `swap.pages.rate`, `oom.kills`, `disk.await.ms`, `filesystem.free.pct`, `network.errors.rate`, `tcp.retransmit.pct` |
| `aws-ec2` | An EC2 instance, seen from outside: the ceilings the guest cannot see — CPU credits, EBS burst, IOPS, throughput and limits, ENA network allowances, status checks | an export from CloudWatch's `AWS/EC2` and `AWS/EBS` (`--csv --from cloudwatch`); it has no Prometheus query. The ENA allowances also need the CloudWatch agent's `ethtool` section. Four KPIs are computed with metric math ([below](#aws-ec2-four-kpis-computed-with-metric-math)) | `cpu.credit.balance`, `ebs.burst.balance.pct`, `ebs.limit.exceeded`, `status.check.failed`, `network.allowance.exceeded` |
| `oracle` | An Oracle database: sessions and processes against their limits, CPU, wait classes, locks, tablespaces | the Oracle Database Metrics Exporter 2.x (`oracle/oracle-db-appdev-monitoring`), default metrics, plus [the custom metrics file](#oracle-the-custom-metrics-file) for five metrics | `up`, `sessions.used.pct`, `processes.used.pct`, `sessions.active.per.cpu`, `sessions.blocked`, `wait.concurrency.sessions`, `db.cpu.pct`, `deadlocks` |
| `influxdb2` | An InfluxDB 2 OSS server: write and query latency and errors, rejected points, series cardinality, compactions | nothing to install: InfluxDB 2 serves its own `/metrics`; scrape it with Prometheus | `up`, `write.latency.p95`, `write.errors.pct`, `write.rejected`, `query.latency.p95`, `query.errors.pct`, `memory.used.bytes`, `series.count`, `compactions.failed` |
| `influxdb3` | An InfluxDB 3 server: the same ids as `influxdb2` where the two engines share a KPI, plus query permits, query OOMs, the object store and panics | nothing to install: InfluxDB 3 serves its own `/metrics`, with a token unless the server runs `--disable-authz metrics`. Names were read in InfluxDB 3 Core's source; Enterprise is closed source and unverified | `up`, `write.latency.p95`, `write.errors.pct`, `write.rejected`, `query.latency.p95`, `query.errors.pct`, `memory.used.bytes`, `query.permit.wait.p95`, `query.ooms`, `panics` |
| `jvm` | A Java service's JVM: heap against its maximum, live heap growth (a leak's signature), GC pauses, threads, process CPU | Micrometer's Prometheus registry, as Spring Boot Actuator serves it at `/actuator/prometheus`. Another library names these otherwise: [write a custom query](#a-jvm-on-another-library-and-a-host-metric-the-type-lacks-custom-queries) | `heap.used.pct`, `heap.live.growth`, `gc.pause.max`, `gc.pause.time.pct`, `cpu.process.pct` |
| `http-service` | The service's own view of the requests JMeter sends: rate, 5xx share, mean, slowest and p95 response time, measured inside the application | Micrometer's `http.server.requests` timer, as Spring Boot Actuator records it for Spring MVC and WebFlux. `latency.p95` also needs the request histogram switched on (below) | `errors.5xx.pct`, `latency.mean`, `latency.max` |
| `kubernetes-pod` | The containers of a pod against their CPU and memory limits: CPU throttling, working set, restarts, OOM kills | cAdvisor, as the kubelet serves it, and kube-state-metrics. Give the component a `match` on `namespace`, `pod` or `container` (below) | `cpu.limit.used.pct`, `cpu.throttled.pct`, `memory.limit.used.pct`, `restarts` |
| `mysql` | A MySQL or MariaDB server: connections against `max_connections`, threads running, row lock waits, slow queries, the InnoDB buffer pool, replica lag | mysqld_exporter (prometheus-community), default collectors | `up`, `connections.used.pct`, `connections.refused`, `threads.running`, `row.lock.waits` |

Every metric of each type, with its label, description, unit and query, is in the plugin's
[`catalogue/` resources](https://github.com/aharon890/JmeterWebInsightReport/tree/main/jmeter-report-core/src/main/resources/com/jmeterwebinsightreport/core/servermetrics/catalogue) (`index.json`, `postgres.json`, `kafka.json`, `redis.json`,
`host-linux.json`, `aws-ec2.json`, `oracle.json`, `influxdb2.json`, `influxdb3.json`, `jvm.json`,
`http-service.json`, `kubernetes-pod.json`, `mysql.json`), and the metric picker lists them; the
report page carries the same files. Exporter metric names differ between
exporter versions and JMX rule sets. Where yours differ, write a `query` instead of a `metric`.

An EC2 instance is two components: a `host-linux` one for the guest (`match` on the node's
`instance`) and an `aws-ec2` one for what AWS sees, keyed by its `InstanceId` (and its volumes'
`VolumeId`). They have different identities and different resolutions: CloudWatch's basic
monitoring is 5-minute data and detailed monitoring 1-minute, and CPU credit metrics are 5-minute
whatever you enable, so set `--step` to the export's period. `AWS/EC2 CPUUtilization` is not the
guest's CPU busy (it is the hypervisor's view); `host-linux` names the CloudWatch agent's
`cpu_usage_active` instead.

A metric with no Prometheus query, as every `aws-ec2` one is, has no query to send: its series is
known as `<type>:<metric>`, with the component's `match` in braces when it has one, and only a file
collected with `--csv` can answer it. Collected live or from `--recording`, an assert on it is
SOURCE_FAILED with "has no prometheus query in the catalogue - export it to a file and collect it
with --csv". Four `kafka` metrics are such metrics too, because the exporters the type assumes
cannot answer them: Strimzi's JMX rules leave request metrics out, so `request.produce.latency.p99`
and `request.fetch_consumer.latency.p99` have no series; they export the request handlers' idle share
as an average since the broker started, which barely moves in a test, so `request.handler.idle` is
read from Datadog's one-minute rate; and kafka_exporter has no lag in seconds, so
`consumer.lag.seconds.max` is CloudWatch's or Datadog's estimate. With JMX rules of your own, write
a custom query for them instead.

Kafka on KRaft with dedicated controller nodes reports `controller.active`, `partitions.offline`
and `elections.unclean` from the controllers, not the brokers: scrape them too. Scraped from the
brokers alone, those three find no series, and their asserts are NOT_JUDGED (no match): skipped,
not failed, and not a configuration error, so the run says nothing louder than the skip.

#### aws-ec2: four KPIs computed with metric math

Four `aws-ec2` KPIs are each two to five CloudWatch metrics, with no single one to name, so the
catalogue's CloudWatch name for them is `null`. Compute each in CloudWatch with metric math (a graph's
math expression, or `GetMetricData`), as its description in the picker says, and export the result
under the catalogue's own name — the `metric` column reads `ebs.iops`, not a CloudWatch name — which
`--csv` reads with `--from cloudwatch` or without it, as it is: no conversion is applied to it.
[From CloudWatch](16-Server-Reference.md#from-cloudwatch) computes `ebs.iops` in a `get-metric-data` request beside two
plain metrics and writes the CSV.

| KPI | CloudWatch metrics | Metric math |
|---|---|---|
| `ebs.iops` (ops/s) | `AWS/EBS` `VolumeReadOps` and `VolumeWriteOps`, statistic Sum, per `VolumeId`; or `AWS/EC2` `EBSReadOps` and `EBSWriteOps` for the instance's total on a Nitro instance that is not bare metal, at a period of at least 300 s under basic monitoring: they are 5-minute data there, and at a 60 s period each datapoint is still five minutes' sum, which `PERIOD(m1)` divides by 60 — five times the rate | `(m1 + m2) / PERIOD(m1)` |
| `ebs.throughput.rate` (B/s) | `AWS/EBS` `VolumeReadBytes` and `VolumeWriteBytes`, statistic Sum, per `VolumeId`; or `AWS/EC2` `EBSReadBytes` and `EBSWriteBytes` for the instance's total on a Nitro instance that is not bare metal, at a period of at least 300 s under basic monitoring, for the same reason | `(m1 + m2) / PERIOD(m1)` |
| `ebs.limit.exceeded` (0/1) | `AWS/EC2` `InstanceEBSIOPSExceededCheck` and `InstanceEBSThroughputExceededCheck` (per `InstanceId`) and `AWS/EBS` `VolumeIOPSExceededCheck` and `VolumeThroughputExceededCheck` for every volume attached to the instance, statistic Maximum: the volume checks carry `VolumeId`, so each volume is its own two series, and a root and a data volume make six series with the instance's two. Nitro instances only (the instance checks not on bare metal). `AWS/EBS` publishes neither volume check for a magnetic (`standard`) or Multi-Attach enabled volume, so such a volume's limit goes unseen | `MAX(METRICS())` on a graph holding only those, every volume's two and the instance's two: the largest at each point, so it stays 0 or 1, never their sum |
| `network.allowance.exceeded` (/min) | `CWAgent` `ethtool_bw_in_allowance_exceeded`, `ethtool_bw_out_allowance_exceeded`, `ethtool_pps_allowance_exceeded`, `ethtool_conntrack_allowance_exceeded` and `ethtool_linklocal_allowance_exceeded`, statistic Maximum, for every network interface of the instance: the ENA driver counts on each interface since its last reset, so the agent publishes one series per interface, with dimensions `interface` and `driver`, plus the agent's hostname unless its `omit_hostname` is true, or in its place what its `append_dimensions` adds (`InstanceId` in AWS's example). On Linux, the agent publishes them only when its `ethtool` section lists the five in `metrics_include`, as custom metrics (on Windows they are the `ENA Packets Shaping` counters, under other names) | `60 * (SUM(RATE(METRICS())) + SUM(ABS(RATE(METRICS())))) / 2` on a graph holding only those, every interface's five: the counters are cumulative, so a rate per second for each series, clamped at 0, summed, times 60. A driver reset starts a counter again, and `RATE` across it is negative: summed as it is, it would cancel a real exceed on another series. `(r + ABS(r)) / 2` is `r`, or 0 where `r` is negative, and it is taken over the sums because metric math adds no two arrays and `IF` takes none. A series reads 0 in the period of its reset, not what it counted since |

`ebs.limit.exceeded` and `network.allowance.exceeded` are recommended and ship a warn above 0: when
either fires, the test hit an AWS ceiling, not a limit of the system under test. `ebs.iops` and
`ebs.throughput.rate` have no band: compare them with what the volume or the instance is provisioned
for. On Nitro instances `AWS/EBS` also publishes `VolumeAvgIOPS` (ops/s) and `VolumeAvgThroughput`
(KiB/s), a volume's average over each minute; exported under `ebs.iops`, and times 1024 under
`ebs.throughput.rate`, they answer the same KPIs there. The table gives the form every instance
publishes.

#### Oracle: the custom metrics file

The exporter's default metrics have no blocked-session count, no CPU, hard-parse or deadlock
statistic and no log switches, so five `oracle` metrics — `sessions.blocked`, `db.cpu.pct`,
`log.switches.per.min`, `parses.hard.rate` and `deadlocks` — read series this file adds. Save it
as `oracle-custom-metrics.toml`, the name the config below lists, beside the exporter:

```toml
# Custom metrics the oracle catalogue type reads.
# Series: oracledb_blocked_sessions_value, oracledb_sysstat_cpu_used_by_this_session,
# oracledb_sysstat_parse_count_hard, oracledb_sysstat_enqueue_deadlocks and
# oracledb_log_switches_last_minute.

[[metric]]
context = "blocked_sessions"
metricsdesc = { value = "Sessions waiting on a row or table lock another session holds." }
request = '''
select count(*) as value
from gv$session
where blocking_session_status = 'VALID'
  and wait_class = 'Application'
'''

[[metric]]
context = "sysstat"
labels = [ "inst_id" ]
metricsdesc = { value = "Cumulative statistic from gv$sysstat." }
metricstype = { value = "counter" }
fieldtoappend = "name"
request = '''
select inst_id, name, value
from gv$sysstat
where name in ('CPU used by this session', 'parse count (hard)', 'enqueue deadlocks')
'''

[[metric]]
context = "log_switches"
metricsdesc = { last_minute = "Redo log switches in the last minute." }
request = '''
select count(*) as last_minute
from (
  select thread#, sequence# from v$log_history
  where first_time > sysdate - 1/1440
  union
  select thread#, sequence# from v$log
  where status = 'CURRENT' and first_time > sysdate - 1/1440
)
'''
```

and list it under `metrics.custom` in the exporter's config file:

```yaml
metrics:
  custom:
    - oracle-custom-metrics.toml
```

The exporter names a series `oracledb_<context>_<field>`; with `fieldtoappend` the field is the
row's value instead, lower case, spaces turned to `_` and brackets dropped, so `parse count (hard)`
becomes `oracledb_sysstat_parse_count_hard`. The `sysstat` series are typed counters (`CPU used by
this session` is in hundredths of a second, which the `db.cpu.pct` query turns into a percentage of
`cpu_count`). The blocked-session count reads `blocking_session_status = 'VALID'`, the one state in
which Oracle's reference says `blocking_session` names a holder, and only in the Application wait
class, which Oracle defines as row-level and explicit lock waits: a session waiting on a commit
names LGWR as its blocker too, and without the class the count would climb with the commit load.
It returns one row even when it is 0, so a healthy run has a series rather than a gap. The log
history holds a row only for a log already switched out, so the log the latest switch opened is
counted from `v$log`, where it is `CURRENT`; without it a minute with one switch reads 0. Both
views are `v$`, not `gv$`: they come from the control file every instance shares. Keep the
contexts as written: the catalogue's queries name these series exactly, and a renamed context
leaves its metric with no data.

Three more Oracle points. The exporter (2.x) types `oracledb_wait_time_<class>` as a counter of
seconds, which the wait-class queries rate; the older `iamseth/oracledb_exporter` used the same
names for a gauge, and these queries misread it. It reports a `TEMPORARY` tablespace's
`used_percent` as a 0–1 fraction beside 0–100 for the rest, so `tablespace.used.pct` leaves TEMP out.
And `db.cpu.pct` needs exporter 2.0.4 or later: before it the default `db_system` metric carries no
`inst_id`, so the query's division by `cpu_count` matches nothing.

#### InfluxDB 2 and 3

`influxdb2` and `influxdb3` share the ids of the ten KPIs both engines have (`write.latency.p95`,
`write.rejected`, `query.errors.pct` and the rest), so moving a file from one version to the other
changes only the component's `type`; each adds four of its own. Their queries differ, because the
engines' metrics do: `write.errors.pct` counts `status="5XX"` on `http_api_requests_total` in 2.x
and `status="server_error"` on `http_requests_total` in 3.x, while 3.x's `query.errors.pct` reads
the failed phase of `influxdb_iox_query_log_phase_entered_total`. Two of their series are not InfluxDB's alone:
`up` is Prometheus' own series for every target, and 2.x's `memory.used.bytes` reads
`go_memstats_heap_inuse_bytes`, which every Go program exports. Give the component a `match` that
picks InfluxDB's (`job="influxdb"`, say): without one, `up`'s safe default fails when any other
target is down, and the memory reads the largest Go heap Prometheus scrapes. Both versions'
duration histograms stop at 10 s, so a p95 reads at most 10 and a band above it can never fire.

#### JVM and HTTP service: Micrometer names

`jvm` and `http-service` read the names Micrometer's Prometheus registry writes, which is what
Spring Boot Actuator serves at `/actuator/prometheus` once `micrometer-registry-prometheus` is on the
classpath and the endpoint is exposed (`management.endpoints.web.exposure.include=prometheus`). A
gauge's name ends in its base unit and a timer's in `_seconds`, with `_count`, `_sum` and `_max`:
`jvm_memory_used_bytes`, `jvm_gc_pause_seconds_max`, `http_server_requests_seconds_count`. A service
on another library, on Spring Boot with OpenTelemetry meter conventions, or with the request timer
renamed (`management.observations.http.server.requests.name`) names these otherwise: declare a
`custom` component and write the queries (see
[below](#a-jvm-on-another-library-and-a-host-metric-the-type-lacks-custom-queries)).

- **`latency.p95` needs the request histogram**, which Spring Boot leaves off. Without
  `management.metrics.distribution.percentiles-histogram.http.server.requests=true` there is no
  `http_server_requests_seconds_bucket` series and the assert is NOT_JUDGED (no match). The
  histogram's top bucket is `maximum-expected-value`, 30 s unless the service sets it lower or
  higher, and the p95 never reads past it: a p95 at that bound means at least that (10000 ms under a
  10 s maximum is 10 s or more). Below `minimum-expected-value` it reads a fraction of the first
  bucket, so 0.95 ms means under about 1 ms. The report sees only the computed p95, never the
  buckets, so it cannot mark either end.
- **`latency.max` and `gc.pause.max` are Micrometer's `_max`**, the largest value in a window that
  decays over 2 minutes: every scrape sees the slowest request, or the longest pause, since about two
  minutes before it, so the run's first scrapes can carry one from before the run.
- **A series starts the first time its tags occur.** Micrometer creates a timer for a tag set the
  first time it records one: a 5xx status the service had not returned since it started, or a GC
  cause the JVM had not had. `rate()` takes the new series' first count as its baseline, so those
  first 5xx, or that first pause, are not counted. `errors.5xx.pct` reads 0, not nothing, on a
  service that has returned no 5xx at all.
- **`heap.used.pct` divides by the heap's maximum.** Its peak includes a full young generation, so a
  healthy JVM under load peaks high: judge it beside `heap.live.growth`, the leak signal, or against
  a baseline run. Generational ZGC (the only ZGC from JDK 24) and generational Shenandoah give their
  young pool the whole heap's maximum as well as their old pool; the query leaves those young pools
  out of the maximum, or a full heap would read half full.
- **`heap.live.growth` moves only after a collection that frees old-generation memory.** Micrometer
  sets the live data size after a major collection, or one that shrank the old generation, and holds
  it at 0 before the first; the query leaves the 0 out. Until the JVM's first such collection it has
  no data (NOT_JUDGED, no match), and a run with none reads flat: judge a leak on a soak.
- Prometheus' own scrapes of `/actuator/prometheus` are requests too, a few per minute beside the
  load.
- **A service's own `errors.5xx.pct` misses what fails in front of it.** Requests to a pod that was
  killed or is restarting fail at the gateway or the load balancer, and the service counts none. To
  see them, assert `errors.5xx.pct` and `latency.p95` on the component in front of it: a Spring Cloud
  Gateway is an `http-service` too, and records every exchange it proxies in the same timer.
- In-flight requests are not in the catalogue: whether Spring Boot publishes
  `http.server.requests.active` depends on the Spring Boot and Micrometer versions.

#### Kubernetes pods: match on namespace, pod or container

Every `kubernetes-pod` query is per container, and two of them divide a cAdvisor series (CPU
used, the working set) by a kube-state-metrics one (the container's limit), joined on `namespace`,
`pod` and `container`. `cpu.throttled.pct` is a ratio of two cAdvisor counters, throttled CFS
periods over all of them. Those are the labels both exporters carry, so the
component's `match` should use them: `namespace="shop"`, or `namespace="shop", pod=~"api-.*"`. A
match on `job` or `instance` names one exporter's scrape only and leaves the other side of the
division empty. Without a `match` every container in the cluster is judged, `kube-system`'s
included, and `restarts`' safe default fails the run on a restart anywhere. A container with no CPU
or memory limit has no limit series, so its percentages have no data.

`restarts` fails on any restart during the run. `restarts.oom` counts only the restarts whose last
termination was an out-of-memory kill: it reads kube-state-metrics'
`kube_pod_container_status_last_terminated_reason`, which the project marks experimental, and
which keeps that reason until the container terminates again, so the query counts a minute's
restarts only while the reason is `OOMKilled`. kube-state-metrics recommends kube-scheduler's
`kube_pod_resource_limit` over its own container limits when pods declare pod-level resources; a
custom query can read it.

#### MySQL and MariaDB

`mysql` reads mysqld_exporter's default collectors, which read MariaDB the same way: every status
variable the queries use has the same name there. The replica's lag is the column
`SHOW REPLICA STATUS` calls `Seconds_Behind_Source` and `SHOW SLAVE STATUS` `Seconds_Behind_Master`
(MySQL 8.0.22 to 8.3 keep the old names in `SHOW SLAVE STATUS`, and MariaDB keeps them in both). The
exporter asks `SHOW SLAVE STATUS` first and names the series after the column, so MySQL 8.4, which
removed that statement, reports `Seconds_Behind_Source` and older MySQL and MariaDB
`Seconds_Behind_Master`; the query reads either. A replica whose replication is stopped reports no lag at all rather than a large one.
`connections.used.pct` can read just past 100: MySQL lets one more connection in for an
administrator, and more on its administrative interface.

#### Which name each tool uses

Every tool names a KPI its own way, and the catalogue holds each tool's name for each metric — its
`names` block, per tool (`telegraf`, `datadog`, `otel`, `cloudwatch`): the `metric` to look for, the
`kind` of number the tool hands over (`gauge`, `counter`, `rate` or `sum-per-period`), an optional
`scale` (100 for a fraction the catalogue reads as %, 60 for a rate it reads per minute) and an
optional `note`. `--csv FILE --from TOOL` reads the file by those names and converts each value
(see `--from` under [Where the metrics come from](16-Server-Reference.md#where-the-metrics-come-from)). A name is written
the way that tool's
export writes it:

| Tool | Name | Example |
|---|---|---|
| `telegraf` | `<measurement>.<field>`, exactly what `--csv-columns metric=_measurement+_field` joins from an InfluxDB export | `postgresql.deadlocks`, `cpu.usage_steal` |
| `datadog` | the metric's name | `postgresql.percent_usage_connections` |
| `otel` | the metric's name, not the one an export to Prometheus rewrites it to | `postgresql.deadlocks` |
| `cloudwatch` | `<Namespace>:<MetricName>` | `AWS/EC2:CPUCreditBalance`, `CWAgent:mem_available_percent` |

The `note` says which of the tool's series to export when the name alone is not enough: a tag
filter (`tag cpu=cpu-total`), a fold over the tool's own tags (`one series per db tag: export the
sum per server`), a CloudWatch statistic and dimension (`statistic Minimum, per InstanceId`), or an
input that is off by default. A `null` means the tool has no single metric for the KPI — it is a
ratio, a difference or a sum of the tool's metrics, or the tool has nothing at all: compute the
series in the tool (a formula, a metric-math expression) and export it under the catalogue's own
name (`connections.used.pct`), which `--from` reads as it is. A metric with no Prometheus query and
no tool's single metric — `aws-ec2`'s [four metric-math KPIs](#aws-ec2-four-kpis-computed-with-metric-math)
— ends its description by saying how: `No single CloudWatch metric: <what to combine, and how>, and
export the result as <name>.` A tool the catalogue says nothing
about for a metric has a name that was not verified, or one another metric of the type already reads
as a different series (a file holds one series per name: Datadog's per-partition
`kafka.consumer_lag` is `consumer.lag.max`'s), or is outside the type's scope (`aws-ec2` names
CloudWatch only); the same rule applies. `oracle`, `influxdb2` and `influxdb3` name no Telegraf
metric: Telegraf has no Oracle input (`inputs.sql` runs the query you write, under your names), and
reads InfluxDB 2 and 3 only through `inputs.prometheus`, whose names depend on its
`metric_version`. The InfluxDB types name no Datadog, OpenTelemetry or CloudWatch metric either:
Datadog has no InfluxDB check of its own, and neither OpenTelemetry's names through its Prometheus
receiver nor Timestream for InfluxDB's CloudWatch namespace could be verified. `http-service` names
no tool's metric: Datadog's APM metrics carry the span name (`trace.<SPAN_NAME>.hits`), which
depends on the integration, and OpenTelemetry's `http.server.request.duration` is a histogram that
the rate, the 5xx share, the mean and the p95 are each computed from. `jvm` names no Telegraf
metric (its `jolokia2` input names fields as its config says), and `mysql`'s Telegraf names hold
with `metric_version = 2`.

**`match`** narrows a component to its own instances. A catalogue query has a `${match}` slot where
it has no selector of its own and a `${andMatch}` slot inside one it already has, so
`"match": "instance=\"orders-db:5432\""` turns `pg_up${match}` into `pg_up{instance="orders-db:5432"}`
and `{state="active"${andMatch}}` into `{state="active",instance="orders-db:5432"}`. Without a
`match` both expand to nothing. Two components of one type with no `match` resolve to the same
query, and the payload still keeps their series apart by component.

## A JVM on another library, and a host metric the type lacks: custom queries

The `jvm` type reads Micrometer's names. A JVM instrumented otherwise is a `custom` component whose
asserts write their own queries; the ones below are starting points. Check each in Prometheus first.
A host metric `host-linux` does not offer (free space on one mount, say) is a custom assert the same
way: `node_filesystem_avail_bytes{mountpoint="/"}` with `slope` (bytes per minute) and an `lt` band.

**A JVM on the Prometheus Java client (`client_java` 1.x)**

Its heap gauges have Micrometer's names; its GC time is a summary, `jvm_gc_collection_seconds`, with
no maximum, and its thread count and CPU are named otherwise.

| Measures | Query | Reduce | Band direction |
|---|---|---|---|
| Heap used, % of max | `100 * sum by (instance) (jvm_memory_used_bytes{area="heap"}) / sum by (instance) (jvm_memory_max_bytes{area="heap"} > 0)` | `max` | `gt` |
| Time in GC, % | `100 * sum by (instance) (rate(jvm_gc_collection_seconds_sum[1m]))` | `p95` (sustained, not a single spike) | `gt` |
| Live threads | `max by (instance) (jvm_threads_current)` | `max` | `gt` |
| Process CPU, cores | `sum by (instance) (rate(process_cpu_seconds_total[1m]))` | `p95` | `gt` |

A `rate(…[1m])` window must cover at least two scrapes. With a 30 s scrape interval, `[1m]` is the
shortest that works.

To aim a query at one environment's hosts, put the selector in an environment variable:
`node_load1{cluster="${env.cluster}"}` (see [Environments](16-Server-Reference.md#environments-and-interpolation)).

---
