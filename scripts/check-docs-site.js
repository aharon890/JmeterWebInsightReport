#!/usr/bin/env node
/**
 * Crawl the documentation site in a real browser: every page reachable from the sidebar and the
 * homepage, every in-body page link, every image and every `?id=` anchor against the heading ids
 * the rendered page actually has. Exits 1 on any miss.
 *
 *   node scripts/check-docs-site.js           serve docs/ locally the way Pages does, and crawl it
 *   node scripts/check-docs-site.js --live    crawl https://aharon890.github.io/JmeterWebInsightReport/
 *
 * Manual, not part of any suite: it needs the Playwright install under test-plans/playwright
 * (`npm ci` there) and, for the CDN-loaded Docsify, the network. The offline counterpart that CI
 * runs is test-plans/js-unit/docs-links.test.js.
 */
'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const { chromium } = require(path.join(ROOT, 'test-plans/playwright/node_modules/playwright'));
const LIVE = 'https://aharon890.github.io/JmeterWebInsightReport/';
const PREFIX = '/JmeterWebInsightReport/';
const TYPES = { '.html': 'text/html', '.md': 'text/markdown', '.png': 'image/png',
    '.js': 'text/javascript', '.json': 'application/json', '.css': 'text/css' };

function serve(dir) {
    const server = http.createServer((req, res) => {
        const url = decodeURIComponent(req.url.split('?')[0]);
        if (!url.startsWith(PREFIX)) { res.writeHead(404); return res.end(); }
        let file = path.join(dir, url.slice(PREFIX.length) || 'index.html');
        if (fs.existsSync(file) && fs.statSync(file).isDirectory()) file = path.join(file, 'index.html');
        if (!file.startsWith(dir) || !fs.existsSync(file)) { res.writeHead(404); return res.end('404'); }
        res.writeHead(200, { 'Content-Type': TYPES[path.extname(file)] || 'application/octet-stream' });
        fs.createReadStream(file).pipe(res);
    });
    return new Promise(resolve => server.listen(0, '127.0.0.1',
        () => resolve({ server, base: `http://127.0.0.1:${server.address().port}${PREFIX}` })));
}

(async () => {
    const live = process.argv.includes('--live');
    const local = live ? null : await serve(path.join(ROOT, 'docs'));
    const base = live ? LIVE : local.base;
    const browser = await chromium.launch();
    const page = await browser.newPage();
    const problems = [];
    const ids = new Map();          // route -> heading ids on that page
    const anchors = [];             // [route it was found on, href]
    const queue = ['/'];
    const seen = new Set();

    async function open(route) {
        await page.goto(base + '#' + route, { waitUntil: 'networkidle' });
        await page.waitForFunction(() => {
            const s = document.querySelector('.markdown-section');
            return s && s.textContent.trim().length > 0;
        }, null, { timeout: 20000 });
        await page.waitForTimeout(300);
    }

    await open('/');
    for (const h of await page.$$eval('.sidebar-nav a', as => as.map(a => a.getAttribute('href')))) {
        if (h && h.startsWith('#/')) queue.push(h.slice(1).split('?')[0]);
    }
    while (queue.length) {
        const route = queue.shift();
        if (seen.has(route)) continue;
        seen.add(route);
        await open(route);
        const r = await page.evaluate(() => {
            const s = document.querySelector('.markdown-section');
            return {
                text: s.textContent.trim().slice(0, 40),
                ids: [...s.querySelectorAll('[id]')].map(e => e.id),
                images: [...s.querySelectorAll('img')].filter(i => !(i.complete && i.naturalWidth > 0))
                    .map(i => i.getAttribute('src')),
                links: [...s.querySelectorAll('a')].map(a => a.getAttribute('href')).filter(h => h && h.startsWith('#/')),
            };
        });
        if (/^404 - Not found/.test(r.text)) { problems.push(`page 404: #${route}`); continue; }
        ids.set(route, new Set(r.ids));
        r.images.forEach(src => problems.push(`image did not load on #${route}: ${src}`));
        for (const h of r.links) {
            const [target, query] = h.slice(1).split('?');
            if (query && query.startsWith('id=')) anchors.push([route, h]);
            if (!seen.has(target)) queue.push(target);
        }
    }
    for (const [route, h] of anchors) {
        const [target, query] = h.slice(1).split('?');
        const id = decodeURIComponent(query.slice(3));
        const known = ids.get(target);
        if (known && !known.has(id)) problems.push(`anchor on #${route} points at a missing id: ${h}`);
    }
    await browser.close();
    if (local) local.server.close();

    console.log(`[docs-site] ${live ? LIVE : 'local docs/'}: ${seen.size} pages, ${anchors.length} anchor links`);
    if (problems.length) {
        console.error(`[docs-site] FAIL: ${problems.length} problem(s)`);
        [...new Set(problems)].forEach(p => console.error('  ' + p));
        process.exit(1);
    }
    console.log('[docs-site] PASS');
})().catch(e => { console.error(e); process.exit(1); });
