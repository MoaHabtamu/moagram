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
    private static final long HEARTBEAT_INTERVAL_MS = 3 * 60 * 1000L;

    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isKeepAliveEnabled()) {
                stopSelf();
                return;
            }
            ensurePushConnectionsAlive();
            scheduleWakeupAlarm();
            AndroidUtilities.runOnUIThread(this, HEARTBEAT_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundCompat();
        ApplicationLoader.postInitApplication();
        ensurePushConnectionsAlive();
        scheduleHeartbeat();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundCompat();
        ApplicationLoader.postInitApplication();
        ensurePushConnectionsAlive();
        scheduleHeartbeat();
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
            scheduleWakeupAlarm(5000L);
            sendStartBroadcast();
        }
    }

    @Override
    public void onTimeout(int startId) {
        if (isKeepAliveEnabled()) {
            scheduleWakeupAlarm(1000L);
            sendStartBroadcast();
        }
        stopSelf(startId);
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        if (isKeepAliveEnabled()) {
            scheduleWakeupAlarm(1000L);
            sendStartBroadcast();
        }
        stopSelf(startId);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        AndroidUtilities.cancelRunOnUIThread(heartbeatRunnable);
        if (isKeepAliveEnabled()) {
            scheduleWakeupAlarm(5000L);
            sendStartBroadcast();
        } else {
            cancelWakeupAlarm();
        }
    }

    private static boolean isKeepAliveEnabled() {
        SharedPreferences preferences = MessagesController.getGlobalNotificationsSettings();
        return preferences.getBoolean("pushService", true);
    }

    private void scheduleHeartbeat() {
        AndroidUtilities.cancelRunOnUIThread(heartbeatRunnable);
        AndroidUtilities.runOnUIThread(heartbeatRunnable, HEARTBEAT_INTERVAL_MS);
        scheduleWakeupAlarm();
    }

    private void ensurePushConnectionsAlive() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                ConnectionsManager cm = ConnectionsManager.getInstance(a);
                boolean pushEnabled = cm.isPushConnectionEnabled();
                cm.setPushConnectionEnabled(pushEnabled);
                if (pushEnabled) {
                    cm.checkConnection();
                    cm.resumeNetworkMaybe();
                }
            }
        }
    }

    private void sendStartBroadcast() {
        try {
            Intent intent = new Intent("org.telegram.start");
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
        } catch (Throwable ignore) {
        }
    }

    private void scheduleWakeupAlarm() {
        scheduleWakeupAlarm(HEARTBEAT_INTERVAL_MS);
    }

    private void scheduleWakeupAlarm(long delayMs) {
        try {
            AlarmManager alarms = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (alarms == null) {
                return;
            }
            PendingIntent pendingIntent = getWakeupPendingIntent();
            long triggerAt = SystemClock.elapsedRealtime() + delayMs;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent);
            } else {
                alarms.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent);
            }
        } catch (Throwable ignore) {
        }
    }

    private void cancelWakeupAlarm() {
        try {
            AlarmManager alarms = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (alarms != null) {
                alarms.cancel(getWakeupPendingIntent());
            }
        } catch (Throwable ignore) {
        }
    }

    private PendingIntent getWakeupPendingIntent() {
        Intent intent = new Intent(this, AppStartReceiver.class);
        intent.setAction("org.telegram.start");
        intent.setPackage(getPackageName());
        return PendingIntent.getBroadcast(
                this,
                ALARM_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private void startForegroundCompat() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                String channelName = "Keep-Alive Service";
                try {
                    String localized = LocaleController.getString("NotificationsService", R.string.NotificationsService);
                    if (localized != null && !localized.isEmpty()) {
                        channelName = localized;
                    }
                } catch (Throwable ignore) {
                }
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_MIN);
                channel.setSound(null, null);
                channel.enableVibration(false);
                channel.enableLights(false);
                channel.setShowBadge(false);
                nm.createNotificationChannel(channel);
            }

            Intent openIntent = new Intent(this, LaunchActivity.class);
            openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent contentIntent = PendingIntent.getActivity(
                    this,
                    0,
                    openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            String title = "Moagram";
            String text = "Background connection enabled for instant notifications";
            try {
                String t = LocaleController.getString("NotificationsService", R.string.NotificationsService);
                String s = LocaleController.getString("NotificationsServiceConnectionInfo", R.string.NotificationsServiceConnectionInfo);
                if (t != null && !t.isEmpty()) {
                    title = t;
                }
                if (s != null && !s.isEmpty()) {
                    text = s;
                }
            } catch (Throwable ignore) {
            }

            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.notification)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setPriority(NotificationCompat.PRIORITY_MIN)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .setOngoing(true)
                    .setSilent(true)
                    .setShowWhen(false)
                    .setContentIntent(contentIntent)
                    .build();

            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE | ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
                );
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Throwable e) {
            try {
                Notification fallback = new NotificationCompat.Builder(this, CHANNEL_ID)
                        .setSmallIcon(R.drawable.notification)
                        .setPriority(NotificationCompat.PRIORITY_MIN)
                        .setSilent(true)
                        .build();
                startForeground(NOTIFICATION_ID, fallback);
            } catch (Throwable ignore) {
            }
        }
    }
}
