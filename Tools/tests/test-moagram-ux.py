#!/usr/bin/env python3
"""Verify MoagramConfig persistence/notifications and Phase 1+2 UX wiring."""
from pathlib import Path
import re
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
java_root = root / 'TMessagesProj/src/main/java/org/telegram'
moagram_xml = (root / 'TMessagesProj/src/main/res/values/moagram.xml').read_text()

# 1. Verify all new UX activities only reference strings that exist in moagram.xml or standard resources.
defined_moagram_strings = set(re.findall(r'<string name="(\w+)">', moagram_xml))
for name in (
    'MoagramSettings', 'MoagramSettingsSubtitle', 'MoagramUxTitle',
    'LocalHistoryTitle', 'HiddenChatsTitle', 'GlobalRepeatsTitle',
    'BackgroundHealthHeader', 'GhostModeMarkReadNow', 'MoagramProfileIdLabel',
):
    assert name in defined_moagram_strings, f'Missing string {name} in moagram.xml'

# 2. Verify GhostModeActivity click handler lets LocalHistoryActivity open even when Ghost Mode is OFF.
ghost_src = (java_root / 'ui/GhostModeActivity.java').read_text()
assert ghost_src.index('position == localHistoryRow') < ghost_src.index('else if (!ghostMode.enabled)'), (
    'LocalHistoryActivity row must remain clickable when Ghost Mode is disabled'
)

# 3. Compile and execute production MoagramConfig against recording Android doubles.
stubs = {
    'android/app/Activity': 'public class Activity extends android.content.Context { public void startActivity(android.content.Intent i) {} }',
    'android/content/Intent': '''public class Intent {
        public Intent(String a) {}
        public Intent(String a, android.net.Uri u) {}
        public Intent putExtra(String k, String v) { return this; }
        public Intent putExtra(String k, int v) { return this; }
    }''',
    'android/content/pm/ApplicationInfo': 'public class ApplicationInfo { public int uid; }',
    'android/content/SharedPreferences': '''public class SharedPreferences {
        private final java.util.Map<String, Boolean> values = new java.util.HashMap<>();
        public boolean getBoolean(String k, boolean d) { return values.getOrDefault(k, d); }
        public Editor edit() { return new Editor(); }
        public class Editor {
            public Editor putBoolean(String k, boolean v) { values.put(k, v); return this; }
            public void apply() {}
        }
    }''',
    'android/content/Context': '''public class Context {
        public static final int MODE_PRIVATE = 0;
        public static final String POWER_SERVICE = "power";
        private final java.util.Map<String, SharedPreferences> stores = new java.util.HashMap<>();
        public SharedPreferences getSharedPreferences(String name, int mode) {
            return stores.computeIfAbsent(name, k -> new SharedPreferences());
        }
        public String getPackageName() { return "org.moagram.messenger.web"; }
        public Object getSystemService(String name) { return null; }
        public android.content.pm.ApplicationInfo getApplicationInfo() { return new android.content.pm.ApplicationInfo(); }
    }''',
    'android/net/Uri': 'public class Uri { public static Uri parse(String s) { return new Uri(); } }',
    'android/os/Build': 'public class Build { public static class VERSION { public static int SDK_INT = 36; } public static class VERSION_CODES { public static final int M = 23, O = 26; } }',
    'android/os/PowerManager': 'public class PowerManager { public boolean isIgnoringBatteryOptimizations(String pkg) { return true; } }',
    'android/provider/Settings': '''public class Settings {
        public static final String ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS = "battery";
        public static final String ACTION_APP_NOTIFICATION_SETTINGS = "notif";
        public static final String ACTION_APPLICATION_DETAILS_SETTINGS = "details";
        public static final String EXTRA_APP_PACKAGE = "pkg";
    }''',
    'androidx/core/app/NotificationManagerCompat': '''public class NotificationManagerCompat {
        public static NotificationManagerCompat from(android.content.Context c) { return new NotificationManagerCompat(); }
        public boolean areNotificationsEnabled() { return true; }
    }''',
    'org/telegram/messenger/ApplicationLoader': 'public class ApplicationLoader { public static android.content.Context applicationContext = new android.content.Context(); }',
    'org/telegram/messenger/FileLog': 'public class FileLog { public static void e(Throwable t) {} }',
    'org/telegram/messenger/LocaleController': 'public class LocaleController { public static String getString(int id) { return "str"; } }',
    'org/telegram/messenger/R': 'public class R { public static class string { public static final int Cancel = 1; } }',
    'org/telegram/messenger/SharedConfig': '''public class SharedConfig {
        public static boolean saveDeletedMessages, saveDeletedMedia, saveEditHistory,
            keepSelfDestructingMedia, cacheStoryViewers, allowSaveRestrictedMedia, ignoreAllRestrictions;
    }''',
    'org/telegram/messenger/NotificationCenter': '''public class NotificationCenter {
        public static final int moagramConfigChanged = 42;
        private static final NotificationCenter GLOBAL = new NotificationCenter();
        public int posts;
        public static NotificationCenter getGlobalInstance() { return GLOBAL; }
        public void postNotificationName(int id, Object... args) { if (id == moagramConfigChanged) posts++; }
    }''',
    'org/telegram/ui/ActionBar/Theme': 'public class Theme { public interface ResourcesProvider {} }',
    'org/telegram/ui/ActionBar/AlertDialog': '''public class AlertDialog {
        public interface OnButtonClickListener { void onClick(AlertDialog d, int w); }
        public static class Builder {
            public Builder(android.content.Context c, Theme.ResourcesProvider p) {}
            public Builder setTitle(CharSequence s) { return this; }
            public Builder setMessage(CharSequence s) { return this; }
            public Builder setPositiveButton(CharSequence s, OnButtonClickListener l) { return this; }
            public Builder setNegativeButton(CharSequence s, OnButtonClickListener l) { return this; }
            public AlertDialog show() { return new AlertDialog(); }
        }
    }''',
}

test_java = r'''
import org.telegram.messenger.*;

public class MoagramUxTest {
    static int checks;
    static void check(boolean cond, String msg) {
        checks++;
        if (!cond) throw new AssertionError(msg);
    }
    public static void main(String[] args) {
        MoagramConfig.resetForTests();
        check(MoagramConfig.isShowGhostModeButton(), "Ghost Mode quick button defaults to true");
        check(!MoagramConfig.isConfirmCalls(), "confirmCalls defaults to false");
        check(!MoagramConfig.isConfirmStickers(), "confirmStickers defaults to false");
        check(!MoagramConfig.isConfirmGifs(), "confirmGifs defaults to false");
        check(!MoagramConfig.isHideStoriesBar(), "hideStoriesBar defaults to false");
        check(!MoagramConfig.isHideGiftButton(), "hideGiftButton defaults to false");
        check(!MoagramConfig.isDisableNextChannelJump(), "disableNextChannelJump defaults to false");
        check(!MoagramConfig.isShowSecondsInTimestamps(), "showSecondsInTimestamps defaults to false");
        check(!MoagramConfig.isShowProfileIds(), "showProfileIds defaults to false");
        check(MoagramConfig.getEnabledUxModCount() == 1, "default enabled UX count is 1 (ghost button)");

        int before = NotificationCenter.getGlobalInstance().posts;
        MoagramConfig.setConfirmCalls(false);
        check(NotificationCenter.getGlobalInstance().posts == before, "setting unchanged flag does not emit event");

        MoagramConfig.setConfirmCalls(true);
        MoagramConfig.setConfirmStickers(true);
        MoagramConfig.setConfirmGifs(true);
        MoagramConfig.setHideStoriesBar(true);
        MoagramConfig.setHideGiftButton(true);
        MoagramConfig.setDisableNextChannelJump(true);
        MoagramConfig.setShowSecondsInTimestamps(true);
        MoagramConfig.setShowProfileIds(true);
        MoagramConfig.setShowGhostModeButton(false);
        check(NotificationCenter.getGlobalInstance().posts == before + 9, "each changed toggle emits moagramConfigChanged");
        check(MoagramConfig.getEnabledUxModCount() == 8, "enabled UX count tracks active toggles");

        // Reload from SharedPreferences to verify persistence.
        MoagramConfig.resetForTests();
        check(!MoagramConfig.isShowGhostModeButton(), "showGhostModeButton persists false");
        check(MoagramConfig.isConfirmCalls(), "confirmCalls persists true");
        check(MoagramConfig.isConfirmStickers(), "confirmStickers persists true");
        check(MoagramConfig.isConfirmGifs(), "confirmGifs persists true");
        check(MoagramConfig.isHideStoriesBar(), "hideStoriesBar persists true");
        check(MoagramConfig.isHideGiftButton(), "hideGiftButton persists true");
        check(MoagramConfig.isDisableNextChannelJump(), "disableNextChannelJump persists true");
        check(MoagramConfig.isShowSecondsInTimestamps(), "showSecondsInTimestamps persists true");
        check(MoagramConfig.isShowProfileIds(), "showProfileIds persists true");

        SharedConfig.saveDeletedMessages = false;
        SharedConfig.saveDeletedMedia = true;
        check(MoagramConfig.getEnabledLocalHistoryCount() == 0, "deleted media is not counted when deleted messages is off");
        SharedConfig.saveDeletedMessages = true;
        SharedConfig.saveEditHistory = true;
        check(MoagramConfig.getEnabledLocalHistoryCount() == 3, "local history count tracks enabled retention switches");

        System.out.println("Moagram UX config: " + checks + " checks passed");
    }
}
'''

with tempfile.TemporaryDirectory(prefix='moagram-ux-test-') as tmp:
    temp = Path(tmp)
    sources = [java_root / 'messenger/MoagramConfig.java']
    for name, body in stubs.items():
        path = temp / (name + '.java')
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('package ' + name.rsplit('/', 1)[0].replace('/', '.') + ';\n' + body)
        sources.append(path)
    test_path = temp / 'MoagramUxTest.java'
    test_path.write_text(test_java)
    sources.append(test_path)
    subprocess.run(['javac', '-d', str(temp), *map(str, sources)], check=True)
    subprocess.run(['java', '-cp', str(temp), 'MoagramUxTest'], check=True)
