package org.telegram.messenger;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

import androidx.core.app.NotificationCompat;

import org.telegram.messenger.support.JobIntentService;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.LaunchActivity;

/**
 * Wakes Moagram about every twelve hours so free-account repeats can refill their
 * seven-message queue while the app is closed. Missed dates stay skipped.
 * A paused series posts a notification that opens Manage Repeats.
 */
public final class LocalRepeatRefill {
    private static final int JOB_ID = 1001;
    private static final int NOTIFICATION_BASE = 723700;
    private static final String CHANNEL = "moagram_repeats";
    private static final long SESSION_MS = 90_000L;
    /** Avoid posting the same paused-rule notice again on every refill. */
    private static final String[] posted = new String[UserConfig.MAX_ACCOUNT_COUNT];

    private LocalRepeatRefill() {}

    public static void schedule() {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) return;
        long delay = 0;
        boolean wake = false;
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            if (!UserConfig.getInstance(account).isClientActivated()) {
                cancelNotification(context, account);
                continue;
            }
            LocalRepeatController controller = LocalRepeatController.getInstance(account);
            syncNotification(context, account, controller);
            long next = controller.nextDelayMillis();
            if (next > 0) {
                wake = true;
                delay = delay == 0 ? next : Math.min(delay, next);
            }
        }
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pending = pending(context);
        try {
            if (!wake || alarms == null) {
                if (alarms != null) alarms.cancel(pending);
                return;
            }
            long when = SystemClock.elapsedRealtime() + delay;
            if (Build.VERSION.SDK_INT >= 23) {
                alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, when, pending);
            } else {
                alarms.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, when, pending);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static void enqueue(Context context) {
        if (context == null) return;
        try {
            JobIntentService.enqueueWork(context, Job.class, JOB_ID, new Intent());
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    static void runSession() {
        // A fresh wakeup may remind the user if a rule is still paused.
        for (int account = 0; account < posted.length; account++) posted[account] = null;
        long deadline = SystemClock.elapsedRealtime() + SESSION_MS;
        boolean force = true;
        while (SystemClock.elapsedRealtime() < deadline) {
            boolean busy = false;
            for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
                if (!UserConfig.getInstance(account).isClientActivated()) continue;
                LocalRepeatController controller = LocalRepeatController.getInstance(account);
                if (!controller.needsWake()) continue;
                if (!force && !controller.needsImmediate()) continue;
                ConnectionsManager.getInstance(account).resumeNetworkMaybe();
                controller.tick();
                busy = true;
            }
            force = false;
            if (!busy) break;
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                break;
            }
        }
        schedule();
    }

    private static void syncNotification(Context context, int account, LocalRepeatController controller) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        long dialogId = controller.pausedDialog();
        if (dialogId == 0) {
            cancelNotification(context, account);
            return;
        }
        int count = controller.pausedCount();
        String signature = dialogId + ":" + count;
        if (signature.equals(posted[account])) return;
        ensureChannel(manager);
        String text = count > 1
                ? LocaleController.formatString(R.string.LocalRepeatRefillTextMany, count)
                : LocaleController.getString(R.string.LocalRepeatRefillText);
        Intent open = new Intent(context, LaunchActivity.class);
        open.setAction("com.tmessages.openchat" + dialogId + "_" + account);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        open.putExtra("currentAccount", account);
        open.putExtra("local_repeats", true);
        if (dialogId > 0) open.putExtra("userId", dialogId);
        else open.putExtra("chatId", -dialogId);
        PendingIntent content = PendingIntent.getActivity(context, NOTIFICATION_BASE + account, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.notification)
                .setContentTitle(LocaleController.getString(R.string.LocalRepeatRefillTitle))
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(content)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build();
        try {
            manager.notify(NOTIFICATION_BASE + account, notification);
            posted[account] = signature;
        } catch (SecurityException e) {
            FileLog.e(e);
        }
    }

    private static void cancelNotification(Context context, int account) {
        posted[account] = null;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(NOTIFICATION_BASE + account);
    }

    private static void ensureChannel(NotificationManager manager) {
        if (Build.VERSION.SDK_INT < 26) return;
        if (manager.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                LocaleController.getString(R.string.LocalRepeatRefillChannel), NotificationManager.IMPORTANCE_DEFAULT);
        channel.setSound(null, null);
        manager.createNotificationChannel(channel);
    }

    private static PendingIntent pending(Context context) {
        Intent intent = new Intent(context, Receiver.class);
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            enqueue(context);
        }
    }

    public static class Job extends JobIntentService {
        @Override
        protected void onHandleWork(Intent intent) {
            runSession();
        }
    }
}
