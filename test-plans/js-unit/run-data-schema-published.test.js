/**
 * The run data schema a producer checks its output against is the one the plugin ships (spec 001, US4, T059).
 *
 * As schema-published.test.js holds the annotations schema: docs/schema/run-data.schema.json is byte for byte the
 * resource inside the JAR, and its "$id" is the URL the docs site serves it at. RunDataSchemaTest (Java) holds its
 * fields to what the producer writes.
 */
'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '../..');
const RESOURCE = path.join(ROOT, 'jmeter-web-insight-report/src/main/resources/static/schema/run-data.schema.json');
const PUBLISHED = path.join(ROOT, 'docs/schema/run-data.schema.json');
const PAGES = 'https://aharon890.github.io/JmeterWebInsightReport/';

const resource = fs.readFileSync(RESOURCE, 'utf8');
assert.ok(fs.existsSync(PUBLISHED), 'docs/schema/run-data.schema.json is missing - copy the resource there');
assert.strictEqual(fs.readFileSync(PUBLISHED, 'utf8'), resource,
    'docs/schema/run-data.schema.json differs from the shipped schema - copy '
    + 'jmeter-web-insight-report/src/main/resources/static/schema/run-data.schema.json over it');
assert.strictEqual(JSON.parse(resource).$id, PAGES + 'schema/run-data.schema.json',
    'the schema $id must be the URL the docs site serves the published copy at');
const docs = fs.readFileSync(path.join(ROOT, 'docs/Tutorial/18-Run-Data.md'), 'utf8');
assert.ok(docs.includes(JSON.parse(resource).$id), 'the Run data page names the schema by its $id');

console.log('  ok   the published run data schema is the shipped one, at its $id');
console.log('\n1/1 passed');
