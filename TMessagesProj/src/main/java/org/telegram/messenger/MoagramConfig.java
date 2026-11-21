package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.core.app.NotificationManagerCompat;

import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;

/**
 * Isolated app-wide Moagram UX preferences and reliability helpers.
 * Kept separate from SharedConfig to minimize upstream merge conflicts.
 */
public final class MoagramConfig {

    private static final String PREFS_NAME = "moagram_ux_config";
    private static final Object LOCK = new Object();
    private static volatile boolean loaded;

    public static volatile boolean showGhostModeButton = true;
    public static volatile boolean confirmCalls = false;
    public static volatile boolean confirmStickers = false;
    public static volatile boolean confirmGifs = false;
    public static volatile boolean hideStoriesBar = false;
    public static volatile boolean hideGiftButton = false;
    public static volatile boolean disableNextChannelJump = false;
    public static volatile boolean showSecondsInTimestamps = false;
    public static volatile boolean showProfileIds = false;

    private MoagramConfig() {}

    public static void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (LOCK) {
            if (loaded) {
                return;
            }
            Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                return;
            }
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            showGhostModeButton = prefs.getBoolean("showGhostModeButton", true);
            confirmCalls = prefs.getBoolean("confirmCalls", false);
            confirmStickers = prefs.getBoolean("confirmStickers", false);
            confirmGifs = prefs.getBoolean("confirmGifs", false);
            hideStoriesBar = prefs.getBoolean("hideStoriesBar", false);
            hideGiftButton = prefs.getBoolean("hideGiftButton", false);
            disableNextChannelJump = prefs.getBoolean("disableNextChannelJump", false);
            showSecondsInTimestamps = prefs.getBoolean("showSecondsInTimestamps", false);
            showProfileIds = prefs.getBoolean("showProfileIds", false);
            loaded = true;
        }
    }

    public static void resetForTests() {
        synchronized (LOCK) {
            loaded = false;
            showGhostModeButton = true;
            confirmCalls = false;
            confirmStickers = false;
            confirmGifs = false;
            hideStoriesBar = false;
            hideGiftButton = false;
            disableNextChannelJump = false;
            showSecondsInTimestamps = false;
            showProfileIds = false;
        }
    }

    private static void saveAndNotify(String key, boolean value) {
        Context context = ApplicationLoader.applicationContext;
        if (context != null) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(key, value)
                    .apply();
        }
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.moagramConfigChanged);
    }

    public static boolean isShowGhostModeButton() {
        ensureLoaded();
        return showGhostModeButton;
    }

    public static void setShowGhostModeButton(boolean value) {
        ensureLoaded();
        if (showGhostModeButton == value) {
            return;
        }
        showGhostModeButton = value;
        saveAndNotify("showGhostModeButton", value);
    }

    public static boolean isConfirmCalls() {
        ensureLoaded();
        return confirmCalls;
    }

    public static void setConfirmCalls(boolean value) {
        ensureLoaded();
        if (confirmCalls == value) {
            return;
        }
        confirmCalls = value;
        saveAndNotify("confirmCalls", value);
    }

    public static boolean isConfirmStickers() {
        ensureLoaded();
        return confirmStickers;
    }

    public static void setConfirmStickers(boolean value) {
        ensureLoaded();
        if (confirmStickers == value) {
            return;
        }
        confirmStickers = value;
        saveAndNotify("confirmStickers", value);
    }

    public static boolean isConfirmGifs() {
        ensureLoaded();
        return confirmGifs;
    }

    public static void setConfirmGifs(boolean value) {
        ensureLoaded();
        if (confirmGifs == value) {
            return;
        }
        confirmGifs = value;
        saveAndNotify("confirmGifs", value);
    }

    public static boolean isHideStoriesBar() {
        ensureLoaded();
        return hideStoriesBar;
    }

    public static void setHideStoriesBar(boolean value) {
        ensureLoaded();
        if (hideStoriesBar == value) {
            return;
        }
        hideStoriesBar = value;
        saveAndNotify("hideStoriesBar", value);
    }

    public static boolean isHideGiftButton() {
        ensureLoaded();
        return hideGiftButton;
    }

    public static void setHideGiftButton(boolean value) {
        ensureLoaded();
        if (hideGiftButton == value) {
            return;
        }
        hideGiftButton = value;
        saveAndNotify("hideGiftButton", value);
    }

    public static boolean isDisableNextChannelJump() {
        ensureLoaded();
        return disableNextChannelJump;
    }

    public static void setDisableNextChannelJump(boolean value) {
        ensureLoaded();
        if (disableNextChannelJump == value) {
            return;
        }
        disableNextChannelJump = value;
        saveAndNotify("disableNextChannelJump", value);
    }

    public static boolean isShowSecondsInTimestamps() {
        ensureLoaded();
        return showSecondsInTimestamps;
    }

    public static void setShowSecondsInTimestamps(boolean value) {
        ensureLoaded();
        if (showSecondsInTimestamps == value) {
            return;
        }
        showSecondsInTimestamps = value;
        saveAndNotify("showSecondsInTimestamps", value);
    }

    public static boolean isShowProfileIds() {
        ensureLoaded();
        return showProfileIds;
    }

    public static void setShowProfileIds(boolean value) {
        ensureLoaded();
        if (showProfileIds == value) {
            return;
        }
        showProfileIds = value;
        saveAndNotify("showProfileIds", value);
    }

    public static int getEnabledUxModCount() {
        ensureLoaded();
        int count = 0;
        if (showGhostModeButton) count++;
        if (confirmCalls) count++;
        if (confirmStickers) count++;
        if (confirmGifs) count++;
        if (hideStoriesBar) count++;
        if (hideGiftButton) count++;
        if (disableNextChannelJump) count++;
        if (showSecondsInTimestamps) count++;
        if (showProfileIds) count++;
        return count;
    }

    public static int getEnabledLocalHistoryCount() {
        int count = 0;
        if (SharedConfig.saveDeletedMessages) count++;
        if (SharedConfig.saveDeletedMessages && SharedConfig.saveDeletedMedia) count++;
        if (SharedConfig.saveEditHistory) count++;
        if (SharedConfig.keepSelfDestructingMedia) count++;
        if (SharedConfig.cacheStoryViewers) count++;
        if (SharedConfig.allowSaveRestrictedMedia) count++;
        if (SharedConfig.ignoreAllRestrictions) count++;
        return count;
    }

    public static boolean isIgnoringBatteryOptimizations(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        try {
            PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return powerManager == null || powerManager.isIgnoringBatteryOptimizations(context.getPackageName());
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static boolean areNotificationsEnabled(Context context) {
        if (context == null) {
            return true;
        }
        try {
            return NotificationManagerCompat.from(context).areNotificationsEnabled();
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static void openBatteryOptimizationSettings(Activity activity) {
        if (activity == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                activity.startActivity(intent);
                return;
            } catch (Throwable ignored) {
            }
        }
        openAppDetailsSettings(activity);
    }

    public static void openNotificationSettings(Activity activity) {
        if (activity == null) {
            return;
        }
        try {
            Intent intent;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName());
            } else {
                intent = new Intent("android.settings.APP_NOTIFICATION_SETTINGS")
                        .putExtra("app_package", activity.getPackageName())
                        .putExtra("app_uid", activity.getApplicationInfo().uid);
            }
            activity.startActivity(intent);
            return;
        } catch (Throwable ignored) {
        }
        openAppDetailsSettings(activity);
    }

    public static void openAppDetailsSettings(Activity activity) {
        if (activity == null) {
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(intent);
        } catch (Throwable ignored) {
        }
    }

    public static void showConfirmDialog(Context context, Theme.ResourcesProvider resourcesProvider,
                                         CharSequence title, CharSequence message,
                                         CharSequence positiveLabel, Runnable onConfirm) {
        if (context == null) {
            if (onConfirm != null) {
                onConfirm.run();
            }
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(title);
        builder.setMessage(message);
        builder.setPositiveButton(positiveLabel, (dialog, which) -> {
            if (onConfirm != null) {
                onConfirm.run();
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        try {
            builder.show();
        } catch (Throwable e) {
            FileLog.e(e);
            if (onConfirm != null) {
                onConfirm.run();
            }
        }
    }
}
