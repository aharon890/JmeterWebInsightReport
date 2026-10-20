# Security policy

## Supported versions

Only the latest 4.x release receives fixes. Upgrade to it before reporting.

## Reporting a vulnerability

Please do **not** open a public issue. Use GitHub's private reporting instead:
**Security** tab → **Report a vulnerability**
(https://github.com/aharon890/JmeterWebInsightReport/security/advisories/new).

Include the plugin version, how the report was produced (listener run or `jmeter -g … -o`),
and the smallest JTL / annotations file that shows the problem. Strip anything sensitive from
them first — JTLs carry URLs and response data, and annotations and `server-metrics.json`
payloads can name internal hosts.

A confirmed issue is fixed in a patch release.

## Scope

The plugin writes self-contained HTML reports that render content taken from your JTL (sampler
labels, URLs, error messages, response bodies). Script injection through that content is in
scope. Jackson, SLF4J and Commons Math come from JMeter's own `lib/` and are reported upstream.
