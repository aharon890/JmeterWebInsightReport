# Contributing to JMeter Web Insight Report

Thanks for your interest in contributing! Bug reports, fixes, features, documentation and ideas are
all welcome.

## Getting started

### Prerequisites

- Java 21+
- Maven 3.8+
- Apache JMeter 5.6.3+, for the scenario suites and for trying a change in a real run
- Node.js, for the `report.js` unit tests and the Playwright browser suite
- On Windows, Git Bash: every script is a `.sh`, and each `.bat` is a thin wrapper around one

### Build and install locally

```bash
./scripts/build-and-deploy.sh
```

This builds both modules, copies the shaded plugin JAR into `$JMETER_HOME/lib/ext/` (removing any
older one), and registers the `-g -o` exporter. `JMETER_HOME` is auto-detected; set it to pick a
specific install.

### Install the git hooks

```bash
./scripts/install-hooks.sh
```

The hooks refuse a commit or a push on `main` and run no tests. `main` changes only through a
pull request.

## Running the tests

```bash
mvn test                                 # Java, both modules
./test-plans/scripts/run-sanity.sh       # build, Java + report.js unit tests, static checks,
                                         # a JMeter smoke run, the browser suite on existing fixtures
./test-plans/scripts/run-regression.sh   # the same over freshly generated fixtures; run before a push
```

CI runs the fast tier on every push and pull request: `mvn verify`, the `report.js` unit tests and
the static checks. It never starts JMeter or a browser, so run the regression locally before you
open a pull request. [docs/Instructions.md](docs/Instructions.md) has the full test matrix.

## Submitting changes

1. Create a branch from `main` named `feat/…`, `fix/…`, `docs/…`, `chore/…` or `refactor/…`.
2. Make your change, with a test that fails without it.
3. Run the regression.
4. Open a pull request that says what changed and why.

## Reporting bugs

Use the **Bug Report** issue template. Include the JMeter and Java versions, the steps to
reproduce, and what you expected versus what happened. A report's `.json` file or the console
lines it printed help a lot.

## Feature requests

Use the **Feature Request** issue template. Describe the use case: understanding *why* helps
prioritise.

## Code style

- Follow the conventions of the surrounding code.
- Keep dependencies minimal. The plugin runs inside JMeter's flat `lib/ext` classpath, and every
  bundled library must be relocated in the shaded JAR.
- The report is a single self-contained HTML file with no network dependencies. Keep it that way.
- Anything taken from a JTL (sampler labels, URLs, response bodies, error messages) goes through
  `escapeHtml()` before it reaches `innerHTML`.

## License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0.
