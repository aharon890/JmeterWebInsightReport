---
name: dev-cycle
description: Finish a change properly — bump the version, build and deploy the JAR to lib/ext, refresh the report example if report output changed, run the tests, and commit on a branch. Use when a feature or bug fix is done, before moving on.
---

# Finishing a change

```
/dev-cycle              finish the current change
/dev-cycle --no-commit  do everything except the commit
```

Six steps. Three of them are judgement, which is why this is a skill and not a hook: no hook can
know whether a change altered report *output* or only internals, and none can know a change is
finished.

## 0. Where are you?

```bash
git rev-parse --abbrev-ref HEAD
git status --short
```

**On the primary branch → stop and branch first.** The `pre-commit` hook will refuse anyway, but
finding out at the end wastes the run. Prefixes: `feat/` `fix/` `docs/` `chore/` `refactor/`.

## 1. Decide the bump — judgement

`<revision>` in the **parent `pom.xml`** is the only place a version lives. Module poms use
`${revision}`; never hardcode one.

- **Patch** for anything you want to see in a real JMeter run. This is almost always the answer:
  `lib/ext` must hold exactly one plugin JAR and it must be the code you just wrote, or you are
  testing the previous build.
- **Minor and major belong to a release** and are `_perf_release`'s job, not this one.

Skip the bump only when nothing under either module's `src/main` changed — a docs, plan, script or
spec change does not need a new JAR.

## 2. Build and deploy

```bash
./scripts/build-and-deploy.sh
```

Purges older plugin JARs, so `lib/ext` ends with exactly one, and fails, naming the file, when one
will not go. On Windows that is usually VS Code holding it: move it out with PowerShell's
`Move-Item` (the error prints the line) and deploy again. Check the printed version matches the
bump.

## 3. Refresh the example — judgement

**Only when report *output* changed** — the template, `report.js`, `report.css`, the serializers,
the statistics engine. Not for a refactor that renders identically.

```bash
./scripts/refresh-report-example.sh
```

**Run it after the tests are green, not before.** It bakes in whatever the current build does; if
the build is wrong, the example is confidently wrong and committed. So in practice: do step 4
first, then come back here.

Commit `reportExample/web-insight-report.html` and `reportExample/report-annotations.json`
together — the annotations file is what produced the report beside it.

## 4. Test

Use the **`run-tests`** skill. Regression when anything under `src/main` changed; sanity otherwise.
The fixture stamps decide whether the expensive regeneration actually runs, so regression costs
the same as sanity when nothing could have changed a report.

**Do not proceed on a red suite.** There is no CI and no later gate.

## 5. Explore, when there is nothing to explore with — judgement

If the change added behaviour **no spec covers yet** — which is the normal state of a feature
mid-development — run **`explore-report`** against it before committing. That is the one moment
when a browser session is worth more than a test run: the automation cannot fail on a feature it
does not know about.

## 6. Commit

On a branch. A message that says **why**, in the style the repo already uses: what was wrong,
what changed, what it costs. Quote the evidence — the failing number, the file:line, the test
count — rather than asserting quality.

Then stop. **Pushing and PR creation stay manual.** The `pre-push` hook is the one rule this repo
has, and automating around it would defeat the point.

## What this skill will not do

- **Push, or open a PR.** See above.
- **Release.** `_perf_release` owns that.
- **Decide the change is finished.** Only you can.
- **Bump for a docs-only change.** A new JAR that is byte-identical except for a version string is
  noise in `lib/ext` and in the history.
