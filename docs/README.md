# Web Insight Report — Tutorial

Complete documentation of the Web Insight Report features and behavior. Each page documents the current behavior — this serves as the functional specification.

## Pages

| # | Page | Description |
|---|------|-------------|
| 1 | [Overview](Tutorial/01-Overview.md) | Installation, report layout, header, tabs, filter bar, output files |
| 2 | [Summary Tab](Tutorial/02-Summary-Tab.md) | Gate strip, server card, metric cards, Apdex, response time chart, Top 5 slowest, Copy Summary and Copy Verdict |
| 3 | [Timeline Tab](Tutorial/03-Timeline-Tab.md) | 9 chart types, chart controls, threshold lines, hide/show, fullscreen, heatmap, granularity |
| 4 | [Samplers Tab](Tutorial/04-Samplers-Tab.md) | Statistics table, 14 columns, assert badges and cell markers, ⊕ to add an assert, sort/search/column toggle, detail expansion, transaction controllers |
| 5 | [Errors Tab](Tutorial/05-Errors-Tab.md) | Error classes, error charts, summary table, detail table by time or grouped, expandable rows |
| 6 | [Compare Tab](Tutorial/06-Compare-Tab.md) | Baseline comparison, regression detection, threshold controls |
| 7 | [Notes Tab](Tutorial/07-Notes-Tab.md) | Markdown notes, timeline markers, annotation editor |
| 8 | [Filter Bar](Tutorial/08-Filter-Bar.md) | Palette, line thickness, metric toggle, granularity, sampler filter, presets |
| 9 | [Dark Mode & Accessibility](Tutorial/09-Dark-Mode-Accessibility.md) | Dark mode, colorblind mode, palette recommendations |
| 10 | [Asserts & Apdex](Tutorial/10-Asserts-Apdex.md) | Assertion rules, targets, metrics, predicates, verdict logic, Apdex |
| 11 | [Export & Integration](Tutorial/11-Export-Integration.md) | CSV export, Copy Summary, CI/CD JSON, JUnit XML, external data mode |
| 12 | [Asserts Tab](Tutorial/12-Asserts-Tab.md) | The gate strip, the rules table, the assert editor, drafts, saving, seeding, Generate from run, cell verdicts, chart interactions |
| 13 | [Server Tab](Tutorial/13-Server-Tab.md) | Server asserts (expected) against collected metrics (actual): `wir-collect`, the header chip, `serverStatus`, components and the catalogue, the editor, and the `serverMetrics` reference |
| 14 | [Getting Server Data In](Tutorial/14-Server-Data.md) | Step by step: a CSV from your monitoring tool into the report — in the JMeter GUI, from any terminal on Windows, macOS or Linux, and from a script; lining the data up with the run; troubleshooting by symptom |

## Screenshots

Screenshots in the `Tutorial/images/` folder are captured from generated test reports and show the actual UI.
