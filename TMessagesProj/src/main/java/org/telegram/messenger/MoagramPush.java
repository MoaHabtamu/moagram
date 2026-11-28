/*
 * This is the source code of Moagram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONObject;
import org.telegram.tgnet.ConnectionsManager;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Moagram's push notifications without Telegram's Firebase project.
 *
 * <p>Moagram registers a Telegram web push token ({@code token_type = 10}) whose
 * endpoint is Moagram's relay (Tools/push-gateway). Telegram encrypts each push
 * for the keys in that token; the relay forwards it, still encrypted, through one
 * of two transports:
 * <ul>
 *   <li>{@link MoagramFcmPush}: Firebase Cloud Messaging with Moagram's own Firebase
 *       project, so Google Play services wakes Moagram like regular Telegram;</li>
 *   <li>{@link MoagramUnifiedPush}: a UnifiedPush distributor such as ntfy, for
 *       phones without Google Play services.</li>
 * </ul>
 * While either works, the always-on {@link NotificationsService} is not needed and
 * Android can close Moagram like any other app.
 */
public final class MoagramPush {

    public static final String TRANSPORT_FCM = "fcm";
    public static final String TRANSPORT_UNIFIEDPUSH = "unifiedpush";

    private static final String PREFS = "moagram_push";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_ACTIVE = "active";
    private static final String KEY_TRANSPORT = "transport";
    private static final String KEY_PRIVATE = "wp_private";
    private static final String KEY_PUBLIC = "wp_public";
    private static final String KEY_AUTH = "wp_auth";

    private static volatile MoagramWebPushCrypto.Keys keys;

    private MoagramPush() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The relay URL baked in at build time, without a trailing slash. */
    public static String gatewayUrl() {
        String url = BuildConfig.MOAGRAM_PUSH_GATEWAY == null ? "" : BuildConfig.MOAGRAM_PUSH_GATEWAY.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    public static boolean isConfigured() {
        return !TextUtils.isEmpty(gatewayUrl());
    }

    public static boolean isUserEnabled() {
        return prefs().getBoolean(KEY_ENABLED, true);
    }

    /** True when some push transport could work on this phone. */
    public static boolean isAvailable() {
        return MoagramFcmPush.shouldUse() || MoagramUnifiedPush.shouldUse();
    }

    /** The transport Telegram last accepted a token for, or null. */
    public static String activeTransport() {
        if (ApplicationLoader.applicationContext == null || !prefs().getBoolean(KEY_ACTIVE, false)) {
            return null;
        }
        return prefs().getString(KEY_TRANSPORT, null);
    }

    /** True once Telegram accepted a web push token and that transport still works. */
    public static boolean isActive() {
        String transport = activeTransport();
        if (TRANSPORT_FCM.equals(transport)) {
            return MoagramFcmPush.shouldUse();
        }
        if (TRANSPORT_UNIFIEDPUSH.equals(transport)) {
            return MoagramUnifiedPush.isLinked();
        }
        return false;
    }

    /** Called when Telegram accepts a web push registration. */
    static void onRegistered() {
        PushListenerController.IPushListenerServiceProvider provider = ApplicationLoader.getPushProvider();
        setActive(provider == MoagramFcmPush.INSTANCE ? TRANSPORT_FCM
                : provider == MoagramUnifiedPush.INSTANCE ? TRANSPORT_UNIFIEDPUSH : null);
    }

    static void setActive(String transport) {
        boolean active = transport != null;
        SharedPreferences p = prefs();
        boolean changed = p.getBoolean(KEY_ACTIVE, false) != active
                || !TextUtils.equals(p.getString(KEY_TRANSPORT, null), transport);
        p.edit().putBoolean(KEY_ACTIVE, active).putString(KEY_TRANSPORT, transport).apply();
        if (changed) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("Moagram push transport = " + transport);
            }
            // Stops the keep-alive service once push works, restarts it if push is lost.
            AndroidUtilities.runOnUIThread(ApplicationLoader::startPushService);
        }
    }

    /** Push stopped working: fall back to the keep-alive service. */
    static void onLost(String transport) {
        if (transport != null && !transport.equals(prefs().getString(KEY_TRANSPORT, null))) {
            return;
        }
        SharedConfig.pushStringStatus = "__MOAGRAM_PUSH_FAILED__";
        setActive(null);
    }

    /** Called from the settings screen. */
    public static void setUserEnabled(boolean enabled) {
        prefs().edit().putBoolean(KEY_ENABLED, enabled).apply();
        if (!enabled) {
            MoagramUnifiedPush.unregister();
            setActive(null);
        }
        ApplicationLoader.refreshPushProvider();
    }

    static synchronized MoagramWebPushCrypto.Keys ensureKeys() {
        if (keys != null && keys.isValid()) {
            return keys;
        }
        SharedPreferences p = prefs();
        try {
            String priv = p.getString(KEY_PRIVATE, null);
            String pub = p.getString(KEY_PUBLIC, null);
            String auth = p.getString(KEY_AUTH, null);
            if (priv != null && pub != null && auth != null) {
                MoagramWebPushCrypto.Keys stored = new MoagramWebPushCrypto.Keys(
                        MoagramWebPushCrypto.fromBase64Url(priv),
                        MoagramWebPushCrypto.fromBase64Url(pub),
                        MoagramWebPushCrypto.fromBase64Url(auth));
                if (stored.isValid()) {
                    return keys = stored;
                }
            }
            MoagramWebPushCrypto.Keys fresh = MoagramWebPushCrypto.generateKeys();
            p.edit()
                    .putString(KEY_PRIVATE, MoagramWebPushCrypto.base64Url(fresh.privateKeyPkcs8))
                    .putString(KEY_PUBLIC, MoagramWebPushCrypto.base64Url(fresh.publicKeyRaw))
                    .putString(KEY_AUTH, MoagramWebPushCrypto.base64Url(fresh.authSecret))
                    .commit();
            return keys = fresh;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    /**
     * Builds the token Telegram stores for {@code account.registerDevice(token_type = 10)}.
     *
     * @param route relay route, "aesgcm" or "fcm"
     * @param param relay query parameter name, "e" or "t"
     * @param value the UnifiedPush endpoint or FCM token
     */
    static String buildToken(String route, String param, String value) throws Exception {
        MoagramWebPushCrypto.Keys k = ensureKeys();
        if (k == null) {
            throw new IllegalStateException("no web push keys");
        }
        JSONObject jsonKeys = new JSONObject();
        jsonKeys.put("p256dh", MoagramWebPushCrypto.base64Url(k.publicKeyRaw));
        jsonKeys.put("auth", MoagramWebPushCrypto.base64Url(k.authSecret));
        JSONObject token = new JSONObject();
        token.put("endpoint", gatewayUrl() + "/" + route + "?" + param + "=" + URLEncoder.encode(value, "UTF-8"));
        token.put("keys", jsonKeys);
        return token.toString();
    }

    /**
     * Handles a decrypted web push. Blocks until the notification is built, so it must
     * run off the main thread.
     */
    static void onPlaintext(byte[] plaintext) {
        String payload = null;
        try {
            payload = new JSONObject(new String(plaintext, StandardCharsets.UTF_8)).optString("p", null);
        } catch (Exception e) {
            FileLog.e(e);
        }
        if (TextUtils.isEmpty(payload)) {
            wakeUpForUpdates();
            return;
        }
        // Same MTProto-encrypted payload Telegram's own Firebase pushes carry in data["p"].
        PushListenerController.processRemoteMessage(PushListenerController.PUSH_TYPE_WEB,
                payload, System.currentTimeMillis());
    }

    /** Content-free push (or one we could not read): fetch updates over MTProto instead. */
    static void wakeUpForUpdates() {
        AndroidUtilities.runOnUIThread(() -> {
            ApplicationLoader.postInitApplication();
            Utilities.stageQueue.postRunnable(() -> {
                for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                    if (UserConfig.getInstance(a).isClientActivated()) {
                        ConnectionsManager.onInternalPushReceived(a);
                        ConnectionsManager.getInstance(a).resumeNetworkMaybe();
                    }
                }
            });
        });
    }
}
