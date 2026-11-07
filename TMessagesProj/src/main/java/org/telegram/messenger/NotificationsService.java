/*
 * This is the source code of Telegram for Android v. 1.3.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import org.telegram.tgnet.ConnectionsManager;

public class NotificationsService extends Service {

    private static final String LEGACY_CHANNEL_ID = "moagram_keep_alive_service";
    private static final int LEGACY_NOTIFICATION_ID = 723699;
    private static final int ALARM_REQUEST_CODE = 723699;
    private static final long HEARTBEAT_INTERVAL_MS = 3 * 60 * 1000L;

    private static final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            Context context = ApplicationLoader.applicationContext;
            if (!isKeepAliveEnabled()) {
                if (context != null) {
                    cancelWakeupAlarm(context);
                }
                return;
            }
            ensurePushConnectionsAlive(false);
            if (context != null) {
                scheduleWakeupAlarm(context, HEARTBEAT_INTERVAL_MS);
            }
            AndroidUtilities.runOnUIThread(this, HEARTBEAT_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        clearLegacyForegroundNotification(this);
        ApplicationLoader.postInitApplication();
        onPushServiceTick(isKeepAliveEnabled(), false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        clearLegacyForegroundNotification(this);
        ApplicationLoader.postInitApplication();
        onPushServiceTick(isKeepAliveEnabled(), false);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        if (isKeepAliveEnabled()) {
            scheduleWakeupAlarm(this, 5000L);
            sendStartBroadcast(this);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        AndroidUtilities.cancelRunOnUIThread(heartbeatRunnable);
        if (isKeepAliveEnabled()) {
            scheduleWakeupAlarm(this, 5000L);
            sendStartBroadcast(this);
        } else {
            cancelWakeupAlarm(this);
        }
    }

    public static void onPushServiceTick(boolean enabled, boolean fromBackgroundWakeup) {
        Context context = ApplicationLoader.applicationContext;
        if (context != null) {
            clearLegacyForegroundNotification(context);
        }
        AndroidUtilities.cancelRunOnUIThread(heartbeatRunnable);
        if (!enabled) {
            if (context != null) {
                cancelWakeupAlarm(context);
            }
            return;
        }
        ensurePushConnectionsAlive(fromBackgroundWakeup);
        if (context != null) {
            scheduleWakeupAlarm(context, HEARTBEAT_INTERVAL_MS);
        }
        AndroidUtilities.runOnUIThread(heartbeatRunnable, HEARTBEAT_INTERVAL_MS);
    }

    private static boolean isKeepAliveEnabled() {
        SharedPreferences preferences = MessagesController.getGlobalNotificationsSettings();
        return preferences.getBoolean("pushService", true);
    }

    private static void ensurePushConnectionsAlive(boolean startKeepAliveJob) {
        boolean anyActive = false;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                ConnectionsManager cm = ConnectionsManager.getInstance(a);
                boolean pushEnabled = cm.isPushConnectionEnabled();
                cm.setPushConnectionEnabled(pushEnabled);
                if (pushEnabled) {
                    anyActive = true;
                    cm.checkConnection();
                    cm.resumeNetworkMaybe();
                }
            }
        }
        if (startKeepAliveJob && anyActive) {
            KeepAliveJob.startJob();
        }
    }

    private static void sendStartBroadcast(Context context) {
        try {
            Intent intent = new Intent("org.telegram.start");
            intent.setPackage(context.getPackageName());
            context.sendBroadcast(intent);
        } catch (Throwable ignore) {
        }
    }

    private static void scheduleWakeupAlarm(Context context, long delayMs) {
        try {
            AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarms == null) {
                return;
            }
            PendingIntent pendingIntent = getWakeupPendingIntent(context);
            long triggerAt = SystemClock.elapsedRealtime() + delayMs;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent);
            } else {
                alarms.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent);
            }
        } catch (Throwable ignore) {
        }
    }

    private static void cancelWakeupAlarm(Context context) {
        try {
            AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarms != null) {
                alarms.cancel(getWakeupPendingIntent(context));
            }
        } catch (Throwable ignore) {
        }
    }

    private static PendingIntent getWakeupPendingIntent(Context context) {
        Intent intent = new Intent(context, AppStartReceiver.class);
        intent.setAction("org.telegram.start");
        intent.setPackage(context.getPackageName());
        return PendingIntent.getBroadcast(
                context,
                ALARM_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static void clearLegacyForegroundNotification(Context context) {
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(LEGACY_NOTIFICATION_ID);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    nm.deleteNotificationChannel(LEGACY_CHANNEL_ID);
                }
            }
        } catch (Throwable ignore) {
        }
    }
}
