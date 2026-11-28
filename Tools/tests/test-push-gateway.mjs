// Execute the real push relay Worker with a recording fetch; no Cloudflare account needed.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readFileSync } from 'node:fs';

const source = readFileSync(new URL('../push-gateway/worker.js', import.meta.url), 'utf8');
const { default: worker, parseEndpoint } =
    await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);

const ENV = { ALLOWED_HOSTS: 'ntfy.sh' };
const ENDPOINT = 'https://ntfy.sh/upAbC123?up=1';
const ENC = 'salt=c2FsdHNhbHRzYWx0c2FsdA';
const CK = 'dh=BPub;p256ecdsa=Vapid';

let calls = [];
let upstreamStatus = 201;
globalThis.fetch = async (url, init) => {
    calls.push({ url, init });
    return new Response(null, { status: upstreamStatus, headers: { 'retry-after': '30' } });
};

function push(endpoint = ENDPOINT, { headers = {}, body = new Uint8Array([1, 2, 3, 4]) } = {}) {
    const url = 'https://relay.example/aesgcm?e=' + encodeURIComponent(endpoint);
    return worker.fetch(new Request(url, {
        method: 'POST',
        headers: { encryption: ENC, 'crypto-key': CK, ...headers },
        body,
    }), ENV);
}

test('relays a push with the aesgcm headers framed into the body', async () => {
    calls = [];
    upstreamStatus = 200;
    const res = await push(ENDPOINT, { headers: { ttl: '60' } });
    assert.equal(res.status, 201);
    assert.equal(calls.length, 1);
    assert.equal(calls[0].url, ENDPOINT);
    assert.equal(calls[0].init.method, 'POST');
    assert.equal(calls[0].init.redirect, 'manual');
    assert.equal(calls[0].init.headers.TTL, '60');
    const body = Buffer.from(calls[0].init.body);
    const prefix = `aesgcm\nEncryption: ${ENC}\nCrypto-Key: ${CK}\n`;
    assert.equal(body.subarray(0, prefix.length).toString(), prefix);
    assert.deepEqual([...body.subarray(prefix.length)], [1, 2, 3, 4]);
});

test('gone subscriptions are reported back so Telegram stops pushing', async () => {
    for (const status of [404, 410]) {
        upstreamStatus = status;
        assert.equal((await push()).status, status);
    }
    upstreamStatus = 429;
    const limited = await push();
    assert.equal(limited.status, 429);
    assert.equal(limited.headers.get('retry-after'), '30');
    upstreamStatus = 500;
    assert.equal((await push()).status, 502);
    upstreamStatus = 201;
});

test('refuses endpoints that are not allowed or not public https', async () => {
    calls = [];
    for (const bad of ['http://ntfy.sh/up1', 'https://evil.example/up1', 'https://127.0.0.1/x',
                       'https://user:pw@ntfy.sh/up1', 'not a url', '']) {
        assert.equal((await push(bad)).status, 403, bad);
    }
    assert.equal(calls.length, 0);
    assert.equal(parseEndpoint('https://10.0.0.5/x', []), null);
    assert.equal(parseEndpoint('https://localhost/x', []), null);
    assert.equal(parseEndpoint('https://[::1]/x', []), null);
    assert.ok(parseEndpoint('https://push.example.org/x', []), 'any public host when no allowlist');
});

test('rejects malformed pushes before contacting the endpoint', async () => {
    calls = [];
    assert.equal((await push(ENDPOINT, { headers: { encryption: '' } })).status, 400);
    assert.equal((await push(ENDPOINT, { headers: { 'crypto-key': 'dh=' + 'a'.repeat(600) } })).status, 400);
    assert.equal((await push(ENDPOINT, { body: new Uint8Array(0) })).status, 400);
    assert.equal((await push(ENDPOINT, { body: new Uint8Array(9000) })).status, 413);
    assert.equal(calls.length, 0);
});

test('only the relay route and health check exist', async () => {
    assert.equal((await worker.fetch(new Request('https://relay.example/'), ENV)).status, 200);
    assert.equal((await worker.fetch(new Request('https://relay.example/aesgcm'), ENV)).status, 404);
    assert.equal((await worker.fetch(new Request('https://relay.example/other', { method: 'POST' }), ENV)).status, 404);
});

// ------------------------------------------------------------------ /fcm route
const { resetTokenCache, headerParam } =
    await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);

const keyPair = await crypto.subtle.generateKey(
    { name: 'RSASSA-PKCS1-v1_5', modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-256' },
    true, ['sign', 'verify']);
const pkcs8 = Buffer.from(await crypto.subtle.exportKey('pkcs8', keyPair.privateKey)).toString('base64');
const ACCOUNT = {
    type: 'service_account', project_id: 'moagram-test', client_email: 'relay@moagram-test.iam.gserviceaccount.com',
    private_key: `-----BEGIN PRIVATE KEY-----\n${pkcs8.match(/.{1,64}/g).join('\n')}\n-----END PRIVATE KEY-----\n`,
};
const FCM_ENV = { FCM_SERVICE_ACCOUNT: JSON.stringify(ACCOUNT) };
const FCM_TOKEN = 'dGVzdC10b2tlbg:APA91bH-example_token.value';

let fcmStatus = 200;
let fcmBody = {};
let tokenRequests = 0;
function installGoogle() {
    calls = [];
    globalThis.fetch = async (url, init) => {
        calls.push({ url, init });
        if (url === 'https://oauth2.googleapis.com/token') {
            tokenRequests++;
            return Response.json({ access_token: 'ya29.test', expires_in: 3600 });
        }
        return Response.json(fcmBody, { status: fcmStatus });
    };
}

function fcmPush({ headers = {}, body = new Uint8Array([9, 8, 7]), token = FCM_TOKEN, env = FCM_ENV } = {}) {
    return worker.fetch(new Request('https://relay.example/fcm?t=' + encodeURIComponent(token), {
        method: 'POST',
        headers: { encryption: 'salt=SALT;rs=4096', 'crypto-key': 'dh=SERVERKEY;p256ecdsa=VAPID', ttl: '120', ...headers },
        body,
    }), env);
}

test('fcm: signs a service-account JWT and sends the encrypted push as data', async () => {
    resetTokenCache(); installGoogle(); fcmStatus = 200; tokenRequests = 0;
    const res = await fcmPush();
    assert.equal(res.status, 201);
    assert.equal(calls.length, 2);

    const form = new URLSearchParams(calls[0].init.body);
    assert.equal(form.get('grant_type'), 'urn:ietf:params:oauth:grant-type:jwt-bearer');
    const [h, c, s] = form.get('assertion').split('.');
    const claims = JSON.parse(Buffer.from(c, 'base64url'));
    assert.equal(JSON.parse(Buffer.from(h, 'base64url')).alg, 'RS256');
    assert.equal(claims.iss, ACCOUNT.client_email);
    assert.equal(claims.scope, 'https://www.googleapis.com/auth/firebase.messaging');
    assert.ok(await crypto.subtle.verify('RSASSA-PKCS1-v1_5', keyPair.publicKey,
        Buffer.from(s, 'base64url'), new TextEncoder().encode(`${h}.${c}`)), 'JWT signature verifies');

    assert.equal(calls[1].url, 'https://fcm.googleapis.com/v1/projects/moagram-test/messages:send');
    assert.equal(calls[1].init.headers.Authorization, 'Bearer ya29.test');
    const message = JSON.parse(calls[1].init.body).message;
    assert.equal(message.token, FCM_TOKEN);
    assert.deepEqual(message.android, { priority: 'HIGH', ttl: '120s' });
    assert.deepEqual(message.data, { s: 'SALT', d: 'SERVERKEY', r: '4096', c: Buffer.from([9, 8, 7]).toString('base64url') });

    await fcmPush();
    assert.equal(tokenRequests, 1, 'OAuth token is cached between pushes');
});

test('fcm: oversized pushes become a content-free wake-up', async () => {
    resetTokenCache(); installGoogle(); fcmStatus = 200;
    assert.equal((await fcmPush({ body: new Uint8Array(3000) })).status, 201);
    assert.deepEqual(JSON.parse(calls.at(-1).init.body).message.data, { wake: '1' });
});

test('fcm: unregistered tokens are reported as gone, other failures are not', async () => {
    resetTokenCache(); installGoogle();
    fcmStatus = 404; fcmBody = { error: { status: 'UNREGISTERED' } };
    assert.equal((await fcmPush()).status, 410);
    fcmStatus = 400; fcmBody = { error: { status: 'INVALID_ARGUMENT' } };
    assert.equal((await fcmPush()).status, 502);
    fcmStatus = 429; fcmBody = {};
    assert.equal((await fcmPush()).status, 429);
    fcmStatus = 200;
});

test('fcm: refuses bad requests and stays off without a service account', async () => {
    resetTokenCache(); installGoogle();
    assert.equal((await fcmPush({ env: {} })).status, 404);
    assert.equal((await fcmPush({ token: 'short' })).status, 403);
    assert.equal((await fcmPush({ token: 'has spaces in it but is long enough' })).status, 403);
    assert.equal((await fcmPush({ headers: { encryption: 'rs=4096' } })).status, 400);
    assert.equal((await fcmPush({ headers: { 'crypto-key': 'p256ecdsa=x' } })).status, 400);
    assert.equal((await fcmPush({ body: new Uint8Array(0) })).status, 400);
    assert.equal((await fcmPush({ body: new Uint8Array(9000) })).status, 413);
    assert.equal(calls.length, 0);
    assert.equal(headerParam('keyid=a;dh="quoted"', 'dh'), 'quoted');
});
