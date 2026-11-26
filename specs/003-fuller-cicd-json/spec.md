# Feature Specification: The CI/CD JSON carries the Aggregate Report's columns and the whole-run row

**Feature Branch**: `feat/003-fuller-cicd-json`

**Created**: 2026-11-26

**Status**: Approved by the owner on 2026-11-26, as stage 1 of perf hub's spec 011 ("Approve, start building").

**Input**: the owner's decision 3 of 2026-11-26 in perf hub's review of its k6 support: Compare, Benchmarking and
History take every run's numbers from its Web Insight Report, for JMeter and k6 alike. The owner asked for this plugin
change by that choice. The design, its research and the field contract are perf hub's spec 011
(`perf-hub/specs/011-cross-tool-compare-and-verdict/`, `contracts/wir-cicd-json-additions.md`).

## Why

A consumer of `web-insight-report.json` gets each request's samples, mean, p95, p99, error rate, throughput and Apdex,
and the whole run's sample and error counts. JMeter's Aggregate Report, which the report's Requests table follows, also
has min, median, Line 90 and max, and a TOTAL row with every column. The report already computes all of them; only
the file leaves them out, so a consumer cannot show the report's own table.

## User Story 1 - A pipeline reads the whole Aggregate Report table from the file (Priority: P1)

**Independent Test**: build any report; each `samplers[]` entry carries `errorCount`, `min`, `median`, `p90` and `max`
equal to its Requests table row, and a top-level `total` equals the Σ Total row.

**Acceptance Scenarios**:

1. **Given** any run, **When** its JSON is written, **Then** each `samplers[]` entry ends with `errorCount`, `min`,
   `median`, `p90`, `max`, after every key 4.6.1 wrote, in that order.
2. **Given** any run, **Then** a top-level `total` follows `samplers` with `sampleCount`, `errorCount`, `errorRate`,
   `mean`, `min`, `median`, `p90`, `p95`, `p99`, `max`, `throughput`, the page's Σ Total row; its `sampleCount`,
   `errorCount` and `errorRate` equal `totalSamples`, `totalErrors` and `errorRate`.
3. **Given** a k6 run (`build-report --from k6`), **Then** the same keys; a value the tool does not measure is `null`.
4. **Given** a 4.6.1 consumer, **Then** every key it read keeps its value and place.

## Requirements

- **FR-001**: The additions above, written from the same model values as the HTML (no new calculation).
- **FR-002**: Every pre-existing key keeps its value and position (`GateOutputTest`).
- **FR-003**: The goldens (R06) and the run-data fixtures (R08) are re-accepted in the same change; R07 is unchanged.
- **FR-004**: `docs/Tutorial/11-Export-Integration.md` and `docs/FEATURES.md` say what 4.7.0 adds.

## Success Criteria

- **SC-001**: `CiCdJsonSerializerTest` holds the new keys, their order and the `total` row; the goldens' diff shows only
  added keys.
- **SC-002**: The full regression and the release tier pass; released as 4.7.0.
