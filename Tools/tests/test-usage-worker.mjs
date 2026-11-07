// Execute the real Worker against SQLite without Cloudflare credentials or packages.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { webcrypto } from 'node:crypto';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';

globalThis.crypto ??= webcrypto;
const source = readFileSync(new URL('../stats/cloudflare/worker.js', import.meta.url), 'utf8');
const { default: worker } = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);
const schema = readFileSync(new URL('../stats/cloudflare/schema.sql', import.meta.url), 'utf8');
const DAY = 86400000;
const NOW = Date.parse('2026-11-07T12:00:00Z');
const TODAY = NOW - 12 * 3600000;
const python = `
import json, sqlite3, sys
data = json.load(sys.stdin)
db = sqlite3.connect(sys.argv[1])
db.row_factory = sqlite3.Row
with db:
    if 'schema' in data:
        db.executescript(data['schema'])
    results = []
    for statement in data.get('statements', []):
        cursor = db.execute(statement['sql'], statement['args'])
        results.append({'results': [dict(row) for row in cursor.fetchall()]})
print(json.dumps(results))
`;

function fixture(t) {
    const directory = mkdtempSync(join(tmpdir(), 'moagram-usage-'));
    t.after(() => rmSync(directory, { recursive: true, force: true }));
    const execute = input => {
        const result = spawnSync('python3', ['-c', python, join(directory, 'usage.sqlite')], {
            input: JSON.stringify(input), encoding: 'utf8',
        });
        assert.equal(result.status, 0, result.stderr);
        return JSON.parse(result.stdout);
    };
    execute({ schema });
    const db = {
        prepare(sql) {
            return {
                sql, args: [],
                bind(...args) { this.args = args; return this; },
                async all() { return execute({ statements: [this] })[0]; },
                async first() { return (await this.all()).results[0] || null; },
                async run() { return this.all(); },
            };
        },
        async batch(statements) { return execute({ statements }); },
    };
    const env = { DB: db, READ_TOKEN: 'test-read-token-for-local-tests', DASHBOARD_PASSWORD: 'local-test-password' };
    const originalNow = Date.now;
    Date.now = () => NOW;
    t.after(() => { Date.now = originalNow; });
    const fetch = (path, options) => worker.fetch(new Request(`https://usage.example${path}`, options), env);
    const summary = async () => {
        const response = await fetch('/v1/summary', { headers: { Authorization: `Bearer ${env.READ_TOKEN}` } });
        assert.equal(response.status, 200);
        return response.json();
    };
    const dashboard = async () => {
        const response = await fetch('/login', { method: 'POST', body: `password=${env.DASHBOARD_PASSWORD}` });
        assert.equal(response.status, 303);
        const page = await fetch('/', { headers: { Cookie: response.headers.get('set-cookie').split(';')[0] } });
        assert.equal(page.status, 200);
        return page.text();
    };
    return { db, fetch, summary, dashboard };
}

async function seed(db, id, first, last, days, version = 8, name = 'Preview 8') {
    await db.batch([
        db.prepare('INSERT INTO installs VALUES (?, ?, ?, ?, ?, ?, ?)').bind(id, 'install', version, name, first, last, 0),
        ...days.map(time => db.prepare('INSERT OR IGNORE INTO daily VALUES (?, ?)').bind(new Date(time).toISOString().slice(0, 10), id)),
    ]);
}

test('private dashboard and empty state do not fabricate activity or retention', async t => {
    const f = fixture(t);
    assert.equal((await f.fetch('/v1/summary')).status, 401);
    assert.match(await (await f.fetch('/')).text(), /Sign in/);
    const totals = await f.summary();
    assert.equal(totals.active_30d, 0);
    assert.equal(totals.week_two.eligible, 0);
    assert.equal(totals.week_two.returned, 0);
    const html = await f.dashboard();
    assert.match(html, /No installs have reported in the last 30 days/);
    assert.match(html, /Not enough history yet/);
    assert.match(html, /2026-11-07 \(today, incomplete\)/);
    assert.match(html, /<details><summary>Show daily counts/);
    assert.match(html, /fill="#b7b2a6"|fill: #b7b2a6/);
    assert.doesNotMatch(html, /NaN|Infinity|Phones/);
});

test('repeat HTTP reports count once per UTC day and version adoption follows updates', async t => {
    const f = fixture(t);
    const id = '12345678-1234-4123-8123-123456789abc';
    async function report(event, version) {
        const body = JSON.stringify({ install_id: id, event, version_code: version, version_name: `Preview ${version}` });
        const response = await f.fetch('/v1/event', { method: 'POST', body, headers: { 'Content-Length': String(Buffer.byteLength(body)) } });
        assert.equal(response.status, 204);
    }
    await report('install', 7);
    await report('active', 7);
    await report('update', 8);
    const totals = await f.summary();
    assert.equal(totals.new_installs, 1);
    assert.equal(totals.active_30d, 1);
    assert.deepEqual(totals.by_version, [{ version_code: 8, version_name: 'Preview 8', new_installs: 1, active_30d: 1 }]);
    assert.deepEqual((await f.db.prepare('SELECT * FROM daily').all()).results, [{ day: '2026-11-07', id }]);
    const html = await f.dashboard();
    assert.match(html, /Preview 8/);
    assert.match(html, /100\.0%/);
    assert.match(html, /2026-11-07 \(today, incomplete\)<\/th><td>1/);
});

test('week two excludes incomplete, old, and missing-history cohorts and deduplicates returns', async t => {
    const f = fixture(t);
    const first = TODAY - 14 * DAY;
    for (const offset of [6, 7, 13, 14]) {
        await seed(f.db, `cohort-${offset}`, first, first + offset * DAY, [first, first + offset * DAY]);
    }
    await f.db.prepare('INSERT INTO daily VALUES (?, ?)').bind('2026-11-01', 'cohort-7').run();
    await seed(f.db, 'incomplete', TODAY - 13 * DAY, NOW, [TODAY - 13 * DAY, NOW]);
    await seed(f.db, 'oldest', TODAY - 43 * DAY, TODAY - 43 * DAY, [TODAY - 43 * DAY]);
    await seed(f.db, 'too-old', TODAY - 44 * DAY, NOW, [TODAY - 44 * DAY, NOW]);
    await seed(f.db, 'missing-history', first, NOW, [NOW]);
    assert.deepEqual((await f.summary()).week_two, {
        eligible: 5, returned: 2, cohort_start: '2026-09-25', cohort_end: '2026-10-24',
    });
    const html = await f.dashboard();
    assert.match(html, /40\.0%/);
    assert.match(html, /2 of 5 eligible installs/);
});

test('version shares exclude dormant installs and escape reported version names', async t => {
    const f = fixture(t);
    await seed(f.db, 'active-a', NOW, NOW, [NOW], 8, '<script>alert(1)</script>');
    await seed(f.db, 'active-b', NOW, NOW, [NOW], 7, 'Preview 7');
    await seed(f.db, 'inactive', NOW - 31 * DAY, NOW - 31 * DAY, [NOW - 31 * DAY], 6, 'Dormant version');
    const html = await f.dashboard();
    assert.equal((html.match(/50\.0%/g) || []).length, 2);
    assert.match(html, /&lt;script&gt;alert\(1\)&lt;\/script&gt;/);
    assert.doesNotMatch(html, /<script>|Dormant version/);
    assert.match(html, /<line[^>]+/); // Count scale remains present for nonempty data.
});
