/*
 * This is the source code of Moagram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.messenger;

import android.content.Context;
import android.os.PowerManager;
import android.os.SystemClock;

import org.unifiedpush.android.connector.FailedReason;
import org.unifiedpush.android.connector.PushService;
import org.unifiedpush.android.connector.data.PushEndpoint;
import org.unifiedpush.android.connector.data.PushMessage;

/** Receives endpoints and pushes from the UnifiedPush distributor. */
public class MoagramUnifiedPushService extends PushService {

    private static final long WAKE_LOCK_TIMEOUT_MS = 30_000;
    private static PowerManager.WakeLock wakeLock;

    private static synchronized void acquireWakeLock(Context context) {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) context.getApplicationContext().getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "moagram:unifiedpush");
            wakeLock.setReferenceCounted(true);
        }
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
    }

    private static synchronized void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (RuntimeException ignore) {
            // Already released by its timeout.
        }
    }

    @Override
    public void onNewEndpoint(PushEndpoint endpoint, String instance) {
        final String url = endpoint.getUrl();
        Utilities.globalQueue.postRunnable(() -> {
            SharedConfig.pushStringGetTimeEnd = SystemClock.elapsedRealtime();
            try {
                ApplicationLoader.postInitApplication();
                PushListenerController.sendRegistrationToServer(PushListenerController.PUSH_TYPE_WEB,
                        MoagramPush.buildToken("aesgcm", "e", url));
            } catch (Exception e) {
                FileLog.e(e);
                lost();
            }
        });
    }

    @Override
    public void onMessage(PushMessage message, String instance) {
        acquireWakeLock(this);
        final byte[] body = message.getContent();
        Utilities.globalQueue.postRunnable(() -> {
            try {
                byte[] plain = null;
                if (MoagramWebPushCrypto.isGatewayBody(body)) {
                    try {
                        plain = MoagramWebPushCrypto.decryptGatewayBody(body, MoagramPush.ensureKeys());
                    } catch (Exception e) {
                        if (BuildVars.LOGS_ENABLED) {
                            FileLog.d("UnifiedPush decrypt failed: " + e);
                        }
                    }
                }
                if (plain != null) {
                    MoagramPush.onPlaintext(plain);
                } else {
                    MoagramPush.wakeUpForUpdates();
                }
            } finally {
                releaseWakeLock();
            }
        });
    }

    @Override
    public void onRegistrationFailed(FailedReason reason, String instance) {
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("UnifiedPush registration failed: " + reason);
        }
        lost();
    }

    @Override
    public void onUnregistered(String instance) {
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("UnifiedPush unregistered");
        }
        lost();
    }

    private static void lost() {
        MoagramPush.onLost(MoagramPush.TRANSPORT_UNIFIEDPUSH);
        if (ApplicationLoader.getPushProvider() == MoagramUnifiedPush.INSTANCE) {
            Utilities.globalQueue.postRunnable(() ->
                    PushListenerController.sendRegistrationToServer(PushListenerController.PUSH_TYPE_WEB, null));
        }
    }
}
