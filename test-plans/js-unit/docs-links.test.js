/**
 * Every link in the documentation resolves the way the published site resolves it.
 *
 * The site is Docsify 4 (docs/index.html, `relativePath: true`) served by GitHub Pages from docs/.
 * Links between Tutorial chapters were once all 404 on the live site — Docsify resolved them from
 * the docs root — while every link looked right on github.com, which is where they were reviewed.
 * So this resolves each link as Docsify does, not as a file browser does:
 *
 *  - a page link is resolved against the linking page's directory (relativePath: true); the
 *    homepage, docs/README.md, sits at the root, so its links are written from the root;
 *  - _sidebar.md is rendered on every page, so a relative link there would resolve against
 *    whichever page is open: its links must be root-absolute (`/Tutorial/…`);
 *  - an anchor must be a heading id Docsify generates: its slug strips punctuation, collapses
 *    runs of `-` and, because marked escapes `'` to `&#39;` first, turns an apostrophe into "39" —
 *    `#this-runs-number` never matched the heading "This run's number";
 *  - nothing may leave docs/ — Pages serves only that folder.
 *
 * README.md and CONTRIBUTING.md are read on github.com, so only their relative file links are
 * checked. No DOM, no browser, no network: scripts/check-docs-site.js is the live counterpart.
 */
'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '../..');
const DOCS = path.join(ROOT, 'docs');

function mdFiles(dir) {
    const out = [];
    for (const f of fs.readdirSync(dir)) {
        const p = path.join(dir, f);
        if (fs.statSync(p).isDirectory()) out.push(...mdFiles(p));
        else if (f.endsWith('.md')) out.push(p);
    }
    return out;
}

/** Blank out fenced code so a link or heading inside an example is not read, keeping line numbers. */
function withoutFences(text) {
    return text.replace(/^(```|~~~)[\s\S]*?^\1/gm, m => m.replace(/[^\n]/g, ' '));
}

const ESCAPES = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };

/** Docsify 4.13's slugify over marked's rendering of a heading (inline markup reduced to text). */
function docsifySlugger() {
    const seen = Object.create(null);
    // Docsify's own character class, src/core/render/slugify.js
    const re = /[ -⁯⸀-⹿\\'!"#$%&()*+,./:;<=>?@[\]^`{|}~]/g;
    return function slug(heading) {
        const text = heading
            .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')     // [text](url) renders as its text
            .replace(/\*\*|__/g, '')                     // strong
            .replace(/`/g, '')                           // code span delimiters
            .replace(/[&<>"']/g, c => ESCAPES[c]);       // what marked escapes before Docsify sees it
        let s = text.trim().toLowerCase().replace(re, '').replace(/\s/g, '-').replace(/-+/g, '-')
            .replace(/^(\d)/, '_$1');
        const n = seen[s];
        seen[s] = n === undefined ? 0 : n + 1;
        if (n !== undefined) s = s + '-' + (n + 1);
        return s;
    };
}

const idCache = new Map();
function headingIds(file) {
    if (!idCache.has(file)) {
        const slug = docsifySlugger();
        const ids = new Set();
        for (const line of withoutFences(fs.readFileSync(file, 'utf8')).split('\n')) {
            const m = line.match(/^#{1,6}\s+(.*?)\s*#*\s*$/);
            if (m) ids.add(slug(m[1]));
        }
        idCache.set(file, ids);
    }
    return idCache.get(file);
}

function linksOf(file) {
    const text = withoutFences(fs.readFileSync(file, 'utf8'));
    const out = [];
    const re = /(!?)\[[^\]]*\]\(\s*([^)\s]+)(?:\s+"[^"]*")?\s*\)/g;
    let m;
    while ((m = re.exec(text))) {
        out.push({ image: m[1] === '!', href: m[2], line: text.slice(0, m.index).split('\n').length });
    }
    return out;
}

const rel = f => path.relative(ROOT, f).split(path.sep).join('/');
const problems = [];
let checked = 0;

for (const file of mdFiles(DOCS)) {
    const isSidebar = path.basename(file) === '_sidebar.md';
    for (const { image, href, line } of linksOf(file)) {
        if (/^[a-z][a-z0-9+.-]*:/i.test(href)) continue;       // http:, https:, mailto:
        checked++;
        const where = `${rel(file)}:${line} ${href}`;
        const hash = href.indexOf('#');
        const target = hash < 0 ? href : href.slice(0, hash);
        const anchor = hash < 0 ? '' : decodeURIComponent(href.slice(hash + 1));
        if (isSidebar && target && !target.startsWith('/')) {
            problems.push(`${where} — a sidebar link must be root-absolute (/Tutorial/…): `
                + 'the sidebar is rendered on every page, so a relative link resolves against the open one');
            continue;
        }
        const resolved = !target ? file
            : target.startsWith('/') ? path.join(DOCS, target)
            : path.resolve(path.dirname(file), target);
        if (resolved !== DOCS && !resolved.startsWith(DOCS + path.sep)) {
            problems.push(`${where} — leaves docs/, which is all the site serves; use an absolute GitHub URL`);
            continue;
        }
        if (!fs.existsSync(resolved)) {
            problems.push(`${where} — ${rel(resolved)} does not exist${image ? ' (image)' : ''}`);
            continue;
        }
        if (anchor && resolved.endsWith('.md') && !headingIds(resolved).has(anchor)) {
            problems.push(`${where} — no heading in ${rel(resolved)} gets the id "${anchor}" on the site`);
        }
    }
}

for (const name of ['README.md', 'CONTRIBUTING.md']) {
    const file = path.join(ROOT, name);
    for (const { href, line } of linksOf(file)) {
        if (/^[a-z][a-z0-9+.-]*:/i.test(href) || href.startsWith('#')) continue;
        checked++;
        const target = href.split('#')[0];
        if (!fs.existsSync(path.resolve(ROOT, target))) problems.push(`${name}:${line} ${href} — does not exist`);
    }
}

// The slug rule is the part most likely to be wrong; pin it to ids read off the live site.
const s = docsifySlugger();
assert.strictEqual(s("This run's number"), 'this-run39s-number');
assert.strictEqual(s('Error classes — where a failure was decided'), 'error-classes-where-a-failure-was-decided');
assert.strictEqual(s('From Grafana: `--layout wide`'), 'from-grafana-layout-wide');
assert.strictEqual(s('1. Install the JAR'), '_1-install-the-jar');

// A docs tree this check could not see would pass on nothing.
assert.ok(checked > 200, `only ${checked} links found — the docs moved or the link pattern broke`);

if (problems.length) {
    console.error(`[docs-links] FAIL: ${problems.length} link(s) do not resolve on the docs site:`);
    for (const p of problems) console.error('  ' + p);
    process.exit(1);
}
console.log(`[docs-links] PASS: ${checked} links`);
