#!/usr/bin/env python3
"""Run the production contact sort and refresh paths against mixed presence states."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
adapter = (root / 'TMessagesProj/src/main/java/org/telegram/ui/Adapters/ContactsAdapter.java').read_text()
activity = (root / 'TMessagesProj/src/main/java/org/telegram/ui/ContactsActivity.java').read_text()
cell = (root / 'TMessagesProj/src/main/java/org/telegram/ui/Cells/UserCell.java').read_text()


def block(source, marker):
    start = source.index(marker)
    opening = source.index('{', start)
    end, depth = opening + 1, 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


sort = block(adapter, 'public void sortOnlineContacts()')
key = block(adapter, 'private static long statusSortKey(')
resume = block(activity, 'public void onResume()')
status_update = block(activity, '} else if (id == NotificationCenter.updateInterfaces)')[
    len('} else if (id == NotificationCenter.updateInterfaces)'):]
online_line = next(line.strip() for line in cell.splitlines()
                   if line.strip().startswith('if (currentUser.id == UserConfig.getInstance'))
online = online_line[len('if ('):-len(') {')]

java = r'''
import java.util.*;
public class ContactSortingTest {
    static class TLRPC {
        static class User { long id; boolean bot; Status status; }
        static class Status { int expires; boolean by_me; }
        static class TL_userStatusOnline extends Status {}
        static class TL_userStatusOffline extends Status {}
        static class TL_userStatusRecently extends Status {}
        static class TL_userStatusLastWeek extends Status {}
        static class TL_userStatusLastMonth extends Status {}
        static class TL_contact { long user_id; TL_contact(long id) { user_id = id; } }
    }
    static class LongSparseArray<V> {
        final Map<Long,V> map = new HashMap<>();
        LongSparseArray(int capacity) {}
        V get(long id) { return map.get(id); }
        void put(long id, V value) { map.put(id, value); }
    }
    static class MessagesController {
        static final int UPDATE_MASK_STATUS = 1, UPDATE_MASK_AVATAR = 2, UPDATE_MASK_NAME = 4;
        static final MessagesController instance = new MessagesController();
        final Map<Long,TLRPC.User> users = new HashMap<>();
        final Map<Long,Integer> onlinePrivacy = new HashMap<>();
        int reads;
        static MessagesController getInstance(int account) { return instance; }
        TLRPC.User getUser(long id) { reads++; return users.get(id); }
    }
    static class ConnectionsManager {
        static final ConnectionsManager instance = new ConnectionsManager();
        static ConnectionsManager getInstance(int account) { return instance; }
        int getCurrentTime() { return 1000; }
    }
    static class UserConfig {
        static final UserConfig instance = new UserConfig();
        static UserConfig getInstance(int account) { return instance; }
        long getClientUserId() { return 999; }
    }
    static class FileLog { static void e(Exception e) { throw new AssertionError(e); } }
    static final int SORT_TYPE_BY_TIME = 2;
    int currentAccount, sortType = SORT_TYPE_BY_TIME, notifications;
    ArrayList<TLRPC.TL_contact> onlineContacts = new ArrayList<>();
    void notifyDataSetChanged() { notifications++; }
    // Production methods, not a second implementation of the comparator.
    %s
    %s
    boolean displayedOnline(TLRPC.User currentUser) {
        return currentUser != null && !currentUser.bot && (%s);
    }
    static class Base { public void onResume() {} }
    static class ContactsController {
        void reloadContactsStatusesMaybe(boolean force) {}
    }
    class Screen extends Base {
        ContactSortingTest listViewAdapter = ContactSortingTest.this;
        boolean sortByName;
        int rowsUpdated, notificationsAtRowUpdate;
        ContactsController getContactsController() { return new ContactsController(); }
        void updateVisibleRows(int mask) { rowsUpdated++; notificationsAtRowUpdate = notifications; }
        %s
        void statusEvent(int incomingMask) {
            Object[] args = {incomingMask};
            %s
        }
    }
    static int checks;
    static void check(boolean value, String description) {
        checks++;
        if (!value) throw new AssertionError(description);
    }
    TLRPC.User user(long id, TLRPC.Status status, int expires) {
        TLRPC.User user = new TLRPC.User();
        user.id = id; user.status = status;
        if (status != null) status.expires = expires;
        MessagesController.instance.users.put(id, user);
        return user;
    }
    List<Long> ids() {
        ArrayList<Long> result = new ArrayList<>();
        for (TLRPC.TL_contact c : onlineContacts) result.add(c.user_id);
        return result;
    }
    void onlineFirst() {
        boolean offlineSeen = false;
        for (TLRPC.TL_contact c : onlineContacts) {
            boolean online = displayedOnline(MessagesController.instance.users.get(c.user_id));
            check(!(offlineSeen && online), "online contact below offline contact: " + ids());
            offlineSeen |= !online;
        }
    }
    public static void main(String[] args) {
        ContactSortingTest a = new ContactSortingTest();
        MessagesController controller = MessagesController.instance;
        a.user(1, new TLRPC.TL_userStatusOnline(), 1100);
        a.user(2, new TLRPC.TL_userStatusOffline(), 900);
        a.user(3, new TLRPC.TL_userStatusRecently(), -100);
        controller.onlinePrivacy.put(3L, 999);
        a.user(4, new TLRPC.TL_userStatusOffline(), 600);
        a.user(5, new TLRPC.TL_userStatusOnline(), 0);
        a.user(6, new TLRPC.TL_userStatusOnline(), 800);
        a.user(7, new TLRPC.TL_userStatusOffline(), 1050);
        a.user(8, new TLRPC.TL_userStatusRecently(), -1000).status.by_me = true;
        a.user(9, new TLRPC.TL_userStatusLastWeek(), -101);
        a.user(10, new TLRPC.TL_userStatusLastMonth(), -1002).status.by_me = true;
        a.user(11, new TLRPC.TL_userStatusRecently(), 0);
        a.user(12, null, 0);
        controller.onlinePrivacy.put(12L, 999);
        a.user(13, null, 0);
        a.user(14, new TLRPC.TL_userStatusOnline(), 1200).bot = true;
        // A missing cached user must remain safe as well.
        for (long id = 1; id <= 15; id++) a.onlineContacts.add(new TLRPC.TL_contact(id));
        Random random = new Random(7);
        for (int run = 0; run < 50; run++) {
            Collections.shuffle(a.onlineContacts, random);
            int reads = controller.reads;
            a.sortOnlineContacts();
            a.onlineFirst();
            check(controller.reads - reads == 15, "presence snapshot taken once per contact");
            List<Long> ids = a.ids();
            check(ids.indexOf(2L) < ids.indexOf(4L), "exact last seen descends");
            check(ids.indexOf(8L) < ids.indexOf(9L), "private recent precedes last week");
            check(ids.indexOf(11L) < ids.indexOf(9L), "uninitialised recent precedes last week");
            check(ids.indexOf(9L) < ids.indexOf(10L), "last week precedes private last month");
            check(ids.indexOf(10L) < ids.indexOf(13L), "unknown last seen is last");
            List<Long> stable = a.ids();
            a.sortOnlineContacts();
            check(a.ids().equals(stable), "equal statuses keep a stable order");
        }
        check(controller.users.get(11L).status.expires == 0, "sorting does not mutate shared status");
        Screen screen = a.new Screen();
        controller.onlinePrivacy.remove(3L);
        controller.onlinePrivacy.remove(12L);
        controller.users.get(2L).status = new TLRPC.TL_userStatusOnline();
        int before = a.notifications;
        screen.statusEvent(MessagesController.UPDATE_MASK_STATUS);
        check(a.notifications == before + 1, "status notification sorts immediately");
        check(screen.notificationsAtRowUpdate == a.notifications, "sort precedes repaint");
        a.onlineFirst();
        check(a.ids().indexOf(2L) < a.ids().indexOf(3L), "new online contact rises after privacy hint expires");
        controller.onlinePrivacy.put(13L, 1000);
        screen.onResume();
        a.onlineFirst();
        check(a.ids().indexOf(13L) < a.ids().indexOf(3L), "returning to contacts refreshes the order");
        before = a.notifications;
        screen.statusEvent(MessagesController.UPDATE_MASK_AVATAR);
        check(a.notifications == before, "avatar-only change does not sort");
        screen.sortByName = true;
        screen.statusEvent(MessagesController.UPDATE_MASK_STATUS);
        check(a.notifications == before, "alphabetical mode is not reordered");
        screen.onResume();
        check(a.notifications == before + 1, "alphabetical mode still refreshes on resume");
        a.sortType = 0;
        List<Long> original = a.ids();
        a.sortOnlineContacts();
        check(a.ids().equals(original), "non-time adapter order is preserved");
        a.onlineContacts = null;
        a.sortOnlineContacts();
        screen.listViewAdapter = null;
        screen.sortByName = false;
        screen.onResume();
        screen.statusEvent(MessagesController.UPDATE_MASK_STATUS);
        System.out.println("Contact sorting checks passed: " + checks);
    }
}
''' % (sort, key, online, resume, status_update)

with tempfile.TemporaryDirectory() as temporary:
    source = Path(temporary) / 'ContactSortingTest.java'
    source.write_text(java)
    subprocess.run(['javac', '-d', temporary, str(source)], check=True)
    subprocess.run(['java', '-cp', temporary, 'ContactSortingTest'], check=True)
