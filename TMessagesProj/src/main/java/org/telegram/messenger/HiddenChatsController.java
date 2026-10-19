package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class HiddenChatsController {

    public static final int OPEN_FROM_TITLE = 0;
    public static final int OPEN_FROM_BUTTON = 1;
    public static final int OPEN_FROM_BOTH = 2;

    public static final int FLAG_OPEN_TITLE = 1;
    public static final int FLAG_OPEN_BUTTON = 2;
    public static final int FLAG_OPEN_SEARCH = 4;
    public static final int FLAG_OPEN_ALL_TAB = 8;
    public static final int FLAG_OPEN_DOUBLE_TAP = 16;
    public static final int FLAG_OPEN_MENU = 32;

    public static final int DEFAULT_OPEN_FLAGS = FLAG_OPEN_TITLE | FLAG_OPEN_SEARCH;

    private static volatile HiddenChatsController[] Instance = new HiddenChatsController[UserConfig.MAX_ACCOUNT_COUNT];

    public static HiddenChatsController getInstance(int num) {
        HiddenChatsController localInstance = Instance[num];
        if (localInstance == null) {
            synchronized (HiddenChatsController.class) {
                localInstance = Instance[num];
                if (localInstance == null) {
                    Instance[num] = localInstance = new HiddenChatsController(num);
                }
            }
        }
        return localInstance;
    }

    private final int currentAccount;
    private final Set<Long> hiddenDialogs = Collections.synchronizedSet(new HashSet<>());
    private String passcodeHash = "";
    private byte[] passcodeSalt = new byte[0];
    private boolean useTelegramPasscode = true;
    private boolean disguiseNotifications = true;
    private boolean hideNotifications = false;
    private int openFrom = DEFAULT_OPEN_FLAGS;

    private HiddenChatsController(int account) {
        currentAccount = account;
        loadConfig();
    }

    public void loadConfig() {
        if (ApplicationLoader.applicationContext == null) {
            return;
        }
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("hidden_chats_" + currentAccount, Context.MODE_PRIVATE);
        passcodeHash = preferences.getString("passcodeHash", "");
        String saltHex = preferences.getString("passcodeSalt", "");
        if (!TextUtils.isEmpty(saltHex)) {
            passcodeSalt = Utilities.hexToBytes(saltHex);
        } else {
            passcodeSalt = new byte[0];
        }
        useTelegramPasscode = preferences.getBoolean("useTelegramPasscode", TextUtils.isEmpty(passcodeHash));
        disguiseNotifications = preferences.getBoolean("disguiseNotifications", true);
        hideNotifications = preferences.getBoolean("hideNotifications", false);
        openFrom = preferences.getInt("openFrom", DEFAULT_OPEN_FLAGS);

        hiddenDialogs.clear();
        String dialogsStr = preferences.getString("hiddenDialogs", "");
        if (!TextUtils.isEmpty(dialogsStr)) {
            String[] split = dialogsStr.split(",");
            for (String s : split) {
                try {
                    s = s.trim();
                    if (!s.isEmpty()) {
                        hiddenDialogs.add(Long.parseLong(s));
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
        }
    }

    public void saveConfig() {
        if (ApplicationLoader.applicationContext == null) {
            return;
        }
        SharedPreferences.Editor editor = ApplicationLoader.applicationContext.getSharedPreferences("hidden_chats_" + currentAccount, Context.MODE_PRIVATE).edit();
        editor.putString("passcodeHash", passcodeHash);
        editor.putString("passcodeSalt", passcodeSalt != null && passcodeSalt.length > 0 ? Utilities.bytesToHex(passcodeSalt) : "");
        editor.putBoolean("useTelegramPasscode", useTelegramPasscode);
        editor.putBoolean("disguiseNotifications", disguiseNotifications);
        editor.putBoolean("hideNotifications", hideNotifications);
        editor.putInt("openFrom", openFrom);

        StringBuilder sb = new StringBuilder();
        synchronized (hiddenDialogs) {
            for (Long did : hiddenDialogs) {
                if (sb.length() > 0) {
                    sb.append(",");
                }
                sb.append(did);
            }
        }
        editor.putString("hiddenDialogs", sb.toString());
        editor.apply();
    }

    public boolean isUseTelegramPasscode() {
        return useTelegramPasscode;
    }

    public void setUseTelegramPasscode(boolean use) {
        useTelegramPasscode = use;
        saveConfig();
    }

    public boolean hasCustomPasscode() {
        return !TextUtils.isEmpty(passcodeHash);
    }

    public boolean isPasscodeSet() {
        if (useTelegramPasscode) {
            return !TextUtils.isEmpty(SharedConfig.passcodeHash);
        }
        return !TextUtils.isEmpty(passcodeHash);
    }

    public boolean checkPasscode(String passcode) {
        if (useTelegramPasscode) {
            return SharedConfig.checkPasscode(passcode);
        }
        if (TextUtils.isEmpty(passcode) || TextUtils.isEmpty(passcodeHash)) {
            return false;
        }
        try {
            byte[] passcodeBytes = passcode.getBytes("UTF-8");
            byte[] bytes = new byte[32 + passcodeBytes.length];
            if (passcodeSalt != null && passcodeSalt.length > 0) {
                System.arraycopy(passcodeSalt, 0, bytes, 0, 16);
                System.arraycopy(passcodeBytes, 0, bytes, 16, passcodeBytes.length);
                System.arraycopy(passcodeSalt, 0, bytes, passcodeBytes.length + 16, 16);
            } else {
                System.arraycopy(passcodeBytes, 0, bytes, 0, passcodeBytes.length);
            }
            String hash = Utilities.bytesToHex(Utilities.computeSHA256(bytes, 0, bytes.length));
            return passcodeHash.equals(hash);
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    public boolean setPasscode(String passcode) {
        if (TextUtils.isEmpty(passcode)) {
            passcodeHash = "";
            passcodeSalt = new byte[0];
            saveConfig();
            return true;
        }
        try {
            passcodeSalt = new byte[16];
            Utilities.random.nextBytes(passcodeSalt);
            byte[] passcodeBytes = passcode.getBytes("UTF-8");
            byte[] bytes = new byte[32 + passcodeBytes.length];
            System.arraycopy(passcodeSalt, 0, bytes, 0, 16);
            System.arraycopy(passcodeBytes, 0, bytes, 16, passcodeBytes.length);
            System.arraycopy(passcodeSalt, 0, bytes, passcodeBytes.length + 16, 16);
            passcodeHash = Utilities.bytesToHex(Utilities.computeSHA256(bytes, 0, bytes.length));
            useTelegramPasscode = false;
            saveConfig();
            return true;
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    public boolean isChatHidden(long dialogId) {
        return hiddenDialogs.contains(dialogId);
    }

    public void hideChat(long dialogId) {
        if (dialogId == 0) {
            return;
        }
        hiddenDialogs.add(dialogId);
        saveConfig();
        MessagesController messagesController = MessagesController.getInstance(currentAccount);
        TLRPC.Dialog dialog = messagesController != null ? messagesController.dialogs_dict.get(dialogId) : null;
        if (dialog != null && dialog.folder_id == 1) {
            if (messagesController.getDialogUnreadCount(dialog) > 0 || dialog.unread_mark) {
                MessagesStorage.getInstance(currentAccount).adjustArchiveUnreadCount(-1);
            }
        }
        MessagesStorage.getInstance(currentAccount).updateUnreadCounters();
        notifyStateChanged();
    }

    public void unhideChat(long dialogId) {
        hiddenDialogs.remove(dialogId);
        saveConfig();
        MessagesController messagesController = MessagesController.getInstance(currentAccount);
        TLRPC.Dialog dialog = messagesController != null ? messagesController.dialogs_dict.get(dialogId) : null;
        if (dialog != null && dialog.folder_id == 1) {
            if (messagesController.getDialogUnreadCount(dialog) > 0 || dialog.unread_mark) {
                MessagesStorage.getInstance(currentAccount).adjustArchiveUnreadCount(1);
            }
        }
        MessagesStorage.getInstance(currentAccount).updateUnreadCounters();
        notifyStateChanged();
    }

    public boolean hasHiddenChats() {
        return !hiddenDialogs.isEmpty();
    }

    public int getHiddenChatsCount() {
        return hiddenDialogs.size();
    }

    public ArrayList<TLRPC.Dialog> getHiddenDialogs(MessagesController messagesController) {
        ArrayList<TLRPC.Dialog> result = new ArrayList<>();
        if (messagesController == null || hiddenDialogs.isEmpty()) {
            return result;
        }
        synchronized (hiddenDialogs) {
            for (Long did : hiddenDialogs) {
                TLRPC.Dialog dialog = messagesController.dialogs_dict.get(did);
                if (dialog != null) {
                    result.add(dialog);
                }
            }
        }
        Collections.sort(result, (d1, d2) -> Integer.compare(d2.last_message_date, d1.last_message_date));
        return result;
    }

    public boolean isDisguiseNotifications() {
        return disguiseNotifications;
    }

    public void setDisguiseNotifications(boolean value) {
        disguiseNotifications = value;
        saveConfig();
    }

    public boolean isHideNotifications() {
        return hideNotifications;
    }

    public int getOpenFrom() {
        return openFrom;
    }

    public void setOpenFrom(int value) {
        openFrom = value;
        if (openFrom == 0) {
            openFrom = DEFAULT_OPEN_FLAGS;
        }
        saveConfig();
        notifyStateChanged();
    }

    public boolean opensFromTitle() {
        return openFrom == OPEN_FROM_TITLE || openFrom == OPEN_FROM_BOTH || (openFrom & FLAG_OPEN_TITLE) != 0;
    }

    public boolean opensFromButton() {
        return openFrom == OPEN_FROM_BUTTON || openFrom == OPEN_FROM_BOTH || (openFrom & FLAG_OPEN_BUTTON) != 0;
    }

    public boolean opensFromSearch() {
        return openFrom == OPEN_FROM_TITLE || openFrom == OPEN_FROM_BOTH || openFrom == DEFAULT_OPEN_FLAGS || (openFrom & FLAG_OPEN_SEARCH) != 0;
    }

    public boolean opensFromAllTab() {
        return (openFrom & FLAG_OPEN_ALL_TAB) != 0;
    }

    public boolean opensFromDoubleTapTitle() {
        return (openFrom & FLAG_OPEN_DOUBLE_TAP) != 0;
    }

    public boolean opensFromMenu() {
        return (openFrom & FLAG_OPEN_MENU) != 0;
    }

    public void setOpenFlag(int flag, boolean enabled) {
        if (enabled) {
            openFrom |= flag;
        } else {
            openFrom &= ~flag;
        }
        if ((openFrom & (FLAG_OPEN_TITLE | FLAG_OPEN_BUTTON | FLAG_OPEN_SEARCH | FLAG_OPEN_ALL_TAB | FLAG_OPEN_DOUBLE_TAP | FLAG_OPEN_MENU)) == 0) {
            openFrom = DEFAULT_OPEN_FLAGS;
        }
        saveConfig();
        notifyStateChanged();
    }

    public void setHideNotifications(boolean value) {
        hideNotifications = value;
        saveConfig();
    }

    private void notifyStateChanged() {
        AndroidUtilities.runOnUIThread(() -> {
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.dialogsNeedReload);
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_ALL);
        });
    }
}
