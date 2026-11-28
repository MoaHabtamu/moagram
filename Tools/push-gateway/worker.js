// SPDX-License-Identifier: GPL-2.0-or-later
//
// Moagram web push relay (Cloudflare Worker).
//
// Telegram sends web pushes (account.registerDevice token_type = 10) with the
// legacy "aesgcm" encoding, which keeps the salt and server key in the
// Encryption and Crypto-Key headers. UnifiedPush distributors such as ntfy only
// deliver the body, so this relay copies those two headers into the body and
// forwards it to the device's UnifiedPush endpoint:
//
//   aesgcm\n
//   Encryption: <header>\n
//   Crypto-Key: <header>\n
//   <ciphertext>
//
// The relay never sees plaintext: payloads are encrypted for the device's own
// key, and the inner payload is additionally MTProto-encrypted by Telegram.
//
// Routes:
//   POST /aesgcm?e=<UnifiedPush endpoint URL>   relay one push to a UnifiedPush distributor
//   POST /fcm?t=<FCM registration token>        relay one push through Firebase Cloud Messaging
//   GET  /                                      health check
//
// Environment (wrangler.toml [vars], the dashboard, or `wrangler secret put`):
//   ALLOWED_HOSTS        comma-separated UnifiedPush hosts, e.g. "ntfy.sh". When set,
//                        pushes to any other host are refused (stops open-relay use).
//   FCM_SERVICE_ACCOUNT  secret: the Firebase service account JSON. Enables /fcm.
//
// /fcm sends a data message {s: salt, d: server key, r: record size, c: ciphertext}
// with every value base64url. The ciphertext stays encrypted for the device; Google
// only ever sees it encrypted. Pushes too big for FCM's 4 KiB limit become a
// content-free {wake: "1"} message, and the app fetches updates itself.

const MAX_BODY_BYTES = 8192;          // Telegram pushes are well under 4 KiB.
const MAX_HEADER_CHARS = 512;
const DEFAULT_TTL = "86400";
const FCM_MAX_DATA_CHARS = 3800;      // FCM allows 4096 bytes of data including keys.
const FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
const GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token";

function isUnsafeIPv4(host) {
  const m = host.match(/^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/);
  if (!m) return false;
  const [a, b, c, d] = m.slice(1).map(Number);
  if ([a, b, c, d].some((n) => n > 255)) return true;
  return a === 0 || a === 10 || a === 127 || a >= 224 ||
    (a === 100 && b >= 64 && b <= 127) ||
    (a === 169 && b === 254) ||
    (a === 172 && b >= 16 && b <= 31) ||
    (a === 192 && b === 168);
}

function isUnsafeHost(host) {
  const h = host.toLowerCase();
  if (h === "localhost" || h.endsWith(".localhost") || h.endsWith(".internal") || h.endsWith(".local")) {
    return true;
  }
  if (h.startsWith("[")) return true;   // no IPv6 literals; real push services use hostnames
  return isUnsafeIPv4(h);
}

export function parseEndpoint(raw, allowedHosts) {
  if (!raw) return null;
  let url;
  try {
    url = new URL(raw);
  } catch {
    return null;
  }
  if (url.protocol !== "https:" || url.username || url.password || !url.hostname) return null;
  if (isUnsafeHost(url.hostname)) return null;
  if (allowedHosts.length && !allowedHosts.includes(url.hostname.toLowerCase())) return null;
  return url;
}

function allowedHostsFrom(env) {
  return String((env && env.ALLOWED_HOSTS) || "")
    .split(",")
    .map((h) => h.trim().toLowerCase())
    .filter(Boolean);
}

function cleanHeader(value) {
  // Header values become lines in the body: refuse anything that could add lines.
  if (!value || value.length > MAX_HEADER_CHARS || /[\r\n]/.test(value)) return null;
  return value;
}

async function relay(request, env) {
  const url = new URL(request.url);
  const target = parseEndpoint(url.searchParams.get("e"), allowedHostsFrom(env));
  if (!target) return new Response("bad endpoint", { status: 403 });

  const encryption = cleanHeader(request.headers.get("encryption"));
  const cryptoKey = cleanHeader(request.headers.get("crypto-key"));
  if (!encryption || !cryptoKey) return new Response("missing aesgcm headers", { status: 400 });

  const declared = Number(request.headers.get("content-length") || 0);
  if (declared > MAX_BODY_BYTES) return new Response("too large", { status: 413 });
  const ciphertext = new Uint8Array(await request.arrayBuffer());
  if (ciphertext.length === 0) return new Response("empty body", { status: 400 });
  if (ciphertext.length > MAX_BODY_BYTES) return new Response("too large", { status: 413 });

  const prefix = new TextEncoder().encode(`aesgcm\nEncryption: ${encryption}\nCrypto-Key: ${cryptoKey}\n`);
  const body = new Uint8Array(prefix.length + ciphertext.length);
  body.set(prefix, 0);
  body.set(ciphertext, prefix.length);

  const ttl = /^\d{1,8}$/.test(request.headers.get("ttl") || "") ? request.headers.get("ttl") : DEFAULT_TTL;
  let upstream;
  try {
    upstream = await fetch(target.toString(), {
      method: "POST",
      headers: {
        "Content-Type": "application/octet-stream",
        "TTL": ttl,
        "Urgency": request.headers.get("urgency") || "high",
      },
      body,
      redirect: "manual",
    });
  } catch {
    return new Response("upstream unreachable", { status: 502 });
  }

  if (upstream.status >= 200 && upstream.status < 300) {
    // Web push servers answer 201 Created; normalise so Telegram does not back off.
    return new Response(null, { status: 201, headers: { "Location": target.toString() } });
  }
  // 404/410 tell Telegram the subscription is gone, so it stops pushing to it.
  if (upstream.status === 404 || upstream.status === 410) {
    return new Response(null, { status: upstream.status });
  }
  if (upstream.status === 429) {
    const headers = {};
    const retry = upstream.headers.get("retry-after");
    if (retry) headers["Retry-After"] = retry;
    return new Response(null, { status: 429, headers });
  }
  return new Response(null, { status: 502 });
}

// ---------------------------------------------------------------- FCM route

export function headerParam(header, name) {
  for (const part of String(header || "").split(/[,;]/)) {
    const eq = part.indexOf("=");
    if (eq > 0 && part.slice(0, eq).trim().toLowerCase() === name) {
      return part.slice(eq + 1).trim().replace(/^"(.*)"$/, "$1");
    }
  }
  return null;
}

function base64url(bytes) {
  let s = "";
  for (let i = 0; i < bytes.length; i += 0x8000) {
    s += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  }
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function pemToDer(pem) {
  const b64 = pem.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  return Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
}

// One OAuth token per isolate, refreshed five minutes before it expires.
let cachedToken = null;

export function resetTokenCache() {
  cachedToken = null;
}

async function googleAccessToken(account) {
  const now = Math.floor(Date.now() / 1000);
  if (cachedToken && cachedToken.email === account.client_email && cachedToken.expires > now + 300) {
    return cachedToken.token;
  }
  const enc = new TextEncoder();
  const header = base64url(enc.encode(JSON.stringify({ alg: "RS256", typ: "JWT" })));
  const claims = base64url(enc.encode(JSON.stringify({
    iss: account.client_email,
    scope: FCM_SCOPE,
    aud: GOOGLE_TOKEN_URL,
    iat: now,
    exp: now + 3600,
  })));
  const key = await crypto.subtle.importKey(
    "pkcs8", pemToDer(account.private_key),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const signature = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, enc.encode(`${header}.${claims}`)));
  const res = await fetch(GOOGLE_TOKEN_URL, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: `grant_type=${encodeURIComponent("urn:ietf:params:oauth:grant-type:jwt-bearer")}&assertion=${header}.${claims}.${base64url(signature)}`,
  });
  if (!res.ok) throw new Error(`google token ${res.status}`);
  const json = await res.json();
  cachedToken = { email: account.client_email, token: json.access_token, expires: now + Number(json.expires_in || 3600) };
  return cachedToken.token;
}

async function relayFcm(request, env) {
  let account;
  try {
    account = JSON.parse(env && env.FCM_SERVICE_ACCOUNT || "null");
  } catch {
    account = null;
  }
  if (!account || !account.client_email || !account.private_key || !account.project_id) {
    return new Response("fcm not configured", { status: 404 });
  }
  const url = new URL(request.url);
  const fcmToken = url.searchParams.get("t") || "";
  if (!/^[A-Za-z0-9_:\-.]{20,4096}$/.test(fcmToken)) return new Response("bad token", { status: 403 });

  const encryption = cleanHeader(request.headers.get("encryption"));
  const cryptoKey = cleanHeader(request.headers.get("crypto-key"));
  const salt = headerParam(encryption, "salt");
  const dh = headerParam(cryptoKey, "dh");
  const rs = headerParam(encryption, "rs");
  if (!salt || !dh || (rs !== null && !/^\d{1,6}$/.test(rs))) {
    return new Response("missing aesgcm headers", { status: 400 });
  }
  const declared = Number(request.headers.get("content-length") || 0);
  if (declared > MAX_BODY_BYTES) return new Response("too large", { status: 413 });
  const ciphertext = new Uint8Array(await request.arrayBuffer());
  if (ciphertext.length === 0) return new Response("empty body", { status: 400 });
  if (ciphertext.length > MAX_BODY_BYTES) return new Response("too large", { status: 413 });

  let data = { s: salt, d: dh, c: base64url(ciphertext) };
  if (rs !== null) data.r = rs;
  const size = Object.entries(data).reduce((n, [k, v]) => n + k.length + v.length, 0);
  if (size > FCM_MAX_DATA_CHARS) data = { wake: "1" };

  const ttl = /^\d{1,8}$/.test(request.headers.get("ttl") || "") ? request.headers.get("ttl") : DEFAULT_TTL;
  let upstream;
  try {
    const accessToken = await googleAccessToken(account);
    upstream = await fetch(`https://fcm.googleapis.com/v1/projects/${encodeURIComponent(account.project_id)}/messages:send`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "Authorization": `Bearer ${accessToken}` },
      body: JSON.stringify({
        message: { token: fcmToken, data, android: { priority: "HIGH", ttl: `${ttl}s` } },
      }),
    });
  } catch {
    return new Response("upstream unreachable", { status: 502 });
  }
  if (upstream.ok) return new Response(null, { status: 201 });

  let status = "";
  try {
    status = (await upstream.json()).error.status || "";
  } catch {
    // keep empty
  }
  // The app was uninstalled or the token rotated: tell Telegram to drop it.
  // (INVALID_ARGUMENT is not mapped: it could also be a relay bug, and a 410 would
  // make Telegram forget a working device.)
  if (upstream.status === 404 || status === "UNREGISTERED") {
    return new Response(null, { status: 410 });
  }
  if (upstream.status === 429) return new Response(null, { status: 429 });
  return new Response(null, { status: 502 });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "POST" && url.pathname === "/aesgcm") return relay(request, env);
    if (request.method === "POST" && url.pathname === "/fcm") return relayFcm(request, env);
    if (request.method === "GET" && url.pathname === "/") return new Response("moagram push relay\n");
    return new Response(null, { status: 404 });
  },
};
