package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

/** Account-scoped ghost mode preferences. */
public class GhostModeController {

    private static final GhostModeController[] instances = new GhostModeController[UserConfig.MAX_ACCOUNT_COUNT];

    public static GhostModeController getInstance(int account) {
        GhostModeController result = instances[account];
        if (result == null) {
            synchronized (instances) {
                result = instances[account];
                if (result == null) {
                    instances[account] = result = new GhostModeController(account);
                }
            }
        }
        return result;
    }

    public boolean enabled;
    public boolean hideOnline = true;
    public boolean hideTyping = true;
    public boolean ghostRead = true;
    public boolean markOnReply = true;
    public boolean hideStories = true;
    public boolean stealthSend = true;

    private final SharedPreferences preferences;

    private GhostModeController(int account) {
        preferences = ApplicationLoader.applicationContext.getSharedPreferences("ghost_mode_account_" + account, Context.MODE_PRIVATE);
        SharedPreferences global = ApplicationLoader.applicationContext.getSharedPreferences("userconfing", Context.MODE_PRIVATE);
        boolean migrateCurrentAccount = account == UserConfig.selectedAccount && !global.getBoolean("ghostModeAccountMigrationDone", false);
        enabled = preferences.getBoolean("enabled", migrateCurrentAccount && SharedConfig.ghostModeEnabled);
        hideOnline = preferences.getBoolean("hideOnline", migrateCurrentAccount ? SharedConfig.ghostModeHideOnline : true);
        hideTyping = preferences.getBoolean("hideTyping", migrateCurrentAccount ? SharedConfig.ghostModeHideTyping : true);
        ghostRead = preferences.getBoolean("ghostRead", migrateCurrentAccount ? SharedConfig.ghostModeGhostRead : true);
        markOnReply = preferences.getBoolean("markOnReply", migrateCurrentAccount ? SharedConfig.ghostModeMarkOnReply : true);
        hideStories = preferences.getBoolean("hideStories", migrateCurrentAccount ? SharedConfig.ghostModeHideStories : true);
        stealthSend = preferences.getBoolean("stealthSend", migrateCurrentAccount ? SharedConfig.ghostModeStealthSend : true);
        if (migrateCurrentAccount) {
            save();
            global.edit().putBoolean("ghostModeAccountMigrationDone", true).apply();
        }
    }

    public boolean isOnlineHidden() { return enabled && hideOnline; }
    public boolean isTypingHidden() { return enabled && hideTyping; }
    public boolean isGhostRead() { return enabled && ghostRead; }
    public boolean isStoriesHidden() { return enabled && hideStories; }
    public boolean isStealthSend() { return enabled && stealthSend; }

    public void save() {
        preferences.edit()
                .putBoolean("enabled", enabled)
                .putBoolean("hideOnline", hideOnline)
                .putBoolean("hideTyping", hideTyping)
                .putBoolean("ghostRead", ghostRead)
                .putBoolean("markOnReply", markOnReply)
                .putBoolean("hideStories", hideStories)
                .putBoolean("stealthSend", stealthSend)
                .apply();
    }
}
