# Server component types — candidates for the catalogue

Status: **reference list for Aaron to choose from, not approved.** Written 2026-10-03. Companion to
`plans/server-metrics-components.md` (C1–C9). First types already decided: **postgres, kafka** (C3).

Each type is a catalogue entry: a fixed list of metrics whose queries are written against a
**published** set of metric names. That set is what has to be studied before a type is added —
column "Published metrics from" names it. From memory of the ecosystem as of mid-2026; every
exporter's metric list must be checked against its docs/source when the type is built.

## How to read the columns

- **Backend** — where `wir-collect` fetches from. **Prometheus** is what the collector speaks today.
  **CloudWatch / Azure Monitor / Google Cloud Monitoring** are other APIs: a type that needs one also
  needs a new collector backend first (v2 work, not a catalogue entry).
- **Names stable?** — **yes**: the exporter defines fixed metric names. **varies**: names come from
  user configuration (JMX exporter rules files, custom SQL), so one catalogue cannot fit every
  installation; such a type needs a stated assumption or variants (`kafka`, `kafka-strimzi`…).
- **Effort** — S: one exporter, fixed names, a handful of metrics. M: two exporters or variants.
  L: needs a new backend or per-installation configuration.

## 1. Databases

| Type | What you would assert | Published metrics from | Backend | Names stable? | Effort |
|---|---|---|---|---|---|
| **postgres** ✅ decided | connections used %, locks waiting, deadlocks, cache hit ratio, replication lag, longest transaction | `postgres_exporter` (prometheus-community) | Prometheus | yes (custom queries file aside) | S |
| **oracle** | sessions used %, wait-class time (User I/O, Concurrency…), tablespace used %, redo/archive activity, blocked sessions | Oracle Database Metrics Exporter (Oracle's own, successor of `iamseth/oracledb_exporter`); default metrics + a TOML file of custom queries | Prometheus | default set yes; custom metrics vary | M |
| **mysql** (and MariaDB) | threads connected/running vs max, slow queries, row-lock waits, InnoDB buffer-pool hit ratio, replica lag | `mysqld_exporter` (prometheus-community) | Prometheus | yes | S |
| **sqlserver** | user connections, batch requests/s, lock waits, deadlocks, page life expectancy, blocked processes | `windows_exporter` mssql collector, or `sql_exporter` (custom SQL) | Prometheus | windows_exporter yes; sql_exporter varies | M |
| **mongodb** | connections current vs available, operation latency, replication lag, queued readers/writers, cache dirty % | `mongodb_exporter` (Percona) | Prometheus | yes, but v1→v2 renamed many | M |
| **redis** | memory used vs max, evictions, connected/blocked clients, keyspace hit ratio, command latency | `redis_exporter` (oliver006) | Prometheus | yes | S |
| **cassandra** | read/write latency p99, pending compactions, dropped messages, heap | JMX exporter (rules vary) or a dedicated Cassandra exporter | Prometheus | varies | M |
| **elasticsearch** / OpenSearch | cluster health, JVM heap, search/index latency, rejected thread-pool tasks, unassigned shards | `elasticsearch_exporter` (prometheus-community); OpenSearch has its own plugin | Prometheus | yes | S–M |
| **clickhouse** | queries running, merges, replication queue, memory | ClickHouse's built-in Prometheus endpoint | Prometheus | yes | S |
| **influxdb** (time-series) | write/query latency, series cardinality, points written/s | InfluxDB's own `/metrics` endpoint (differs between 1.x, 2.x, 3.x) | Prometheus | per major version | M |
| **aws-rds** (Postgres/MySQL on RDS) | CPU, free memory, DB connections, read/write latency, replica lag | Amazon CloudWatch `AWS/RDS` namespace | **CloudWatch** | yes | L (backend) |

TimescaleDB is Postgres: the `postgres` type covers it.

## 2. Messaging and streaming

| Type | What you would assert | Published metrics from | Backend | Names stable? | Effort |
|---|---|---|---|---|---|
| **kafka** ✅ decided | consumer-group lag, under-replicated / offline partitions, active controller, messages in/s, request latency | `kafka_exporter` (danielqsj) for lag + JMX exporter for broker metrics | Prometheus | lag yes; broker metrics **vary** with the JMX rules file | M |
| **kafka-msk** (Kafka on AWS) | the same questions | MSK *Open Monitoring* (JMX exporter + node exporter ports, scraped by your Prometheus) **or** CloudWatch `AWS/Kafka` | Prometheus or **CloudWatch** | Open Monitoring yes (fixed by AWS) | M (Prometheus) / L (CloudWatch) |
| **rabbitmq** | queue depth, unacked messages, consumers, publish/deliver rates, memory/disk alarms | RabbitMQ's built-in Prometheus plugin (`rabbitmq_prometheus`, 3.8+) | Prometheus | yes | S |
| **activemq-artemis** | queue depth, consumers, messages added/acked | JMX exporter, or Artemis's Prometheus metrics plugin | Prometheus | varies | M |
| **ibm-mq** | queue depth, oldest message age, channel status, put/get rates | `mq_prometheus` (IBM's mq-metric-samples) | Prometheus | yes | M |
| **pulsar**, **nats** | backlog, throughput, consumer lag | both expose Prometheus metrics natively | Prometheus | yes | S each |
| **aws-sqs** | visible messages, age of oldest message | CloudWatch `AWS/SQS` | **CloudWatch** | yes | L (backend) |

## 3. Runtimes (the application itself)

| Type | What you would assert | Published metrics from | Backend | Names stable? | Effort |
|---|---|---|---|---|---|
| **jvm** | heap used / after GC, GC pause time, threads, heap growth (slope) | Micrometer (Spring Boot Actuator), Prometheus Java client, or JMX exporter — **three naming schemes** | Prometheus | yes per library, so 3 variants | M |
| **dotnet** | GC heap, gen-2 collections, thread-pool queue, exceptions/s | `prometheus-net` or OpenTelemetry .NET | Prometheus | per library | M |
| **nodejs** | event-loop lag, heap used, active handles | `prom-client` default metrics | Prometheus | yes | S |
| **go**, **python** | goroutines/threads, GC, memory | official Prometheus client libraries' default metrics | Prometheus | yes | S each |
| **http-service** | 5xx rate, server-side latency p95, in-flight requests | the app's own HTTP metrics: Micrometer `http_server_requests_*`, OpenTelemetry `http.server.*` | Prometheus | per framework | M |

`jvm` and `http-service` were in the 2026-08-21 phase-1 list beside Kafka.

## 4. Infrastructure

| Type | What you would assert | Published metrics from | Backend | Names stable? | Effort |
|---|---|---|---|---|---|
| **host-linux** | CPU busy %, memory available %, load per core, disk busy, free space, TCP retransmits | `node_exporter` | Prometheus | yes | S |
| **host-windows** | CPU, memory, disk, network | `windows_exporter` | Prometheus | yes | S |
| **kubernetes-pod** | pod CPU vs limit, CPU throttling, memory vs limit, restarts, OOM kills | cAdvisor (via kubelet) + `kube-state-metrics` | Prometheus | yes | M |
| **container** (Docker, no k8s) | CPU, memory, throttling | cAdvisor | Prometheus | yes | S |

Tutorial 13 already carries starter queries for `host-linux` and `jvm`.

## 5. Web tier and gateways

| Type | What you would assert | Published metrics from | Backend | Names stable? | Effort |
|---|---|---|---|---|---|
| **nginx** | active connections, request rate, 5xx (Plus only) | `nginx-prometheus-exporter` (stub_status for OSS, richer for NGINX Plus) | Prometheus | yes, but OSS exposes very little | S |
| **haproxy** | backend 5xx, queue length, response time, sessions vs limit | HAProxy's built-in Prometheus endpoint (2.0+) | Prometheus | yes | S |
| **envoy** / **istio** | upstream 5xx, request duration, retries, circuit-breaker trips | Envoy's `/stats/prometheus`; Istio standard metrics | Prometheus | yes | M |
| **apache-httpd**, **tomcat** | busy workers, request rate | `apache_exporter`; Tomcat via JMX exporter | Prometheus | httpd yes; Tomcat varies | S / M |

## 6. Coordination, cache, identity

| Type | What you would assert | Published metrics from | Backend | Names stable? | Effort |
|---|---|---|---|---|---|
| **zookeeper** | outstanding requests, latency, sync/ephemeral counts | ZooKeeper's built-in Prometheus metrics provider (3.6+) | Prometheus | yes | S |
| **memcached** | hit ratio, evictions, connections | `memcached_exporter` | Prometheus | yes | S |
| **keycloak** | login failures, request latency | Keycloak's built-in metrics (recent versions) | Prometheus | yes | S |

## 7. Other cloud monitoring APIs (whole backends, not types)

| Backend | Covers | Needed for |
|---|---|---|
| Amazon CloudWatch | RDS, MSK, SQS, ElastiCache, ALB, Lambda | `aws-*` types |
| Azure Monitor | Azure SQL, Service Bus, Event Hubs, App Service | `azure-*` types |
| Google Cloud Monitoring | Cloud SQL, Pub/Sub | `gcp-*` types |
| Datadog / Elastic / Grafana Cloud | anything the user already ships there | users without their own Prometheus |

Each is a new `wir-collect` backend (auth, query language, rate limits, paging) and was already on
the v2 list. The catalogue format should allow a type to name its backend so these slot in later.
An **OpenTelemetry Collector** exporting to Prometheus is the cheap bridge for many of them.

## Suggested order

1. **postgres, kafka** — decided.
2. **host-linux, jvm** — almost every system under test has both. Starter queries already exist in
   Tutorial 13. Also on your 2026-08-21 phase-1 list.
3. **http-service, kubernetes-pod** — the app's own view of the load, and where most teams run it.
4. **oracle, mysql, redis, rabbitmq** — by demand.
5. **kafka-msk** via Open Monitoring (Prometheus): no new backend needed.
6. **CloudWatch backend**, then `aws-rds`, `aws-sqs`, MSK via CloudWatch.

## Questions for Aaron

- **Q1.** Which types beyond postgres and kafka, and in what order? The list above is a menu.
- **Q2.** Types whose metric names **vary** (Kafka broker metrics, JVM, Cassandra, Tomcat): ship one
  assumed variant and state it, or a variant per exporter (`jvm-micrometer`, `jvm-jmx`…)?
- **Q3.** Is Prometheus-only acceptable for v1, with every cloud API (CloudWatch first) left to v2?
