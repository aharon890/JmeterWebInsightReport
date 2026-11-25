# Contract: run data format 1.1

Run data 1.0 is defined by spec 001 (`specs/001-report-for-every-load-tool/contracts/run-data-format.md`) and its
schema, `run-data.schema.json`, published beside each release. 1.1 adds optional fields only, under 1.0's rule for a
minor version.

## Added

- Threshold record: `statistic`, `measuredValue`, `metricType`, `metricContains` (data-model.md).
- Run record: `trendStatistics`.

## Rules

- A writer of 1.1 writes `"formatVersion": "1.1"` in the run record. A writer that has no measured value leaves the
  four fields out. It never writes 0 for an unknown value.
- A reader of 1.1 reads 1.0 files unchanged.
- A reader of 1.0 (Web Insight Report 4.5.x) reads a 1.1 file too: it accepts any version with major 1
  (`RunDataFormat.reads`) and ignores fields it does not know. Its k6 tab then shows no measured values, as for a 1.0
  file.
- Both schema copies (`static/schema/run-data.schema.json` in the JAR and `docs/schema/`) stay identical, as
  `schema-published.test.js` holds them. The release attaches the new schema.
