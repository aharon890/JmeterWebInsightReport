/**
 * The annotations schema an editor fetches is the one the plugin ships.
 *
 * Every annotations file the page writes carries a "$schema" URL, and the schema's "$id" is the
 * same URL. It used to name a path nothing served, so every downloaded file pointed editors at a
 * 404. The URL is now the docs site, which serves docs/schema/; this holds that copy byte for byte
 * to the resource inside the JAR, and holds report.js's SCHEMA_URL and the committed annotation
 * files to the schema's own $id, so the three cannot drift apart.
 *
 * No DOM, no fixture, no network.
 */
'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '../..');
const RESOURCE = path.join(ROOT, 'jmeter-web-insight-report/src/main/resources/static/schema/report-annotations.schema.json');
const PUBLISHED = path.join(ROOT, 'docs/schema/report-annotations.schema.json');
const PAGES = 'https://aharon890.github.io/JmeterWebInsightReport/';

const resource = fs.readFileSync(RESOURCE, 'utf8');
assert.ok(fs.existsSync(PUBLISHED), 'docs/schema/report-annotations.schema.json is missing — copy the resource there');
assert.strictEqual(fs.readFileSync(PUBLISHED, 'utf8'), resource,
    'docs/schema/report-annotations.schema.json differs from the shipped schema — copy '
    + 'jmeter-web-insight-report/src/main/resources/static/schema/report-annotations.schema.json over it');

const id = JSON.parse(resource).$id;
assert.strictEqual(id, PAGES + 'schema/report-annotations.schema.json',
    'the schema $id must be the URL the docs site serves the published copy at');

const reportJs = fs.readFileSync(path.join(ROOT, 'jmeter-web-insight-report/src/main/resources/static/js/report.js'), 'utf8');
const m = reportJs.match(/var SCHEMA_URL =\s*'([^']+)'/);
assert.ok(m, 'report.js no longer declares SCHEMA_URL — this test is stale');
assert.strictEqual(m[1], id, "report.js's SCHEMA_URL is not the schema's $id");

for (const f of ['test-plans/annotations/asserts.json', 'test-plans/annotations/example.json', 'reportExample/report-annotations.json']) {
    const doc = JSON.parse(fs.readFileSync(path.join(ROOT, f), 'utf8'));
    assert.strictEqual(doc.$schema, id, `${f}: "$schema" is not the schema's $id`);
}

console.log('[schema-published] PASS');
