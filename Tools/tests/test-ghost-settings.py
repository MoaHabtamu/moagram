#!/usr/bin/env python3
"""Execute Ghost Mode's production controller, toggle/bind and Settings resume paths.

Android widgets/preferences are recording doubles. The saved-state and row-refresh
logic are extracted from production so the first-login/rebind regression fails on
its original implementation, without a Telegram account or network requests.
"""
from pathlib import Path
import re
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
java_root = root / 'TMessagesProj/src/main/java/org/telegram'
ghost = (java_root / 'ui/GhostModeActivity.java').read_text()
settings = (java_root / 'ui/SettingsActivity.java').read_text()


def block(source, marker):
    start = source.index(marker)
    opening = source.index('{', start)
    end, depth = opening + 1, 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


bind = block(ghost, 'public void onBindViewHolder(')
click = ghost[ghost.index('listView.setOnItemClickListener'):]
constructors = '\n'.join(block(ghost, signature).replace('GhostModeActivity', 'GhostScreen')
                         for signature in ('public GhostModeActivity()', 'public GhostModeActivity(int account)')
                         if signature in ghost)
row = next(line.strip() for line in settings.splitlines()
           if 'items.add(SettingCell.Factory.of(4,' in line and 'GhostModeController' in line)
open_settings = re.search(r'case 4:\s*(presentSettingFragment\(new GhostModeActivity\([^;]+;)', settings)[1]
open_profile = re.search(r'position == ghostModeRow\) \{\s*(presentFragment\(new GhostModeActivity\([^;]+;)',
                         (java_root / 'ui/ProfileActivity.java').read_text())[1]
open_privacy = re.search(r'position == ghostModeRow\) \{\s*(presentFragment\(new GhostModeActivity\([^;]+;)',
                         (java_root / 'ui/PrivacySettingsActivity.java').read_text())[1]
replacements = {
    'GHOST_CONSTRUCTORS': constructors,
    'GHOST_ROWS': '\n'.join(re.findall(r'private int \w+;', ghost)),
    'GHOST_UPDATE_ROWS': block(ghost, 'private void updateRows()'),
    'MASTER_BIND': block(bind, 'if (position == masterRow)'),
    'ONLINE_BIND': block(bind, 'if (position == hideOnlineRow)'),
    'MASTER_CLICK': block(click, 'if (position == masterRow)'),
    'GHOST_RESUME': block(ghost, 'public void onResume()'),
    'SETTINGS_RESUME': block(settings, 'public void onResume()'),
    'SETTINGS_ROW': row,
    'REGISTER_OBSERVER': next((line.strip() for line in settings.splitlines() if 'addObserver(this, NotificationCenter.ghostModeChanged)' in line), ''),
    'REMOVE_OBSERVER': next((line.strip() for line in settings.splitlines() if 'removeObserver(this, NotificationCenter.ghostModeChanged)' in line), ''),
    'GHOST_EVENT_HANDLER': block(settings, 'if (id == NotificationCenter.ghostModeChanged)') if 'if (id == NotificationCenter.ghostModeChanged)' in settings else '',
    'OPEN_SETTINGS': open_settings.replace('GhostModeActivity', 'GhostScreen'),
    'OPEN_PROFILE': open_profile.replace('GhostModeActivity', 'GhostScreen'),
    'OPEN_PRIVACY': open_privacy.replace('GhostModeActivity', 'GhostScreen'),
    'OPEN_SEARCH': (re.search(r'new SearchResult\(150,[^\n]+f\.(presentFragment\(new GhostModeActivity\([^)]*\)\))',
                              (java_root / 'ui/ProfileActivity.java').read_text())[1] + ';').replace('GhostModeActivity', 'GhostScreen'),
}
ids = sorted(set(re.findall(r'R\.string\.(\w+)', '\n'.join(replacements.values()))))
stubs = {
    'android/content/SharedPreferences': '''public class SharedPreferences {
        private final java.util.Map<String,Boolean> values=new java.util.HashMap<>();
        public boolean getBoolean(String key,boolean fallback) { return values.getOrDefault(key,fallback); }
        public Editor edit() { return new Editor(); }
        public class Editor {
            public Editor putBoolean(String key,boolean value) { values.put(key,value); return this; }
            public void apply() {}
        }
    }''',
    'android/content/Context': '''public class Context {
        public static final int MODE_PRIVATE=0;
        private final java.util.Map<String,SharedPreferences> stores=new java.util.HashMap<>();
        public SharedPreferences getSharedPreferences(String name,int mode) {
            return stores.computeIfAbsent(name,key->new SharedPreferences());
        }
    }''',
    'org/telegram/messenger/ApplicationLoader': '''public class ApplicationLoader {
        public static android.content.Context applicationContext=new android.content.Context();
    }''',
    'org/telegram/messenger/UserConfig': '''public class UserConfig {
        public static final int MAX_ACCOUNT_COUNT=4;
        public static int selectedAccount;
    }''',
    'org/telegram/messenger/SharedConfig': '''public class SharedConfig {
        public static boolean ghostModeEnabled;
        public static boolean ghostModeHideOnline=true, ghostModeHideTyping=true, ghostModeGhostRead=true,
            ghostModeMarkOnReply=true, ghostModeHideStories=true, ghostModeStealthSend=true;
    }''',
    'org/telegram/messenger/NotificationCenter': '''public class NotificationCenter {
        public static final int ghostModeChanged=1;
        public interface NotificationCenterDelegate { void didReceivedNotification(int id,int account,Object... args); }
        private static final java.util.Map<Integer,NotificationCenter> instances=new java.util.HashMap<>();
        private final java.util.Set<NotificationCenterDelegate> delegates=new java.util.HashSet<>();
        private final int account;
        public int posts;
        private NotificationCenter(int value) { account=value; }
        public static NotificationCenter getInstance(int account) { return instances.computeIfAbsent(account,NotificationCenter::new); }
        public void addObserver(NotificationCenterDelegate delegate,int event) { delegates.add(delegate); }
        public void removeObserver(NotificationCenterDelegate delegate,int event) { delegates.remove(delegate); }
        public void postNotificationName(int event,Object... args) {
            posts++;
            for (NotificationCenterDelegate delegate:new java.util.ArrayList<>(delegates)) delegate.didReceivedNotification(event,account,args);
        }
    }''',
    'org/telegram/messenger/R': 'public class R { public static class string {' + ''.join(
        f'public static final int {name}={i};' for i, name in enumerate(ids)) +
        '} public static class drawable { public static int msg_stories_stealth; } }',
}
java = r'''
import org.telegram.messenger.*;
import java.lang.reflect.Field;
import java.util.*;
public class GhostSettingsTest {
    static int checks;
    static List<String> failures=new ArrayList<>();
    static void check(boolean value,String message) {
        checks++; if (!value) failures.add(message);
    }
    static String getString(int id) {
        return id==R.string.NotificationsOn ? "On" : id==R.string.NotificationsOff ? "Off" : "text";
    }
    static class LocaleController { static String getString(int id) { return GhostSettingsTest.getString(id); } }
    static class Base {
        int currentAccount=UserConfig.selectedAccount;
        public void onResume() {}
        public void setCurrentAccount(int account) { currentAccount=account; }
        NotificationCenter getNotificationCenter() { return NotificationCenter.getInstance(currentAccount); }
    }
    static class TextCheckCell {
        boolean checked,enabled=true;
        void setChecked(boolean value) { checked=value; }
        void setEnabled(boolean value,Object ignored) { enabled=value; }
        void setTextAndValueAndCheck(String a,String b,boolean c,boolean d,boolean e) { checked=c; }
    }
    static class MessagesController {
        static MessagesController[] accounts={new MessagesController(),new MessagesController(),new MessagesController(),new MessagesController()};
        int offlineRequests;
        static MessagesController getInstance(int account) { return accounts[account]; }
        void sendOfflineStatus() { offlineRequests++; }
    }
    static class GhostScreen extends Base {
        GHOST_ROWS
        TextCheckCell master=new TextCheckCell(),online=new TextCheckCell();
        class ListAdapter { void notifyDataSetChanged() { bindRows(); } }
        ListAdapter listAdapter=new ListAdapter();
        GHOST_CONSTRUCTORS
        GHOST_UPDATE_ROWS
        void init() {
            updateRows(); bindRows();
            // RecyclerListView enables an attached master row through SelectionAdapter.isEnabled().
            master.setEnabled(true,null);
        }
        void bindRows() {
            GhostModeController ghostMode=GhostModeController.getInstance(currentAccount);
            boolean enabled=ghostMode.enabled;
            TextCheckCell checkCell=master;
            int position=masterRow;
            MASTER_BIND
            checkCell=online; position=hideOnlineRow;
            ONLINE_BIND
        }
        void tapMaster() {
            if (!master.enabled) return;
            TextCheckCell view=master;
            int position=masterRow;
            GhostModeController ghostMode=GhostModeController.getInstance(currentAccount);
            MASTER_CLICK
        }
        GHOST_RESUME
    }
    static class Item {
        String summary;
        Item(String value) { summary=value; }
    }
    static class SettingCell {
        static class Factory { static Item of(int id,int t,int b,int icon,String title,String summary) { return new Item(summary); } }
    }
    static class IconBackgroundColors {
        static IconBackgroundColors CYAN=new IconBackgroundColors();
        int top,bottom;
    }
    static class SettingsScreen extends Base implements NotificationCenter.NotificationCenterDelegate {
        class Adapter {
            ArrayList<Item> items=new ArrayList<>();
            String rendered;
            int rebuilds;
            void update(boolean animated) { rebuilds++; items.clear(); fill(items); notifyDataSetChanged(); }
            void notifyDataSetChanged() { rendered=items.get(0).summary; }
        }
        class ListView { Adapter adapter=new Adapter(); }
        ListView listView=new ListView();
        GhostScreen opened;
        SettingsScreen(int account) { currentAccount=account; listView.adapter.update(false); startObserving(); }
        void startObserving() { REGISTER_OBSERVER }
        void stopObserving() { REMOVE_OBSERVER }
        public void didReceivedNotification(int id,int account,Object... args) { GHOST_EVENT_HANDLER }
        void fill(ArrayList<Item> items) { SETTINGS_ROW }
        String status() { return listView.adapter.rendered; }
        void presentSettingFragment(GhostScreen screen) { opened=screen; }
        void presentFragment(GhostScreen screen) { opened=screen; }
        GhostScreen openSettings() { OPEN_SETTINGS return opened; }
        GhostScreen openProfile() { OPEN_PROFILE return opened; }
        GhostScreen openPrivacy() { OPEN_PRIVACY return opened; }
        GhostScreen openSearch() { OPEN_SEARCH return opened; }
        SETTINGS_RESUME
    }
    static void gates(GhostModeController mode,boolean enabled,String when) {
        check(mode.enabled==enabled,when+": controller state");
        check(mode.isOnlineHidden()==enabled,when+": online gate");
        check(mode.isTypingHidden()==enabled,when+": typing gate");
        check(mode.isGhostRead()==enabled,when+": read gate");
        check(mode.isStoriesHidden()==enabled,when+": story gate");
        check(mode.isStealthSend()==enabled,when+": send gate");
    }
    public static void main(String[] args) throws Exception {
        UserConfig.selectedAccount=0;
        // Controllers can exist before login; opening Settings must not need a restart to refresh them.
        GhostModeController mode=GhostModeController.getInstance(0);
        check(NotificationCenter.getInstance(0).posts==0,"legacy migration does not publish a partially constructed controller");
        SettingsScreen settings=new SettingsScreen(0);
        check(settings.status().equals("Off"),"fresh-login Settings starts off");
        GhostScreen screen=settings.openSettings(); screen.init();
        screen.onResume();
        check(screen.master.enabled,"master is tappable after the first post-login resume and rebind");
        screen.tapMaster();
        gates(mode,true,"immediately after first toggle");
        check(screen.master.checked && screen.master.enabled,"master remains checked and tappable after rebind");
        check(screen.online.enabled,"feature rows enabled immediately");
        check(ApplicationLoader.applicationContext.getSharedPreferences("ghost_mode_account_0",0).getBoolean("enabled",false),"first toggle saved");
        check(settings.status().equals("On"),"visible Settings refreshes immediately without an account switch or resume");
        settings.onResume();
        check(settings.status().equals("On"),"returning to the same Settings instance rebuilds cached Off value");
        settings.stopObserving();
        mode.setEnabled(false);
        settings.onResume();
        check(settings.status().equals("Off"),"resume rebuilds snapshot values even when the change event was missed");
        settings.startObserving();
        mode.setEnabled(true);
        screen.onResume();
        check(screen.master.enabled,"master remains enabled after resuming the same Ghost Mode screen");
        for (int i=0;i<6;i++) {
            boolean enabled=(i%2)==1;
            screen.tapMaster();
            gates(mode,enabled,"repeated toggle "+i);
            check(screen.master.enabled && screen.master.checked==enabled,"switch tracks repeated toggle "+i);
            check(screen.online.enabled==enabled,"dependent rows track toggle "+i);
            settings.onResume();
            check(settings.status().equals(enabled?"On":"Off"),"Settings tracks repeated toggle "+i);
        }
        mode.enabled=false; mode.save();
        UserConfig.selectedAccount=1;
        GhostModeController other=GhostModeController.getInstance(1);
        GhostScreen[] entries={settings.openSettings(),settings.openPrivacy(),settings.openProfile(),settings.openSearch()};
        for (GhostScreen entry:entries) check(entry.currentAccount==0,"Ghost screen inherits its parent account, not a stale selected account");
        GhostScreen owned=entries[0]; owned.init(); owned.tapMaster();
        check(mode.enabled && !other.enabled,"toggle updates only its owning account");
        check(MessagesController.getInstance(0).offlineRequests>0 && MessagesController.getInstance(1).offlineRequests==0,"offline status uses the owning account");
        Field field=GhostModeController.class.getDeclaredField("instances"); field.setAccessible(true);
        ((GhostModeController[])field.get(null))[0]=null;
        check(GhostModeController.getInstance(0).enabled,"enabled setting survives controller recreation");
        check(!GhostModeController.getInstance(1).enabled,"other account stays unchanged after reload");
        int updates=settings.listView.adapter.rebuilds;
        other.setEnabled(true); other.setEnabled(false);
        check(settings.listView.adapter.rebuilds==updates,"another account's events do not refresh this Settings page");
        GhostModeController reloaded=GhostModeController.getInstance(0);
        int events=NotificationCenter.getInstance(0).posts;
        reloaded.setEnabled(true);
        check(NotificationCenter.getInstance(0).posts==events,"setting an unchanged value does not publish another event");
        settings.stopObserving();
        reloaded.setEnabled(false);
        check(settings.listView.adapter.rebuilds==updates,"Settings removes its Ghost Mode observer when destroyed");
        if (!failures.isEmpty()) throw new AssertionError(failures.size()+" failures: "+String.join("; ",failures));
        System.out.println("Ghost settings: "+checks+" checks passed (first-login toggle, rebind, Settings refresh, account ownership, persistence)");
    }
}
'''
for key, value in replacements.items():
    java = java.replace(key, value)
with tempfile.TemporaryDirectory(prefix='moagram-ghost-settings-') as temporary:
    temp = Path(temporary)
    sources = [java_root / 'messenger/GhostModeController.java']
    for name, body in stubs.items():
        path = temp / (name + '.java')
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('package ' + name.rsplit('/', 1)[0].replace('/', '.') + ';\n' + body)
        sources.append(path)
    test = temp / 'GhostSettingsTest.java'; test.write_text(java); sources.append(test)
    subprocess.run(['javac', '-d', str(temp), *map(str, sources)], check=True)
    subprocess.run(['java', '-cp', str(temp), 'GhostSettingsTest'], check=True)
