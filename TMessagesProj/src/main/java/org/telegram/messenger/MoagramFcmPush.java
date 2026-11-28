/*
 * This is the source code of Moagram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.messenger;

import android.os.SystemClock;
import android.text.TextUtils;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;

import java.util.Map;

/**
 * {@link MoagramPush} transport through Firebase Cloud Messaging with Moagram's own
 * Firebase project. Google Play services keeps the only connection, so Moagram sleeps
 * like regular Telegram. Telegram cannot push to this project directly; the relay
 * receives Telegram's web push and forwards the still-encrypted payload to FCM.
 */
public final class MoagramFcmPush implements PushListenerController.IPushListenerServiceProvider {

    public static final MoagramFcmPush INSTANCE = new MoagramFcmPush();

    /** Telegram's own Firebase sender: configs borrowing it cannot receive pushes in Moagram. */
    private static final String TELEGRAM_SENDER_ID = "760348033671";

    private static Boolean ownProject;
    private static Boolean playServices;

    private MoagramFcmPush() {
    }

    /** True when this build carries Moagram's own Firebase project and Play services is present. */
    public static boolean isAvailable() {
        if (playServices == null) {
            try {
                playServices = GoogleApiAvailability.getInstance()
                        .isGooglePlayServicesAvailable(ApplicationLoader.applicationContext) == ConnectionResult.SUCCESS;
            } catch (Throwable e) {
                FileLog.e(e);
                playServices = false;
            }
        }
        if (ownProject == null) {
            try {
                FirebaseApp app = FirebaseApp.initializeApp(ApplicationLoader.applicationContext);
                String sender = app == null ? null : app.getOptions().getGcmSenderId();
                ownProject = !TextUtils.isEmpty(sender) && !TELEGRAM_SENDER_ID.equals(sender);
            } catch (Throwable e) {
                FileLog.e(e);
                ownProject = false;
            }
        }
        return playServices && ownProject;
    }

    public static boolean shouldUse() {
        return MoagramPush.isConfigured() && MoagramPush.isUserEnabled() && isAvailable();
    }

    static boolean isCurrentProvider() {
        return ApplicationLoader.getPushProvider() == INSTANCE;
    }

    /** Registers a (new) FCM token with Telegram as a web push endpoint on the relay. */
    static void registerToken(String fcmToken) {
        Utilities.globalQueue.postRunnable(() -> {
            SharedConfig.pushStringGetTimeEnd = SystemClock.elapsedRealtime();
            try {
                PushListenerController.sendRegistrationToServer(PushListenerController.PUSH_TYPE_WEB,
                        MoagramPush.buildToken("fcm", "t", fcmToken));
            } catch (Exception e) {
                FileLog.e(e);
                MoagramPush.onLost(MoagramPush.TRANSPORT_FCM);
            }
        });
    }

    /**
     * Handles a relay data message: {s: salt, d: server key, r: record size, c: ciphertext}
     * or a content-free {wake: "1"}. Runs on Firebase's worker thread and blocks until the
     * notification is built, like the stock Firebase path.
     *
     * @return false if this is not a Moagram relay message
     */
    static boolean onDataMessage(Map<String, String> data) {
        if (data == null) {
            return false;
        }
        if ("1".equals(data.get("wake"))) {
            MoagramPush.wakeUpForUpdates();
            return true;
        }
        String salt = data.get("s");
        String dh = data.get("d");
        String ciphertext = data.get("c");
        if (salt == null || dh == null || ciphertext == null) {
            return false;
        }
        byte[] plain = null;
        try {
            int recordSize = data.containsKey("r") ? Integer.parseInt(data.get("r")) : 4096;
            plain = MoagramWebPushCrypto.decryptAesgcm(MoagramPush.ensureKeys(),
                    MoagramWebPushCrypto.fromBase64Url(dh), MoagramWebPushCrypto.fromBase64Url(salt),
                    recordSize, MoagramWebPushCrypto.fromBase64Url(ciphertext));
        } catch (Exception e) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("FCM web push decrypt failed: " + e);
            }
        }
        if (plain != null) {
            MoagramPush.onPlaintext(plain);
        } else {
            MoagramPush.wakeUpForUpdates();
        }
        return true;
    }

    @Override
    public boolean hasServices() {
        return shouldUse();
    }

    @Override
    public String getLogTitle() {
        return "Moagram Firebase";
    }

    @Override
    public int getPushType() {
        return PushListenerController.PUSH_TYPE_WEB;
    }

    @Override
    public void onRequestPushToken() {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                SharedConfig.pushStringGetTimeStart = SystemClock.elapsedRealtime();
                if (MoagramPush.ensureKeys() == null) {
                    MoagramPush.onLost(MoagramPush.TRANSPORT_FCM);
                    return;
                }
                FirebaseApp.initializeApp(ApplicationLoader.applicationContext);
                FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
                    String token = task.isSuccessful() ? task.getResult() : null;
                    if (TextUtils.isEmpty(token)) {
                        if (BuildVars.LOGS_ENABLED) {
                            FileLog.d("Moagram FCM token failed: " + task.getException());
                        }
                        MoagramPush.onLost(MoagramPush.TRANSPORT_FCM);
                        PushListenerController.sendRegistrationToServer(getPushType(), null);
                        return;
                    }
                    registerToken(token);
                });
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }
}
