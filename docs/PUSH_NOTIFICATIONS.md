# Push notifications without the always-on service

Moagram's package is not registered with Telegram's Firebase project, so Telegram's own Firebase pushes never reach it. Without push, Moagram has to keep a background connection open through an always-on foreground service. Android then treats Moagram as an app on screen: it is never closed, never receives background memory-trim signals, and keeps a few hundred MB of memory and some CPU and radio time busy all day.

Moagram instead registers a Telegram **web push** token and receives pushes through one of two routes, in this order:

1. **Google (Firebase Cloud Messaging)** with Moagram's own Firebase project. Google Play services keeps the only connection, so Moagram sleeps like regular Telegram. Nothing extra to install.
2. **UnifiedPush** (for example [ntfy](https://ntfy.sh)) on phones without Google Play services.
3. Neither available: the always-on background connection, as before.

## How it works

```
                        ┌─▶ /fcm    ─▶ Google FCM ─▶ Play services ─▶ Moagram
Telegram ─web push─▶ relay
                        └─▶ /aesgcm ─▶ ntfy server ─▶ ntfy app ─────▶ Moagram
```

1. Moagram registers `account.registerDevice` with `token_type = 10`. The token holds a relay URL (`/fcm?t=<FCM token>` or `/aesgcm?e=<UnifiedPush endpoint>`) plus Moagram's own P-256 public key and auth secret ([`MoagramPush`](../TMessagesProj/src/main/java/org/telegram/messenger/MoagramPush.java)).
2. Telegram encrypts each push for that key with the legacy `aesgcm` Web Push encoding, which carries the salt and server key in the `Encryption` and `Crypto-Key` headers.
3. The relay ([`Tools/push-gateway/worker.js`](../Tools/push-gateway/worker.js)) forwards the still-encrypted push:
   - `/fcm` signs in to Google with the Firebase service account (a Cloudflare secret) and sends an FCM data message `{s, d, r, c}`: salt, server key, record size, ciphertext. Pushes over FCM's 4 KiB limit become a content-free `{wake: "1"}`.
   - `/aesgcm` copies the two headers into the body for the UnifiedPush distributor, which only delivers bodies.
4. Moagram decrypts the push ([`MoagramWebPushCrypto`](../TMessagesProj/src/main/java/org/telegram/messenger/MoagramWebPushCrypto.java)). Inside is the same MTProto-encrypted payload Telegram's Firebase pushes carry, so the existing `PushListenerController.processRemoteMessage` builds the notification. Anything it cannot decrypt makes it wake up and fetch updates over MTProto instead.
5. Once Telegram accepts the token, `NotificationsService.isKeepAliveEnabled()` returns false and the always-on service stops. If the route stops working, the service comes back.

The relay, Google and ntfy only ever see ciphertext. The relay needs the Firebase key because Telegram cannot send to Moagram's Firebase project itself, and the key must not ship inside the APK.

## Set it up

1. **Firebase project.** In the [Firebase console](https://console.firebase.google.com/), create a project (Analytics off), add an Android app with package `org.moagram.messenger.web`, and download `google-services.json` into `TMessagesProj_AppStandalone/`. Then *Project settings → Service accounts → Generate new private key*.

2. **Relay.** With Node 22+ and `npx wrangler login` done:

   ```bash
   Tools/push-gateway/setup.sh
   cd Tools/push-gateway && npx wrangler secret put FCM_SERVICE_ACCOUNT < ~/Downloads/<project>-firebase-adminsdk-<id>.json
   ```

   Delete the downloaded key afterwards. `wrangler.toml` limits `/aesgcm` to `ntfy.sh`; add hosts to `ALLOWED_HOSTS` for other UnifiedPush servers.

3. **Build.** Add the relay address to `local.properties` and rebuild:

   ```properties
   MOAGRAM_PUSH_GATEWAY=https://moagram-push.YOUR-SUBDOMAIN.workers.dev
   ```

   Builds without it keep the old behaviour.

4. **Check.** *Settings → Moagram Settings → Push Notifications* shows *Active via Google* (or *via ntfy*), and *Keep-Alive* shows *Not needed*. The same row turns push off again.

## Checks

```bash
python3 Tools/tests/test-webpush-crypto.py       # decrypts pushes encrypted independently in Python
node --test Tools/tests/test-push-gateway.mjs    # both relay routes, Google sign-in, error mapping
python3 Tools/tests/test-notification-service.py # keep-alive turns off while push works
```

## Limits

- Every push is one Worker request; Cloudflare's free plan allows 100,000 a day.
- Telegram's `token_type = 4` (simple push) is not registered; content-free events are handled by the wake-up fallback.
- If the UnifiedPush app is uninstalled while Moagram is closed, Moagram notices only when it next starts.
