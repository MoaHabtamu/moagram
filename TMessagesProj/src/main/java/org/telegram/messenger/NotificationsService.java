/*
 * This is the source code of Telegram for Android v. 1.3.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import androidx.core.app.NotificationCompat;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.LaunchActivity;

public class NotificationsService extends Service {

    private static final String CHANNEL_ID = "moagram_keep_alive_service";
    private static final int NOTIFICATION_ID = 723699;
    private static final int ALARM_REQUEST_CODE = 723699;
    // Used only when no foreground service is running to recover the process.
    private static final long RECOVERY_ALARM_INTERVAL_MS = 15 * 60 * 1000L;

    private boolean foregroundStarted;
    private boolean localizedForeground;

    @Override
    public void onCreate() {
        super.onCreate();
        // Promote before account/database initialization, which can exceed the
        // startForegroundService deadline on a cold start.
        foregroundStarted = startForegroundCompat(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!isKeepAliveEnabled()) {
            cancelWakeupAlarm(this);
            stopSelf();
            return START_NOT_STICKY;
        }
        ApplicationLoader.postInitApplication();
        if (foregroundStarted && !localizedForeground) {
            foregroundStarted = startForegroundCompat(true);
            localizedForeground = foregroundStarted;
        }
        if (!foregroundStarted) {
            onPushServiceTick(true, true);
            stopSelf();
            return START_NOT_STICKY;
        }
        // Android owns restarting this sticky foreground service, and tgnet's
        // native push socket stays alive while networkPaused is true. Do not
        // run a periodic Java wakeup loop or call resumeNetworkMaybe() here.
        cancelWakeupAlarm(this);
        ensurePushConnectionsAlive(false);
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
            scheduleWakeupAlarm(this, RECOVERY_ALARM_INTERVAL_MS);
        }
    }

    @Override
    public void onTimeout(int startId) {
        stopSelf();
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        stopSelf();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        localizedForeground = false;
        stopForeground(true);
        if (isKeepAliveEnabled()) {
            scheduleWakeupAlarm(this, RECOVERY_ALARM_INTERVAL_MS);
        } else {
            cancelWakeupAlarm(this);
        }
    }

    public static void onPushServiceTick(boolean enabled, boolean fromBackgroundWakeup) {
        Context context = ApplicationLoader.applicationContext;
        if (!enabled) {
            if (context != null) {
                cancelWakeupAlarm(context);
            }
            return;
        }
        ApplicationLoader.postInitApplication();
        ensurePushConnectionsAlive(fromBackgroundWakeup);
        if (context != null) {
            scheduleWakeupAlarm(context, RECOVERY_ALARM_INTERVAL_MS);
        }
    }

    public static boolean isKeepAliveEnabled() {
        SharedPreferences preferences = MessagesController.getGlobalNotificationsSettings();
        if (preferences.contains("pushService")) {
            return preferences.getBoolean("pushService", true);
        }
        return MessagesController.getMainSettings(UserConfig.selectedAccount).getBoolean("keepAliveService", true);
    }

    private static void ensurePushConnectionsAlive(boolean wakeGenericNetwork) {
        boolean startKeepAliveJob = wakeGenericNetwork;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                ConnectionsManager cm = ConnectionsManager.getInstance(a);
                boolean pushEnabled = cm.isPushConnectionEnabled();
                cm.setPushConnectionEnabled(pushEnabled);
                if (pushEnabled && wakeGenericNetwork) {
                    if (startKeepAliveJob) {
                        KeepAliveJob.startJob();
                        startKeepAliveJob = false;
                    }
                    cm.checkConnection();
                    cm.resumeNetworkMaybe();
                }
            }
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
        } catch (RuntimeException e) {
            FileLog.e(e);
        }
    }

    private static void cancelWakeupAlarm(Context context) {
        try {
            AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarms != null) {
                alarms.cancel(getWakeupPendingIntent(context));
            }
        } catch (RuntimeException e) {
            FileLog.e(e);
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

    private boolean startForegroundCompat(boolean localized) {
        try {
            // Release builds store translations in Telegram's assets and strip
            // Android string resources. Use bootstrap text until LocaleController
            // is initialized, then replace it with the user's translations.
            String title = localized ? LocaleController.getString(R.string.NotificationsService) : "Moagram";
            String text = localized ? LocaleController.getString(R.string.MoagramBackgroundConnectionActive)
                    : "Background connection active for incoming messages";
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                        title, NotificationManager.IMPORTANCE_LOW);
                channel.setSound(null, null);
                channel.enableVibration(false);
                channel.setShowBadge(false);
                nm.createNotificationChannel(channel);
            }

            Intent openIntent = new Intent(this, LaunchActivity.class);
            openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent contentIntent = PendingIntent.getActivity(this, NOTIFICATION_ID, openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.notification)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setContentIntent(contentIntent)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    // Android 13+ allows dismissing foreground-service notices
                    // without stopping the service. Older versions may pin them.
                    .setOngoing(false)
                    .setSilent(true)
                    .setShowWhen(false)
                    .build();
            if (Build.VERSION.SDK_INT >= 34) {
                // A persistent incoming-message connection is not a finite
                // data sync or a relay between devices (remoteMessaging).
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            return true;
        } catch (RuntimeException e) {
            FileLog.e(e);
            return false;
        }
    }
}
