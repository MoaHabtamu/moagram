const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const EVENTS = new Set(['install', 'update', 'active']);
const DAY = 24 * 60 * 60 * 1000;
const HOUR = 60 * 60 * 1000;
const NEW_INSTALL_LIMIT = 120;

const SESSION_MS = 14 * DAY;

export default {
    async fetch(request, env) {
        const path = new URL(request.url).pathname;
        const now = Date.now();
        try {
            if (request.method === 'GET' && path === '/v1/summary') {
                if (!allowedToRead(request, env)) return text(401, 'Not available\n');
                return json(await summary(env.DB, now));
            }
            if (request.method === 'GET' && path === '/') {
                if (!(await sessionValid(request, env, now))) return page(loginDocument(''));
                return page(await dashboardDocument(env.DB, now));
            }
            if (request.method === 'POST' && path === '/login') return authenticate(request, env, now);
            if (request.method === 'POST' && path === '/logout') return logout();
            if (request.method === 'POST' && path === '/v1/event') {
                await record(env.DB, await readPayload(request), now);
                return new Response(null, { status: 204, headers: { 'Cache-Control': 'no-store' } });
            }
            return text(404, 'Not found\n');
        } catch (error) {
            if (error instanceof UsageError) return text(error.status, error.message + '\n');
            return text(500, 'Usage database is not connected\n');
        }
    },
};

function sameText(got, expected) {
    if (typeof expected !== 'string' || typeof got !== 'string' || got.length !== expected.length) return false;
    let diff = 0;
    for (let i = 0; i < got.length; i++) diff |= got.charCodeAt(i) ^ expected.charCodeAt(i);
    return diff === 0;
}

function allowedToRead(request, env) {
    const expected = env.READ_TOKEN;
    if (typeof expected !== 'string' || expected.length < 16) return false;
    const header = request.headers.get('authorization') || '';
    const got = header.startsWith('Bearer ') ? header.slice('Bearer '.length) : '';
    return sameText(got, expected);
}

async function authenticate(request, env, now) {
    const expected = env.DASHBOARD_PASSWORD;
    if (typeof expected !== 'string' || expected.length < 8) return page(loginDocument('The dashboard password is not set yet.'));
    const raw = await request.text();
    const password = raw.length > 200 ? '' : (new URLSearchParams(raw).get('password') || '');
    const bucket = await attemptBucket(request, env, now);
    if (!(await attemptsLeft(env.DB, bucket, now))) return page(loginDocument('Too many tries. Wait an hour and try again.'), 429);
    if (!sameText(password, expected)) {
        await noteFailure(env.DB, bucket, now);
        return page(loginDocument('Wrong password.'), 401);
    }
    await env.DB.prepare('DELETE FROM login_limits WHERE bucket = ?').bind(bucket).run();
    const cookie = await sessionCookie(env, now);
    return redirect('/', `moagram_session=${cookie}; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=${SESSION_MS / 1000}`);
}

function logout() {
    return redirect('/', 'moagram_session=; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=0');
}

async function sessionValid(request, env, now) {
    if (typeof env.READ_TOKEN !== 'string' || env.READ_TOKEN.length < 16) return false;
    const cookie = readCookie(request.headers.get('cookie') || '', 'moagram_session');
    const parts = cookie.split('.');
    if (parts.length !== 3 || parts[0] !== 'v1') return false;
    const expires = Number(parts[1]);
    if (!Number.isSafeInteger(expires) || expires < now) return false;
    return sameText(parts[2], await hmac(env.READ_TOKEN, `v1.${expires}`));
}

async function sessionCookie(env, now) {
    const expires = now + SESSION_MS;
    return `v1.${expires}.${await hmac(env.READ_TOKEN, `v1.${expires}`)}`;
}

async function hmac(secret, value) {
    const key = await crypto.subtle.importKey(
        'raw', new TextEncoder().encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
    const signature = await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(value));
    return btoa(String.fromCharCode(...new Uint8Array(signature))).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '');
}

function readCookie(header, name) {
    for (const part of header.split(';')) {
        const [key, ...rest] = part.trim().split('=');
        if (key === name) return rest.join('=');
    }
    return '';
}

async function attemptBucket(request, env, now) {
    const hour = Math.floor(now / HOUR);
    const material = `${env.READ_TOKEN}|${request.headers.get('cf-connecting-ip') || 'unknown'}|${hour}`;
    const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(material));
    const hex = [...new Uint8Array(digest)].map(byte => byte.toString(16).padStart(2, '0')).join('');
    return `${hour}.${hex.slice(0, 24)}`;
}

async function attemptsLeft(db, bucket, now) {
    await db.prepare('DELETE FROM login_limits WHERE seen < ?').bind(now - HOUR).run();
    const row = await db.prepare('SELECT failures FROM login_limits WHERE bucket = ?').bind(bucket).first();
    return !row || row.failures < 8;
}

async function noteFailure(db, bucket, now) {
    await db.prepare(`INSERT INTO login_limits (bucket, failures, seen) VALUES (?, 1, ?)
        ON CONFLICT(bucket) DO UPDATE SET failures = failures + 1, seen = excluded.seen`).bind(bucket, now).run();
}

function loginDocument(message) {
    const notice = message ? `<p class="notice">${escapeHtml(message)}</p>` : '';
    return shell('Sign in', `
        <main class="card narrow">
            <p class="mark">Moagram</p>
            <h1>Active use</h1>
            <p class="quiet">Sign in to see how many phones open the app.</p>
            ${notice}
            <form method="post" action="/login">
                <label for="password">Password</label>
                <input id="password" name="password" type="password" autocomplete="current-password" required>
                <button type="submit">Sign in</button>
            </form>
        </main>`);
}

async function dashboardDocument(db, now) {
    const totals = await summary(db, now);
    const heard = totals.new_installs + totals.existing_installs;
    const days = await dailySeries(db, now);
    return shell('Active use', `
        <main class="card">
            <header>
                <div>
                    <p class="mark">Moagram</p>
                    <h1>Active use</h1>
                </div>
                <form method="post" action="/logout"><button class="quiet-button" type="submit">Sign out</button></form>
            </header>
            <section class="stats">
                ${stat(totals.active_1d, 'Last day')}
                ${stat(totals.active_7d, 'Last 7 days')}
                ${stat(totals.active_30d, 'Last 30 days')}
                ${stat(heard, 'Phones so far')}
            </section>
            <h2>Last 30 days</h2>
            ${chart(days)}
            <p class="quiet">Each bar is a UTC day. A phone counts once on a day it opens Moagram.</p>
        </main>`);
}

function stat(value, label) {
    return `<article><strong>${Number(value) || 0}</strong><span>${escapeHtml(label)}</span></article>`;
}

function chart(days) {
    const width = 640;
    const height = 168;
    const max = Math.max(1, ...days.map(day => day.phones));
    const gap = 5;
    const barWidth = (width - gap * (days.length - 1)) / days.length;
    const bars = days.map((day, index) => {
        const barHeight = day.phones === 0 ? 3 : Math.max(4, Math.round((day.phones / max) * 120));
        const x = index * (barWidth + gap);
        const y = 132 - barHeight;
        return `<rect x="${x.toFixed(1)}" y="${y}" width="${barWidth.toFixed(1)}" height="${barHeight}" rx="2" fill="${day.phones ? '#8fbf6a' : '#34382f'}"><title>${escapeHtml(day.day)}: ${day.phones}</title></rect>`;
    }).join('');
    return `<svg viewBox="0 0 ${width} ${height}" role="img" aria-label="Phones that opened Moagram on each of the last 30 days">${bars}<text x="0" y="158">${escapeHtml(days[0].day.slice(5))}</text><text x="${width}" y="158" text-anchor="end">${escapeHtml(days[days.length - 1].day.slice(5))}</text></svg>`;
}

function shell(title, body) {
    return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="referrer" content="no-referrer">
<title>${escapeHtml(title)}</title>
<style>
:root { color-scheme: dark; }
* { box-sizing: border-box; }
body { margin: 0; min-height: 100vh; display: grid; place-items: center; background: #12140f; color: #f3f0e7; font: 16px/1.45 "Iowan Old Style", Palatino, Georgia, serif; }
.card { width: min(760px, calc(100% - 32px)); margin: 24px 0; padding: 28px; background: #1c1f18; border: 1px solid #31352c; border-radius: 18px; }
.narrow { width: min(420px, calc(100% - 32px)); }
.mark { margin: 0; letter-spacing: 0.14em; text-transform: uppercase; font: 600 12px/1.2 sans-serif; color: #8fbf6a; }
h1 { margin: 8px 0 0; font-size: 36px; font-weight: 600; }
h2 { margin: 28px 0 12px; font: 600 15px/1.2 sans-serif; }
header { display: flex; justify-content: space-between; gap: 16px; align-items: end; }
.quiet { color: #b7b2a6; }
.notice { color: #f0c27a; }
form { margin-top: 22px; }
label { display: block; margin-bottom: 8px; font: 600 13px/1.2 sans-serif; }
input { width: 100%; padding: 12px 14px; border-radius: 10px; border: 1px solid #3d4238; background: #12140f; color: inherit; font: 16px/1.2 sans-serif; }
button { margin-top: 14px; padding: 11px 16px; border: 0; border-radius: 999px; background: #8fbf6a; color: #17200f; font: 600 15px/1 sans-serif; }
.quiet-button { margin: 0; background: transparent; color: #f3f0e7; border: 1px solid #3d4238; }
.stats { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; margin-top: 26px; }
article { padding: 14px; border-radius: 12px; background: #12140f; }
article strong { display: block; font-size: 28px; }
article span, svg text { color: #b7b2a6; font: 12px/1.2 sans-serif; }
svg { width: 100%; height: auto; display: block; }
@media (max-width: 680px) { .stats { grid-template-columns: 1fr 1fr; } h1 { font-size: 30px; } }
</style>
</head>
<body>
${body}
</body>
</html>`;
}

function escapeHtml(value) {
    return String(value).replace(/[&<>"']/g, ch => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch]));
}

function page(body, status = 200, extra) {
    return new Response(body, {
        status,
        headers: {
            'Content-Type': 'text/html; charset=utf-8',
            'Cache-Control': 'no-store',
            'X-Frame-Options': 'DENY',
            'Referrer-Policy': 'no-referrer',
            'Content-Security-Policy': "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'",
            ...extra,
        },
    });
}

function redirect(location, cookie) {
    return new Response(null, {
        status: 303,
        headers: { Location: location, 'Set-Cookie': cookie, 'Cache-Control': 'no-store' },
    });
}

class UsageError extends Error {
    constructor(status, message) {
        super(message);
        this.status = status;
    }
}

async function readPayload(request) {
    const advertised = Number(request.headers.get('content-length') || 0);
    if (!advertised || advertised > 1024) throw new UsageError(400, 'Expected a small JSON body');
    const raw = await request.text();
    if (!raw || raw.length > 1024) throw new UsageError(400, 'Expected a small JSON body');
    try {
        const payload = JSON.parse(raw);
        if (payload === null || typeof payload !== 'object' || Array.isArray(payload)) {
            throw new UsageError(400, 'Expected a JSON object');
        }
        return payload;
    } catch (error) {
        if (error instanceof UsageError) throw error;
        throw new UsageError(400, 'Invalid JSON');
    }
}

function checked(payload) {
    const installId = payload.install_id;
    const event = payload.event;
    const version = payload.version_code;
    const name = payload.version_name === undefined ? '' : payload.version_name;
    if (typeof installId !== 'string' || !UUID.test(installId.toLowerCase())) {
        throw new UsageError(400, 'install_id must be a UUID');
    }
    if (!EVENTS.has(event)) throw new UsageError(400, 'event must be install, update, or active');
    if (typeof version !== 'number' || !Number.isInteger(version) || version <= 0 || version > 2100000000) {
        throw new UsageError(400, 'version_code is invalid');
    }
    if (typeof name !== 'string' || name.length > 80 || [...name].some(ch => ch.charCodeAt(0) < 32)) {
        throw new UsageError(400, 'version_name is invalid');
    }
    return { installId: installId.toLowerCase(), event, version, name };
}

async function record(db, payload, now) {
    if (!db) throw new Error('missing database');
    const { installId, event, version, name } = checked(payload);
    const existing = await db.prepare('SELECT kind FROM installs WHERE id = ?').bind(installId).first();
    if (existing) {
        await db.prepare(`UPDATE installs
            SET version_code = ?, version_name = ?, last_seen = ?, update_count = update_count + ?
            WHERE id = ?`).bind(version, name, now, event === 'update' ? 1 : 0, installId).run();
        await rememberDay(db, installId, now);
        return;
    }
    await db.prepare('DELETE FROM arrivals WHERE seen < ?').bind(now - HOUR).run();
    const recent = await db.prepare('SELECT COUNT(*) AS n FROM arrivals').first();
    if (recent.n >= NEW_INSTALL_LIMIT) throw new UsageError(429, 'Too many new installs this hour');
    await db.batch([
        db.prepare('INSERT INTO arrivals (seen) VALUES (?)').bind(now),
        db.prepare('INSERT INTO installs VALUES (?, ?, ?, ?, ?, ?, ?)')
            .bind(installId, event === 'install' ? 'install' : 'existing', version, name, now, now, event === 'update' ? 1 : 0),
    ]);
    await rememberDay(db, installId, now);
}

async function rememberDay(db, installId, now) {
    const day = utcDay(now);
    const cutoff = utcDay(now - 180 * DAY);
    await db.batch([
        db.prepare('INSERT OR IGNORE INTO daily (day, id) VALUES (?, ?)').bind(day, installId),
        db.prepare('DELETE FROM daily WHERE day < ?').bind(cutoff),
    ]);
}

async function dailySeries(db, now) {
    const start = Date.parse(`${utcDay(now)}T00:00:00.000Z`) - 29 * DAY;
    const rows = await db.prepare(
        'SELECT day, COUNT(*) AS phones FROM daily WHERE day >= ? GROUP BY day'
    ).bind(utcDay(start)).all();
    const counts = new Map((rows.results || []).map(row => [row.day, row.phones]));
    const days = [];
    for (let i = 0; i < 30; i++) days.push({ day: utcDay(start + i * DAY), phones: counts.get(utcDay(start + i * DAY)) || 0 });
    return days;
}

function utcDay(ms) {
    return new Date(ms).toISOString().slice(0, 10);
}

async function summary(db, now) {
    if (!db) throw new Error('missing database');
    const count = async (sql, ...args) => (await db.prepare(sql).bind(...args).first()).n;
    const versions = await db.prepare(`SELECT version_code,
            SUM(kind = 'install') AS new_installs,
            SUM(last_seen >= ?) AS active_30d
        FROM installs GROUP BY version_code ORDER BY version_code DESC`).bind(now - 30 * DAY).all();
    return {
        new_installs: await count("SELECT COUNT(*) AS n FROM installs WHERE kind = 'install'"),
        existing_installs: await count("SELECT COUNT(*) AS n FROM installs WHERE kind = 'existing'"),
        update_events: await count('SELECT COALESCE(SUM(update_count), 0) AS n FROM installs'),
        active_1d: await count('SELECT COUNT(*) AS n FROM installs WHERE last_seen >= ?', now - DAY),
        active_7d: await count('SELECT COUNT(*) AS n FROM installs WHERE last_seen >= ?', now - 7 * DAY),
        active_30d: await count('SELECT COUNT(*) AS n FROM installs WHERE last_seen >= ?', now - 30 * DAY),
        by_version: versions.results,
    };
}

function json(body) {
    return new Response(JSON.stringify(body, null, 2), {
        status: 200,
        headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' },
    });
}

function text(status, body) {
    return new Response(body, {
        status,
        headers: { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store' },
    });
}
