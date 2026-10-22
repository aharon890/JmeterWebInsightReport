---
name: _perf_release
description: Cut a release — bump the version, run every release tier, merge through a PR, tag, and publish a GitHub release with the shaded JAR, the wir-collect scripts and the annotations schema attached. Use when asked to release, cut a release, publish a version, or ship a build.
---

# Cutting a release

```
/_perf_release          detect the level from what has been merged since the last tag
/_perf_release patch    force   1.2.0 -> 1.2.1
/_perf_release minor    force   1.2.0 -> 1.3.0
/_perf_release major    force   1.2.0 -> 2.0.0
```

**There is no patch default.** Bare `/_perf_release` reads the history and decides —
see step 2. A blind patch default would have cut the 39-merge v1.2.0 as v1.1.1.

Runs to completion without stopping: bumps, tests, merges, tags, **publishes live**.
There is no confirmation step, so the only window to intervene is during the ~20
minutes of tests — which is why step 2 prints the derived level and the evidence
behind it *before* that window opens rather than after.

Stop on the **first** failing tier. Never release around a red suite, and never
"fix it in the release notes".

## The steps

### 1. Preflight — abort if any of these is false

- Working tree clean (`git status --porcelain`, ignoring untracked).
- On `main`, and `main` equals `origin/main` after a `git fetch`.
- `gh auth status` succeeds.
- `git config user.email` is the GitHub **noreply** address. With the yahoo address
  the push is rejected at the very end with `GH007: Your push would publish a private
  email address` — after the 20 minutes of tests have already been spent.

### 2. Read the current version and **derive** the bump level from the history

The version lives in **one** place: `<revision>` in the **parent** `pom.xml`. Module
poms use `${revision}` and must never be edited.

An explicit argument always wins. With no argument, decide from what has actually
been merged since the previous tag — never from a fixed default.

```bash
LAST=$(git describe --tags --abbrev=0 2>/dev/null)   # empty on a first release
RANGE=${LAST:+$LAST..}main
git log --format=%s  --merges    $RANGE     # merge subjects -> branch names
git log --format=%s  --no-merges $RANGE     # conventional-commit prefixes
git log --format=%B              $RANGE     # bodies -> breaking markers
```

Extract the source branch from each merge subject — all three shapes occur in this
repo:

```
Merge pull request #49 from aharon890/feature/error-details-grouped-view
Merge branch 'fix/frontend-misc'
Merge fix/docker-and-a11y (#36)
```

Then apply, in order:

| Signal | Level |
|---|---|
| `BREAKING CHANGE` in any body, or a `type!:` subject | **major** |
| any `feature/*` or `feat/*` branch, or any `feat:` commit | **minor** |
| otherwise (only `fix/*`, `chore/*`, `docs/*`) | **patch** |

**Never infer major from branch names** — only an explicit breaking marker or an
explicit `major` argument produces one. Silently doubling the major version is worse
than under-calling a release.

Validated against real history: `v1.1.0..v1.2.0` yields **minor** off
`feature/integer-ms-formatting`, `feature/error-details-grouped-view`,
`feature/column-resize` and `feat/cross-platform-scripts` — the same call a human made
by hand.

Print before the tests start: current → new version, the level, **and the evidence**
("4 feature branches since v1.1.0 → minor"), plus the merge count.

**Known blind spot.** The signal lives in branch names and commit prefixes, so a
*squash-merged* release carries none. `v1.1.0` was one squashed commit titled "Fix 26
audit findings" and reads as patch despite having been a minor bump. If the range
collapses to a handful of squashed commits, say so and state that the level is
low-confidence — that is when to pass the level explicitly.

### 3. Branch and bump

Branch `chore/release-X.Y.Z`. Edit only the `<revision>` line.

**`<revision>` normally runs ahead of the last tag, and the tag is the previous release — not
the pom.** Every deploy bumps the patch, so today `<revision>` is 3.1.1 against last tag
`v3.0.0`. Derive the level from `LAST..main` as in step 2, compute `last tag + level`, then
release **the greater of that and the current `<revision>`**. Never cut a tag at or below
`<revision>`: that number already names a deployed JAR, and `gh release create` cannot be taken
back. If the pom is already at or past the target, step 3 edits nothing — say so in step 8
rather than silently bumping past it.

Write the file back preserving line endings (`newline=''` in Python, or an in-place
Edit). Every text blob is LF since the renormalization, and `verify_line_endings` in
S03/R03 will fail the build if a CRLF blob reappears.

### 4. Run every runner, in order, in the background

```bash
./test-plans/scripts/run-regression.sh          # mandatory pre-push gate: 11 fixtures + CMP
./test-plans/scripts/run-all-tests.sh           # the 19 E-scenarios — see below
./test-plans/scripts/run-config-tests.sh        # C-stages
./test-plans/scripts/run-error-tests.sh         # N-stages
./test-plans/scripts/run-docker-tests.sh        # containerised JMeter
./test-plans/scripts/run-title-junit-tests.sh   # T-stages
./test-plans/scripts/run-family-ab-tests.sh     # A/B — output artifacts
./test-plans/scripts/run-family-cd-tests.sh     # C/D — includes the -n -t -e -o handshake
./test-plans/scripts/run-family-e-tests.sh
./test-plans/scripts/run-family-fh-tests.sh
./test-plans/scripts/run-family-g-tests.sh
./test-plans/scripts/run-family-ij-extra-tests.sh
```

**Ranges are deliberately not written here.** `./scripts/list-scenarios.sh` generates them from
the runners' own `record` calls (`--text`, `--csv`, `--count`), and every hand-kept copy of that
in this repo has been wrong within a month. Each runner also prints its own total.

**Everything below `run-docker-tests.sh` used to be missing from this list, and two of them are
load-bearing.** `run-family-ab-tests.sh` carries **B12**, which CLAUDE.md cites by name as one of
the two deliberate exit-0 exemptions — an exemption defended only by a stage no documented
workflow ran is not defended. `run-family-cd-tests.sh` D01–D06 is the **only** coverage of the
combined `-n -t -e -o` handshake, which is exactly where the listener and the exporter were
found emitting two artifact sets that disagreed about the gate.

**`run-all-tests.sh` is not optional either, and it too was once missing.** Regression covers
only the fixture tier, so E01, E03, E04, E13, E15, E20 and E22 never executed for a release at
all (E24 ran only as regression's live smoke). E20 is the one stage that proves the external-data
payload is written and referenced at that size.

Chain them in one backgrounded command and read the summaries; each exits with its failure
count. Budget ~40 minutes for the full set — the config tier alone is ~13½ of that, serially,
mostly JVM start-up.

Do **not** wait with `pgrep` — Git Bash here has no `pgrep`, so `until ! pgrep …` exits
on the first iteration and reports a still-running suite as finished.

On any failure: report the failing stage with its actual output, leave the branch in
place for inspection, and stop. Do not push, tag or release.

### 5. Merge through a PR — never push to `main`

`main` changes via pull request only; the `pre-push` hook refuses a direct push. Push
the branch, `gh pr create`, `gh pr merge --merge`, then delete the branch local and
remote once it is confirmed merged. Fast-forward local `main` to `origin/main`.

If the fast-forward aborts complaining about modified files, check
`git diff --ignore-cr-at-eol` first — if it reports zero changed files it is line-ending
noise and a `git reset --hard origin/main` is safe.

### 6. Build the artifact

```bash
./scripts/build-and-deploy.sh
```

The release asset is the **shaded** JAR:
`jmeter-web-insight-report/target/jmeter-web-insight-report-X.Y.Z.jar`.

Never attach `original-jmeter-web-insight-report-*.jar` (pre-shading) or
`jmeter-report-core-*.jar` — core alongside the fat JAR puts duplicate classes on
JMeter's flat `lib/ext` classpath and which copy wins is non-deterministic. Exactly one
plugin JAR belongs in `lib/ext`.

Four more assets go beside the JAR, each taken from the tagged tree as it is:

| Asset | Why |
|---|---|
| `scripts/wir-collect.sh` | The server-metrics collector's wrapper (step 2 of the three steps). It is self-contained on purpose — it must run as a lone copy beside a released JAR, sourcing nothing from this repository (CLAUDE.md, "The collector") — so a user should not need the source archive to get it |
| `scripts/wir-collect.bat` | One `java` line running the JAR's `WirCollectLauncher`, for `cmd.exe` users; no bash. `java -jar <the JAR>` is the documented form on every OS, the two wrappers are conveniences |
| `server-data-example-X.Y.Z.zip` | `examples/server-data` zipped (`cd examples && zip -r ../server-data-example-X.Y.Z.zip server-data`): plan, annotations, `run.properties`, the stand-in export tool and the `hooks/` (bash, PowerShell) - the two-command flow a user can run before pointing it at their own monitoring. C43 and cross-os.yml run exactly these files |
| `jmeter-web-insight-report/src/main/resources/static/schema/report-annotations.schema.json` | The annotations file's JSON Schema: associated with an annotations file in an editor, it gives completion and typo squiggles for `assertions` and `serverMetrics`. It is in the JAR too, but nobody points an editor into a JAR |

Attach the working-tree copies, never a `git show` of the blobs: the `.bat`'s blob is LF, and
`.gitattributes` checks it out `eol=crlf` on every OS, which is the copy `cmd.exe` parses; the
`.sh` is LF either way.

### 7. Tag and publish

Tag `vX.Y.Z` on the merge commit, push the tag, then:

```bash
gh release create vX.Y.Z <shaded-jar> \
  scripts/wir-collect.sh scripts/wir-collect.bat server-data-example-X.Y.Z.zip \
  jmeter-web-insight-report/src/main/resources/static/schema/report-annotations.schema.json \
  --title "vX.Y.Z — <one-line summary>" \
  --notes "<body>"
```

Match the existing shape: `v1.1.0` is titled `v1.1.0 — audit bugfix release` with
`jmeter-web-insight-report-1.1.0.jar` attached; a release now carries step 6's four assets beside
the JAR. Published, not draft, not prerelease
(v1.0.0 is flagged prerelease; later ones are not).

Compose the notes from `git log --oneline --merges <last-tag>..main` — group them into
what changed for a user (features, fixes, internals) rather than pasting the raw list —
and include the install line:

> Drop `jmeter-web-insight-report-X.Y.Z.jar` into `$JMETER_HOME/lib/ext/`, removing any
> older copy first. Server data from a CSV needs nothing more: `jmeter -g results.jtl -o report -q
> run.properties` reads it (see `server-data-example-X.Y.Z.zip`). To collect from Prometheus, run
> `java -jar <JMETER_HOME>/lib/ext/jmeter-web-insight-report-X.Y.Z.jar --jtl results.jtl ...` on any OS.

### 8. Report

State the released version, the tag and release URL, the tier results, and the merge
count covered. Restate the bump level **and the evidence step 2 derived it from** — and
if that derivation was flagged low-confidence (squashed history), say so, since the
version is the one thing a published release cannot take back.

## Traps

- **The version has exactly one home.** `<revision>` in the parent pom. A hardcoded
  version in a module pom breaks the flatten plugin's rewrite.
- **`lib/ext` holds one JAR.** `build-and-deploy.sh` purges older plugin JARs; verify it
  did.
- **Tests do not prove the report is right unless fixtures regenerated.** Regression
  regenerates only when the provenance stamps say the source moved. A release whose
  fixtures were skipped still had its rendering checked against the same source, which
  is the point — but never reach for `WIR_SKIP_FIXTURE_CHECK=1` to get past a stale
  fixture failure.
- **`gh release create` is not idempotent.** If a tag or release for that version already
  exists, stop and report rather than deleting and recreating a published release.
