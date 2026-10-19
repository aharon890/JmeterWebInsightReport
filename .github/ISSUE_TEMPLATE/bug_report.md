---
name: Bug Report
about: Report a bug or unexpected behavior
title: "[Bug] "
labels: bug
assignees: ''
---

**Describe the bug**
A clear description of what the bug is.

**To Reproduce**
1. How the report was produced — **listener** (`jmeter -n -t plan.jmx -l results.jtl`) or
   **`jmeter -g results.jtl -o dir`** — and the exact command, with every `-J` property.
2. If server asserts are involved: the `wir-collect` command and its console output.
3. What you clicked or ran next.

**Expected behavior**
What you expected to happen.

**What happened**
What you saw instead, and the **exit code** of the JMeter run (`echo $?` / `%ERRORLEVEL%`).

**Attachments**
The smallest set that shows the problem. **Remove anything sensitive first** — a JTL carries URLs
and response data; annotations files and `server-metrics.json` can name internal hosts.
- Generated report (`.html`, and its `-data.js` beside it for a large run)
- CI/CD JSON (`.json`) and JUnit XML (`.xml`), if written
- `report-annotations.json`
- `server-metrics.json` (the collector's payload), for a Server-tab issue
- JMeter results file (`.jtl`), or a few rows of it
- Browser console errors (F12 → Console)
- Screenshots

**Environment**
- Plugin version: [e.g. 4.0.4]
- JMeter version: [e.g. 5.6.3]
- Java version: [21 or later]
- OS: [e.g. Windows 11, macOS 14, Ubuntu 22.04]
- Browser: [e.g. Chrome 130, Firefox 132]

**JMeter log output**
```
Paste the lines around "WEB-INSIGHT-REPORT" and any stack trace here
```
