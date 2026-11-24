# Contract: run data, version 1.0

Fields and rules are in [../data-model.md](../data-model.md). The published JSON Schema will be
`docs/schema/run-data.schema.json`, held to the Java records by a test.

## File

- JSON Lines: one JSON object per line, UTF-8 with no BOM, LF line ends. A file whose name ends in `.gz` is
  gzip-compressed.
- The first line is the `run` record. All other records may come in any order, so a listener can append as samples
  arrive. The reader reads the file twice to resolve parents and transactions (data-model.md "Reading order and
  memory").
- Each record has `"type"`. A reader skips a type it does not know.
- The default name beside a report is `<report name>-run-data.jsonl.gz`.

## Example (a k6 run: one group pass with two requests, one failed check)

```json
{"type":"run","formatVersion":"1.0","tool":{"name":"k6","version":"2.4.0"},"testName":"petstore-checkout - DEV #71 (k6)","producer":{"name":"jmeter-web-insight-report","version":"4.5.0"},"timeResolution":"ms","notMeasured":["bytes","sentBytes","latency","connect"]}
{"type":"sample","id":"t1","kind":"transaction","name":"Complete Checkout Flow","start":1795365182210,"wallTime":2350,"user":"VU 1","iteration":0}
{"type":"sample","parent":"t1","name":"Login","start":1795365182210,"elapsed":41,"success":true,"code":"200","user":"VU 1","iteration":0,"method":"POST","url":"http://petstore/api/login","timings":{"blocked":3,"connecting":1,"tlsHandshaking":0,"sending":0,"waiting":40,"receiving":1}}
{"type":"sample","parent":"t1","name":"Checkout","start":1795365183400,"elapsed":120,"success":false,"code":"500","message":"Internal Server Error","user":"VU 1","iteration":0}
{"type":"users","time":1795365182000,"active":1}
{"type":"check","time":1795365183520,"name":"checkout is 200","group":"::Complete Checkout Flow","passed":false,"user":"VU 1","iteration":0}
{"type":"threshold","metric":"http_req_duration{name:Checkout}","expression":"p(95)<=600","passed":true}
{"type":"counter","name":"dropped_iterations","value":0}
```

The transaction `t1` has no `elapsed` and no `success`. The engine sets its elapsed to 161 (41 + 120), its idle time
to 2189, and its success to false (a child failed), and counts it once
in the whole-run figures. It is counted as a transaction row (`kind`), so it is not a sample in the Errors tab and has
no response code. Both requests have `topLevel` false, because they have a parent and give no flag. This is JMeter's
Transaction Controller with "Generate parent sample" on, the mode of petstore's checkout plan.

## A JMeter run's run data (`webinsight.report.rundata=true`)

It holds the same samples the report was built from, with `tool.name` `jmeter`, explicit `parent`, `link`, `kind`,
`topLevel`, `threads`, `idle`, `samples`/`errors` and `failureMessage`. It holds the failed samples' response bodies exactly as the report does (`responseBody`, decision 11). A report built from it equals the JTL's report: CI/CD JSON,
`runId` included (plan stage 2).

## Versions

- A minor version (1.1) only adds optional fields or record types, so a 1.0 reader still builds the report.
- A major version (2.0) is refused by a 1.x reader, with a message naming both versions.
- The docs list, per report release, the versions it reads and the one it writes (FR-014).
