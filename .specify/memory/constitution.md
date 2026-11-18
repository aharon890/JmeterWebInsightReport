# JMeter Web Insight Report Constitution

## Core Principles

### I. Implementation Needs an Explicit Go-Ahead

- Investigation, review, planning and design end by reporting back. Code is written only after an
  explicit instruction to fix, implement or apply.
- A permission mode or an auto-accepted edit is never consent to implement.
- In Spec Kit terms: `/speckit-specify`, `/speckit-clarify`, `/speckit-plan`, `/speckit-tasks`
  and `/speckit-analyze` write only under `specs/`; product code changes only in
  `/speckit-implement`.

Rationale: an implementation nobody asked for costs a review; a question costs one round trip.

### II. The Report Follows JMeter

- Counts and statistics MUST match JMeter's Aggregate Report for the same run. Where JMeter and
  this report could differ, JMeter's own logic decides.
- Numbers and times MUST read the same for every reader, "like in JMeter": `.` for decimals, no
  thousands separator, the Aggregate Report's patterns, times in the zone of the machine that
  built the report.
- New reporting logic MUST NOT be invented where JMeter, Grafana or Prometheus already define the
  behaviour; an open question is settled by what those tools do.

Rationale: users compare this report with the tools they already trust; a number that differs
from JMeter's is a defect, whatever the reasoning behind it.

### III. Tests Are the Gate, Read as Counts

- A change is finished only when sanity and regression pass (the `run-tests` skill). Both runners
  exit with their failure count; the counts are read, not only the exit code.
- A defect is pinned by a test that fails on it before it is fixed. A spec written against a
  defect is handed over red; the product is never adjusted just to turn a spec green.
- Every fixture MUST have an Aggregate Report reference (R07); a fixture without one is a FAIL,
  never a skip.
- Playwright specs generate nothing; fixtures come from the runners and are regenerated when
  their provenance stamps no longer match the tree.

Rationale: every suite that compares the report only with itself has, at least once, agreed with
itself on a wrong number.

### IV. One Self-Contained Artifact

- `jmeter-web-insight-report-*.jar` is the only deployed artifact: a shaded fat JAR. The core JAR
  MUST NOT be deployed beside it.
- Shading is an allowlist. Every bundled third-party library MUST be relocated under
  `com.jmeterwebinsightreport.shaded`; `verify_shaded_jar` fails the build otherwise.
- `jmeter-report-core` classes are not relocated, because listener and exporter class names
  appear in users' JMX files and `reportgenerator.properties`.
- The report is one self-contained HTML file that opens without a server or network access.

Rationale: the JAR lands in JMeter's flat `lib/ext` classpath, where a duplicate class is a
non-deterministic failure on someone else's machine.

### V. Branch-First, One Version, Clean Text

- Work MUST NOT be committed or pushed directly to the primary branch; the hooks refuse it.
  Branch prefixes: `feat/`, `fix/`, `docs/`, `chore/`, `refactor/`.
- The version lives in one place, `<revision>` in the parent `pom.xml`. It MUST NOT be hardcoded
  anywhere else.
- Every text blob is LF in git; `verify_line_endings` enforces it.
- Scripts: `.sh` holds the logic, `.bat` is a thin wrapper.

Rationale: each of these rules replaced a failure that had already happened once.

### VI. Every Setting Reachable by the User

- Every report property MUST be settable per run, following the documented configuration
  precedence.
- Names of properties, statuses and files are long and self-explaining; abbreviations that need a
  legend are not used.
- User-visible behaviour is documented in `docs/` in the same change that introduces it.

Rationale: the plugin is used by testers on runs the authors never see; whatever cannot be set or
understood from the outside does not exist for them.

## Technical Constraints

- Two Maven modules: `jmeter-report-core` (models, parser, statistics, assertions, baseline
  comparison, server asserts) and `jmeter-web-insight-report` (listener, `-g -o` exporter,
  Thymeleaf template, report JS/CSS, the `wir-collect` collector).
- Target: Apache JMeter 5.6.3. Two ingestion paths (live listener and JTL export) feed one
  generator and MUST produce the same report for the same samples.
- Percentiles are exact counts; no approximating sketch.
- CI runs the fast tier only (`mvn verify`, `report.js` unit tests, static checks). The fixture
  and browser tiers run locally before a push; no required status check may depend on them.

## Development Workflow

1. `/speckit-specify` describes what is wanted and why, in `specs/<number>-<name>/`;
   `/speckit-clarify` when anything is open.
2. `/speckit-plan`, checked against this constitution, then `/speckit-tasks`. These end by
   reporting back (Principle I).
3. `/speckit-implement` on a feature branch only after the go-ahead; then `/speckit-converge`.
4. Finish with the project's existing `dev-cycle` skill: version bump, build and deploy to
   `lib/ext`, refresh the report example when output changed, run the tests, commit.

Spec Kit's `specs/` holds the specification, plan and tasks of a feature. The `plans/` folder
stays what it is: untracked-by-process working notes. A feature has one home, `specs/`; a plan
file is not created in `plans/` for work that has a spec.

## Governance

- This constitution takes precedence over other working habits in this repository. `CLAUDE.md`
  holds the day-to-day guidance and the reasoning behind each rule, and MUST stay consistent
  with it.
- Amendments are made through `/speckit-constitution`, on a branch, with the owner's approval.
- Versioning follows semantic versioning: MAJOR for a removed or redefined principle, MINOR for a
  new principle or section, PATCH for wording.
- Every plan MUST include a constitution check; a deviation is written down with its reason in
  the plan.

**Version**: 1.0.0 | **Ratified**: 2026-11-19 | **Last Amended**: 2026-11-19
